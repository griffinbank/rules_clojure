package gazelle

import (
	"reflect"
	"sort"
	"testing"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/label"
	"github.com/bazelbuild/bazel-gazelle/resolve"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

func TestImportsNonClojureLibrary(t *testing.T) {
	l := &clojureLang{ruleNs: map[string]string{}}
	r := rule.NewRule("clojure_test", "core.test")
	got := l.Imports(nil, r, &rule.File{Pkg: "src/foo"})
	if got != nil {
		t.Errorf("Imports for clojure_test = %v; want nil", got)
	}
}

func TestImportsRuleNsHit(t *testing.T) {
	l := &clojureLang{ruleNs: map[string]string{"src/foo:core": "foo.core"}}
	r := rule.NewRule("clojure_library", "core")
	got := l.Imports(nil, r, &rule.File{Pkg: "src/foo"})
	if len(got) != 1 || got[0].Imp != "foo.core" {
		t.Errorf("Imports = %v; want one spec with Imp=foo.core", got)
	}
	// Lang must match languageName so Gazelle's cross-language resolver
	// routes the spec back to our Resolve.
	if got[0].Lang != languageName {
		t.Errorf("Imports[0].Lang = %q; want %q", got[0].Lang, languageName)
	}
}

func TestImportsAOTFallbackMultipleNs(t *testing.T) {
	l := &clojureLang{ruleNs: map[string]string{}}
	r := rule.NewRule("clojure_library", "lib")
	r.SetAttr("aot", []string{"foo.a", "foo.b", "foo.c"})
	got := l.Imports(nil, r, &rule.File{Pkg: "src/foo"})
	if len(got) != 3 {
		t.Fatalf("Imports = %v; want 3 specs (one per AOT ns)", got)
	}
	want := []string{"foo.a", "foo.b", "foo.c"}
	imps := []string{got[0].Imp, got[1].Imp, got[2].Imp}
	sort.Strings(imps)
	if !reflect.DeepEqual(imps, want) {
		t.Errorf("Imports AOT imps = %v; want %v", imps, want)
	}
}

func TestImportsAOTAbsentReturnsNil(t *testing.T) {
	l := &clojureLang{ruleNs: map[string]string{}}
	r := rule.NewRule("clojure_library", "lib")
	got := l.Imports(nil, r, &rule.File{Pkg: "src/foo"})
	if got != nil {
		t.Errorf("Imports with no ruleNs / no aot = %v; want nil", got)
	}
}

func TestMergeDepsBazelTargetDepsAbsent(t *testing.T) {
	depSet := map[string]struct{}{}
	mergeDepsBazelTargetDeps(depSet, clojureparser.DepsBazel{}, label.New("", "src/foo", "core"))
	if len(depSet) != 0 {
		t.Errorf("depSet = %v; want empty (no deps_bazel.Deps entries)", depSet)
	}
}

func TestMergeDepsBazelTargetDepsHappyPath(t *testing.T) {
	depSet := map[string]struct{}{"@deps//:existing": {}}
	from := label.New("", "src/foo", "core")
	depsBazel := clojureparser.DepsBazel{
		Deps: map[string]clojureparser.DepsBazelTarget{
			from.String(): {Deps: []string{"@deps//:extra1", "@deps//:extra2"}},
		},
	}
	mergeDepsBazelTargetDeps(depSet, depsBazel, from)
	keys := make([]string, 0, len(depSet))
	for k := range depSet {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	want := []string{"@deps//:existing", "@deps//:extra1", "@deps//:extra2"}
	if !reflect.DeepEqual(keys, want) {
		t.Errorf("depSet keys = %v; want %v", keys, want)
	}
}

func TestMergeDepsBazelTargetDepsUnmatchedLabel(t *testing.T) {
	depSet := map[string]struct{}{}
	depsBazel := clojureparser.DepsBazel{
		Deps: map[string]clojureparser.DepsBazelTarget{
			"//src/bar:other": {Deps: []string{"@deps//:nope"}},
		},
	}
	mergeDepsBazelTargetDeps(depSet, depsBazel, label.New("", "src/foo", "core"))
	if len(depSet) != 0 {
		t.Errorf("depSet = %v; want empty (label didn't match)", depSet)
	}
}

// resolveFixture builds a clojureLang with a session containing the
// supplied DepNsLabels + DepsBazel, plus a minimal config that maps
// the language to its name. Used by all Resolve tests to avoid
// hand-rolling the seven-field setup each time.
func resolveFixture(depNsLabels map[string]map[string]string, depsBazel clojureparser.DepsBazel) (*clojureLang, *config.Config) {
	l := &clojureLang{
		ruleNs:            map[string]string{},
		hasClojureContent: map[string]bool{},
		session: &parserSession{
			depsRepoTag: "@deps",
			initResp: &clojureparser.InitResponse{
				DepNsLabels: depNsLabels,
				DepsBazel:   depsBazel,
			},
		},
	}
	c := &config.Config{
		Exts: map[string]interface{}{
			languageName: clojureconfig.New(),
		},
	}
	return l, c
}

func TestResolveSkipsNonClojureLibrary(t *testing.T) {
	l, c := resolveFixture(nil, clojureparser.DepsBazel{})
	r := rule.NewRule("clojure_test", "core.test")
	r.SetAttr("deps", []string{"@deps//:should_stay"})
	ix := resolve.NewRuleIndex(nil)
	l.Resolve(c, ix, nil, r, &clojureparser.NamespaceInfo{}, label.New("", "src/foo", "core.test"))
	if got := r.AttrStrings("deps"); !reflect.DeepEqual(got, []string{"@deps//:should_stay"}) {
		t.Errorf("clojure_test deps got %v; want untouched [@deps//:should_stay]", got)
	}
}

func TestResolveSkipsNilSession(t *testing.T) {
	l := &clojureLang{ruleNs: map[string]string{}, hasClojureContent: map[string]bool{}}
	c := &config.Config{Exts: map[string]interface{}{languageName: clojureconfig.New()}}
	r := rule.NewRule("clojure_library", "core")
	r.SetAttr("deps", []string{"@deps//:keep"})
	ix := resolve.NewRuleIndex(nil)
	// Should not panic; should leave deps unchanged.
	l.Resolve(c, ix, nil, r, &clojureparser.NamespaceInfo{}, label.New("", "src/foo", "core"))
	if got := r.AttrStrings("deps"); !reflect.DeepEqual(got, []string{"@deps//:keep"}) {
		t.Errorf("nil-session Resolve mutated deps: got %v; want [@deps//:keep]", got)
	}
}

func TestResolveResolvesExternalRequirePerPlatform(t *testing.T) {
	l, c := resolveFixture(
		map[string]map[string]string{
			"clj":  {"clojure.string": "ns_clj_string"},
			"cljs": {"cljs.spec.alpha": "org_clojure_clojurescript"},
		},
		clojureparser.DepsBazel{},
	)
	r := rule.NewRule("clojure_library", "shared")
	r.SetAttr("deps", []string{}) // start empty so the test asserts what Resolve added
	ix := resolve.NewRuleIndex(nil)
	ns := &clojureparser.NamespaceInfo{
		Platforms: []string{"clj", "cljs"},
		Requires: map[string][]string{
			"clj":  {"clojure.string"},
			"cljs": {"cljs.spec.alpha"},
		},
	}
	l.Resolve(c, ix, nil, r, ns, label.New("", "src", "shared"))
	got := r.AttrStrings("deps")
	want := []string{"@deps//:ns_clj_string", "@deps//:org_clojure_clojurescript"}
	sort.Strings(got)
	if !reflect.DeepEqual(got, want) {
		t.Errorf("deps = %v; want %v (per-platform external resolution)", got, want)
	}
}

func TestResolveRemovesSelfDep(t *testing.T) {
	l, c := resolveFixture(nil, clojureparser.DepsBazel{})
	r := rule.NewRule("clojure_library", "core")
	from := label.New("", "src/foo", "core")
	selfLabel := label.New("", from.Pkg, from.Name).String()
	r.SetAttr("deps", []string{selfLabel, "@deps//:org_clojure_clojure"})
	ix := resolve.NewRuleIndex(nil)
	ns := &clojureparser.NamespaceInfo{Platforms: []string{"clj"}, Requires: map[string][]string{"clj": {}}}
	l.Resolve(c, ix, nil, r, ns, from)
	got := r.AttrStrings("deps")
	for _, dep := range got {
		if dep == selfLabel {
			t.Errorf("self-dep %q not removed from deps %v", selfLabel, got)
		}
	}
}

func TestResolveHonoursPerPackageDepsRepoOverride(t *testing.T) {
	l, c := resolveFixture(
		map[string]map[string]string{"clj": {"some.dep": "some_dep_label"}},
		clojureparser.DepsBazel{},
	)
	// Set a per-package directive override.
	c.Exts[languageName].(*clojureconfig.Configs).Set("src/foo", clojureconfig.ClojureDepsRepo, "@other_deps")
	r := rule.NewRule("clojure_library", "core")
	r.SetAttr("deps", []string{})
	ix := resolve.NewRuleIndex(nil)
	ns := &clojureparser.NamespaceInfo{
		Platforms: []string{"clj"},
		Requires:  map[string][]string{"clj": {"some.dep"}},
	}
	l.Resolve(c, ix, nil, r, ns, label.New("", "src/foo", "core"))
	got := r.AttrStrings("deps")
	if !reflect.DeepEqual(got, []string{"@other_deps//:some_dep_label"}) {
		t.Errorf("per-package override ignored: deps = %v; want [@other_deps//:some_dep_label]", got)
	}
}

func TestResolveMissingPlatformInDepNsLabels(t *testing.T) {
	// DepNsLabels only has :clj; ns requires :cljs branch.
	l, c := resolveFixture(
		map[string]map[string]string{"clj": {"only.clj": "x"}},
		clojureparser.DepsBazel{},
	)
	r := rule.NewRule("clojure_library", "core")
	r.SetAttr("deps", []string{})
	ix := resolve.NewRuleIndex(nil)
	ns := &clojureparser.NamespaceInfo{
		Platforms: []string{"cljs"},
		Requires:  map[string][]string{"cljs": {"only.clj"}},
	}
	// Should not panic; cljs require simply doesn't resolve.
	l.Resolve(c, ix, nil, r, ns, label.New("", "src", "core"))
	if got := r.AttrStrings("deps"); len(got) != 0 {
		t.Errorf("missing-platform require produced deps %v; want empty", got)
	}
}
