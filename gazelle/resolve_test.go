package gazelle

import (
	"reflect"
	"sort"
	"testing"

	"github.com/bazelbuild/bazel-gazelle/label"
	"github.com/bazelbuild/bazel-gazelle/rule"

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
