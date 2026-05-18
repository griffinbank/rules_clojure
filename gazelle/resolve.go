// resolve.go implements Gazelle's Resolver interface. Imports indexes
// each rule's provided namespace; Resolve fills :deps once the cross-
// package index is built. Static deps (org_clojure_clojure + import-deps
// + gen-class-deps + ns-library-meta) are pre-merged by the bb server;
// this file adds intra-repo FindRulesByImport hits, per-platform external
// resolution via DepNsLabels, and per-target deps_bazel overrides.
package gazelle

import (
	"log"
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
	if ns, ok := l.ruleNs[ruleNsKey{pkg: f.Pkg, name: r.Name()}]; ok {
		return []resolve.ImportSpec{
			{Lang: languageName, Imp: ns},
		}
	}
	// Fallback for pre-existing rules (not freshly generated this run):
	// index every AOT'd namespace. Rules with no AOT attr stay un-indexed.
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
		// Defensive guard for callers that skip the normal lifecycle (tests).
		return
	}

	configs, ok := c.Exts[languageName].(*clojureconfig.Configs)
	if !ok {
		log.Fatalf("clojure: Resolve invoked with no %q Ext on config; bootParserSession should have populated it", languageName)
	}
	depsRepoTag := configs.DepsRepo(from.Pkg)

	depSet := make(map[string]struct{})
	for _, dep := range r.AttrStrings("deps") {
		depSet[dep] = struct{}{}
	}

	// Per-require resolution: intra-repo index first (platform-independent),
	// then per-platform DepNsLabels for external requires. depSet dedupes.
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
			var platformMap map[string]string
			switch platform {
			case clojureparser.PlatformClj:
				platformMap = session.depsIndex.DepNsLabels.Clj
			case clojureparser.PlatformCljs:
				platformMap = session.depsIndex.DepNsLabels.Cljs
			}
			if lbl, ok := platformMap[reqName]; ok {
				depSet[depsRepoTag+"//:"+lbl] = struct{}{}
			}
		}
	}

	// Per-target deps_bazel override (keyed on the final Bazel label, which
	// the bb side doesn't know).
	mergeDepsBazelTargetDeps(depSet, session.depsIndex.DepsBazel, from)

	// Exclude self-deps (.cljc files :require-macros'ing themselves).
	selfLabel := label.New("", from.Pkg, from.Name).String()
	delete(depSet, selfLabel)

	r.SetAttr("deps", slices.Sorted(maps.Keys(depSet)))
}

// mergeDepsBazelTargetDeps adds per-target extras from deps_bazel.Deps.
// Looks up both `from.String()` (may carry a bzlmod `@@//pkg:name` prefix)
// and the bare `//pkg:name` form.
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
