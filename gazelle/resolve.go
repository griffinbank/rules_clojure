// resolve.go implements Gazelle's Resolver interface (Imports, Embeds,
// Resolve). Gazelle calls Imports(rule) per generated rule to index what
// each rule "provides" (here: the rule's namespace symbol so other
// packages' requires can find it). After indexing, Gazelle calls
// Resolve(rule) to fill in :deps.
//
// Static deps that don't need Gazelle's cross-package index
// (org_clojure_clojure, ns-library-meta extras, pre-resolved
// import-deps / gen-class-deps) are pre-merged Clojure-side via
// gen-build/ns-rules and seeded into the depSet from the rule's
// existing :deps. This file adds only:
//   - Intra-repo lookups via FindRulesByImport (a require like `foo.bar`
//     resolves to `//src/foo:bar` when another package generated it).
//   - Per-target deps_bazel overrides, which are keyed on the rule's
//     final Bazel label — unknowable to ns-rules, so Go must do it here.
//
// Note on `clojure_deps_repo` directive scope: per-package overrides only
// retag externally-resolved labels added by this file. Seed deps in the
// rule's existing :deps were emitted Clojure-side at init time using the
// root tag, so a sub-package override produces a mixed list. Mixing is
// intentional — the seed deps reference Maven coords resolved against the
// root deps.edn and don't move when a sub-package overrides for its own
// custom deps.bzl. If you need a sub-package to use a completely different
// dep universe, run a second Gazelle invocation with a different root.
package gazelle

import (
	"sort"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/label"
	"github.com/bazelbuild/bazel-gazelle/repo"
	"github.com/bazelbuild/bazel-gazelle/resolve"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

func (l *clojureLang) Imports(_ *config.Config, r *rule.Rule, f *rule.File) []resolve.ImportSpec {
	if r.Kind() != "clojure_library" {
		return nil
	}
	// Look up namespace from our generation-time mapping.
	key := f.Pkg + ":" + r.Name()
	if ns, ok := l.ruleNs[key]; ok {
		return []resolve.ImportSpec{
			{Lang: languageName, Imp: ns},
		}
	}
	// Fallback: rules already in existing BUILD files (not freshly generated
	// this run) — index every AOT'd namespace so cross-package :requires can
	// resolve to any of them. Rules with no AOT attr stay un-indexed: there
	// is no other source of truth for which namespaces a pre-existing rule
	// provides.
	aot := r.AttrStrings("aot")
	if len(aot) == 0 {
		return nil
	}
	specs := make([]resolve.ImportSpec, 0, len(aot))
	for _, ns := range aot {
		specs = append(specs, resolve.ImportSpec{Lang: languageName, Imp: ns})
	}
	return specs
}

func (*clojureLang) Embeds(_ *rule.Rule, _ label.Label) []label.Label {
	return nil
}

func (l *clojureLang) Resolve(c *config.Config, ix *resolve.RuleIndex, _ *repo.RemoteCache, r *rule.Rule, imports interface{}, from label.Label) {
	if r.Kind() != "clojure_library" {
		return
	}
	ns, ok := imports.(*clojureparser.NamespaceInfo)
	if !ok || ns == nil {
		return
	}

	session := l.session
	if session == nil {
		// Resolve runs after GenerateRules, which would have log.Fatalf'd if
		// the parser never started. Defensive guard for callers that skip
		// the normal lifecycle (tests, etc.).
		return
	}

	// Honour per-package `# gazelle:clojure_deps_repo @other_deps` for
	// external dep labels. DepsRepo walks the parent chain and falls back
	// to the default tag, so it always returns a non-empty value.
	depsRepoTag := session.depsRepoTag
	if configs, ok := c.Exts[languageName].(clojureconfig.Configs); ok {
		depsRepoTag = configs.DepsRepo(from.Pkg)
	}

	// Seed depSet with the deps gen-build/ns-rules already merged
	// (org_clojure_clojure + clojure-library-args + ns-library-meta +
	// import-deps + gen-class-deps). Resolve adds only what needs Gazelle's
	// cross-package index: intra-repo lookup, per-platform external
	// resolution, and per-target deps_bazel overrides.
	depSet := make(map[string]struct{})
	for _, dep := range r.AttrStrings("deps") {
		depSet[dep] = struct{}{}
	}

	// Per-require resolution: try intra-repo index first (platform-
	// independent), fall back to external DepNsLabels for this platform.
	// gen_srcs cross-products files × platforms, producing redundant deps
	// (both AOT and plain library for the same dep). We intentionally
	// don't replicate this — the AOT target already depends on the plain
	// library, so the extra dep is unnecessary.
	for _, platform := range ns.Platforms {
		reqNs, ok := ns.Requires[platform]
		if !ok {
			continue
		}
		for _, reqName := range reqNs {
			spec := resolve.ImportSpec{Lang: languageName, Imp: reqName}
			if matches := ix.FindRulesByImport(spec, languageName); len(matches) > 0 {
				lbl := matches[0].Label
				depSet[label.New("", lbl.Pkg, lbl.Name).String()] = struct{}{}
				continue
			}
			if platformMap, ok := session.initResp.DepNsLabels[platform]; ok {
				if lbl, ok := platformMap[reqName]; ok {
					depSet[depsRepoTag+"//:"+lbl] = struct{}{}
				}
			}
		}
	}

	// Per-target deps_bazel override (can't be pre-merged on the Clojure
	// side — ns-rules doesn't know the eventual Bazel label).
	mergeDepsBazelTargetDeps(depSet, session.initResp.DepsBazel, from)

	// Exclude self-deps (e.g. .cljc files with :require-macros of themselves
	// resolve to the same target). Self-label is rebuilt with an empty repo
	// segment to match the form `depSet` entries use for intra-repo deps.
	selfLabel := label.New("", from.Pkg, from.Name).String()
	delete(depSet, selfLabel)

	deps := make([]string, 0, len(depSet))
	for dep := range depSet {
		deps = append(deps, dep)
	}
	sort.Strings(deps)
	r.SetAttr("deps", deps)
}

// mergeDepsBazelTargetDeps adds per-target extra deps from
// deps_bazel.Deps[label].Deps. Per-target overrides are keyed on the rule's
// final Bazel label, which ns-rules doesn't know — so this stays Go-side
// where Gazelle hands us the `from` label during Resolve.
//
// Typed via the DepsBazel struct: malformed shapes (wrong nesting, wrong
// element types) fail at clojureparser.Init's json.Unmarshal with a clear
// error, before reaching this function. By the time this runs, the wire
// shape is known good.
func mergeDepsBazelTargetDeps(depSet map[string]struct{}, depsBazel clojureparser.DepsBazel, from label.Label) {
	target, ok := depsBazel.Deps[from.String()]
	if !ok {
		return
	}
	for _, dep := range target.Deps {
		depSet[dep] = struct{}{}
	}
}
