package gazelle

import (
	"sort"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/label"
	"github.com/bazelbuild/bazel-gazelle/repo"
	"github.com/bazelbuild/bazel-gazelle/resolve"
	"github.com/bazelbuild/bazel-gazelle/rule"

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
	// Fallback: try AOT attribute.
	aot := r.AttrStrings("aot")
	if len(aot) > 0 {
		return []resolve.ImportSpec{
			{Lang: languageName, Imp: aot[0]},
		}
	}
	return nil
}

func (*clojureLang) Embeds(r *rule.Rule, from label.Label) []label.Label {
	return nil
}

func (l *clojureLang) Resolve(c *config.Config, ix *resolve.RuleIndex, rc *repo.RemoteCache, r *rule.Rule, imports interface{}, from label.Label) {
	if r.Kind() != "clojure_library" {
		return
	}
	ns, ok := imports.(*clojureparser.NamespaceInfo)
	if !ok || ns == nil {
		return
	}

	depSet := make(map[string]bool)

	// 1. Implicit dep: Clojure itself.
	depSet[l.depsRepoTag+"//:org_clojure_clojure"] = true

	// 2. Resolve required namespaces per platform.
	// Note: gen_srcs cross-products files × platforms, producing redundant
	// deps (both AOT and plain library for the same dep). We intentionally
	// don't replicate this — the AOT target already depends on the plain
	// library, so the extra dep is unnecessary.
	for _, platform := range ns.Platforms {
		reqNs, ok := ns.Requires[platform]
		if !ok {
			continue
		}
		for _, reqName := range reqNs {
			// Try intra-repo index first (platform-independent).
			spec := resolve.ImportSpec{Lang: languageName, Imp: reqName}
			if matches := ix.FindRulesByImport(spec, languageName); len(matches) > 0 {
				lbl := matches[0].Label
				depSet[label.New("", lbl.Pkg, lbl.Name).String()] = true
				continue
			}
			// Try external dep_ns_labels for this platform.
			if l.initResp != nil {
				if platformMap, ok := l.initResp.DepNsLabels[platform]; ok {
					if lbl, ok := platformMap[reqName]; ok {
						depSet[l.depsRepoTag+"//:"+lbl] = true
					}
				}
			}
		}
	}

	// 3. Import deps and gen-class deps (pre-resolved labels).
	for _, dep := range ns.ImportDeps {
		depSet[dep] = true
	}
	for _, dep := range ns.GenClassDeps {
		depSet[dep] = true
	}

	// 4. Extra deps from global deps_bazel config.
	if l.initResp != nil {
		mergeDepsBazelGlobalDeps(depSet, l.initResp.DepsBazel, "clojure_library")
		mergeDepsBazelTargetDeps(depSet, l.initResp.DepsBazel, from)
	}

	// 5. Extra deps from ns_meta.
	mergeNsMetaDeps(depSet, ns)

	// Build sorted, deduplicated deps list.
	deps := make([]string, 0, len(depSet))
	for dep := range depSet {
		deps = append(deps, dep)
	}
	sort.Strings(deps)

	r.SetAttr("deps", deps)
}

// mergeDepsBazelGlobalDeps adds default deps from deps_bazel[ruleKind]["deps"].
// e.g. deps_bazel["clojure_library"]["deps"] → list of deps for all clojure_library rules.
func mergeDepsBazelGlobalDeps(depSet map[string]bool, depsBazel map[string]interface{}, ruleKind string) {
	kindRaw, ok := depsBazel[ruleKind]
	if !ok {
		return
	}
	kindMap, ok := kindRaw.(map[string]interface{})
	if !ok {
		return
	}
	mergeStringList(depSet, kindMap["deps"])
}

// mergeDepsBazelTargetDeps adds per-target extra deps from deps_bazel["deps"][label]["deps"].
func mergeDepsBazelTargetDeps(depSet map[string]bool, depsBazel map[string]interface{}, from label.Label) {
	depsRaw, ok := depsBazel["deps"]
	if !ok {
		return
	}
	depsMap, ok := depsRaw.(map[string]interface{})
	if !ok {
		return
	}
	targetRaw, ok := depsMap[from.String()]
	if !ok {
		return
	}
	targetMap, ok := targetRaw.(map[string]interface{})
	if !ok {
		return
	}
	mergeStringList(depSet, targetMap["deps"])
}

// mergeStringList adds all strings from a JSON-decoded []interface{} to depSet.
func mergeStringList(depSet map[string]bool, raw interface{}) {
	list, ok := raw.([]interface{})
	if !ok {
		return
	}
	for _, item := range list {
		if s, ok := item.(string); ok {
			depSet[s] = true
		}
	}
}

// mergeNsMetaDeps adds extra deps from per-namespace metadata.
// ns_meta["bazel/clojure_library"]["deps"] → list of extra dep strings.
func mergeNsMetaDeps(depSet map[string]bool, ns *clojureparser.NamespaceInfo) {
	if m := clojureLibraryMeta(ns); m != nil {
		mergeStringList(depSet, m["deps"])
	}
}
