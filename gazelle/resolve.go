// resolve.go implements Gazelle's Resolver interface (Imports, Embeds,
// Resolve). Imports indexes each rule's provided namespace; Resolve fills
// :deps once the cross-package index is built.
//
// Static deps (org_clojure_clojure + import-deps + gen-class-deps +
// ns-library-meta extras) are pre-merged by the bb server's ns-rules
// and seeded into the depSet from the rule's existing :deps. This file
// adds only what needs Gazelle's cross-package index: intra-repo
// FindRulesByImport, per-platform external resolution via DepNsLabels,
// and per-target deps_bazel overrides keyed on the final Bazel label.
//
// `clojure_deps_repo` directive scope: per-package overrides retag only
// labels added by this file. Seed deps were emitted by the bb server at
// init time using the root tag, so a sub-package override produces a
// mixed list — the seed deps reference Maven coords resolved against
// the root deps.edn and don't move.
package gazelle

import (
	"maps"
	"slices"

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
	if configs, ok := c.Exts[languageName].(*clojureconfig.Configs); ok {
		depsRepoTag = configs.DepsRepo(from.Pkg)
	}

	// Seed depSet with what the bb server's ns-rules already merged
	// (org_clojure_clojure + ns-library-meta + import-deps + gen-class-deps).
	depSet := make(map[string]struct{})
	for _, dep := range r.AttrStrings("deps") {
		depSet[dep] = struct{}{}
	}

	// Per-require resolution: try the intra-repo index first (platform-
	// independent), short-circuiting the external lookup on a hit since
	// the AOT target intra-repo emits already depends on the plain
	// library. For a .cljc require both :clj and :cljs labels are
	// emitted across the outer platform loop; depSet's map semantics
	// dedupe when both platforms resolve to the same label.
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

	r.SetAttr("deps", slices.Sorted(maps.Keys(depSet)))
}

// mergeDepsBazelTargetDeps adds per-target extra deps from
// deps_bazel.Deps[label].Deps. Per-target overrides are keyed on the rule's
// final Bazel label, which ns-rules doesn't know — so this stays Go-side
// where Gazelle hands us the `from` label during Resolve.
//
// Looks up under both `from.String()` (which may carry a bzlmod canonical
// repo prefix like "@@//pkg:name") AND the bare `//pkg:name` form, so
// deps.edn :bazel :deps entries written as bare labels still match under
// bzlmod canonicalization.
//
// Typed via the DepsBazel struct: malformed shapes (wrong nesting, wrong
// element types) fail at clojureparser.Init's json.Unmarshal with a clear
// error, before reaching this function. By the time this runs, the wire
// shape is known good.
func mergeDepsBazelTargetDeps(depSet map[string]struct{}, depsBazel clojureparser.DepsBazel, from label.Label) {
	keys := []string{from.String()}
	if bare := label.New("", from.Pkg, from.Name).String(); bare != keys[0] {
		keys = append(keys, bare)
	}
	for _, k := range keys {
		if target, ok := depsBazel.Deps[k]; ok {
			for _, dep := range target.Deps {
				depSet[dep] = struct{}{}
			}
			return
		}
	}
}
