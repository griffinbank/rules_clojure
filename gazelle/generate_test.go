package gazelle

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	build "github.com/bazelbuild/buildtools/build"

	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

func TestIsClojureExt(t *testing.T) {
	cases := map[string]bool{
		".clj":  true,
		".cljs": true,
		".cljc": true,
		".js":   true,
		".java": false,
		".txt":  false,
		"":      false,
	}
	for ext, want := range cases {
		if got := isClojureExt(ext); got != want {
			t.Errorf("isClojureExt(%q) = %v; want %v", ext, got, want)
		}
	}
}

func TestSourceRootMatchesPrefix(t *testing.T) {
	sourcePaths := []string{"src/main/clojure", "src/test/clojure"}
	cases := map[string]string{
		"src/main/clojure":         "src/main/clojure",
		"src/main/clojure/foo/bar": "src/main/clojure",
		"src/test/clojure/x":       "src/test/clojure",
		"src/main":                 "", // shorter than any prefix
		"unrelated":                "",
		"":                         "",
	}
	for rel, want := range cases {
		if got := sourceRoot(sourcePaths, rel); got != want {
			t.Errorf("sourceRoot(%q) = %q; want %q", rel, got, want)
		}
	}
}

func TestSubdirHasClojureFiles(t *testing.T) {
	dir := t.TempDir()

	// Empty dir → false.
	if subdirHasClojureFiles(dir) {
		t.Errorf("subdirHasClojureFiles(empty) = true; want false")
	}

	// Non-Clojure file → false.
	if err := os.WriteFile(filepath.Join(dir, "README.md"), []byte("x"), 0644); err != nil {
		t.Fatal(err)
	}
	if subdirHasClojureFiles(dir) {
		t.Errorf("subdirHasClojureFiles(only README) = true; want false")
	}

	// Nested .clj → true.
	subdir := filepath.Join(dir, "src", "foo")
	if err := os.MkdirAll(subdir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(subdir, "core.clj"), []byte("(ns foo.core)"), 0644); err != nil {
		t.Fatal(err)
	}
	if !subdirHasClojureFiles(dir) {
		t.Errorf("subdirHasClojureFiles(with nested .clj) = false; want true")
	}

	// .js does NOT count (rollup excludes JS-only subdirs).
	jsOnly := t.TempDir()
	if err := os.WriteFile(filepath.Join(jsOnly, "lib.js"), []byte("x"), 0644); err != nil {
		t.Fatal(err)
	}
	if subdirHasClojureFiles(jsOnly) {
		t.Errorf("subdirHasClojureFiles(JS only) = true; want false")
	}
}

func TestReadRootModuleName(t *testing.T) {
	cases := []struct {
		name    string
		content string
		want    string
	}{
		{"double-quoted", `module(name = "foo")`, "foo"},
		{"no-spaces", `module(name="bar")`, "bar"},
		{"single-quoted", `module(name = 'baz')`, "baz"},
		{"underscored", `module(name = "with_underscore_123")`, "with_underscore_123"},
		{"with-version-after", `module(name = "foo", version = "1.0")`, "foo"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			if err := os.WriteFile(filepath.Join(dir, "MODULE.bazel"), []byte(tc.content), 0644); err != nil {
				t.Fatal(err)
			}
			if got := readRootModuleName(dir); got != tc.want {
				t.Errorf("readRootModuleName(%q) = %q; want %q", tc.content, got, tc.want)
			}
		})
	}

	t.Run("missing MODULE.bazel", func(t *testing.T) {
		got := readRootModuleName(t.TempDir())
		if got != "" {
			t.Errorf("readRootModuleName(no MODULE.bazel) = %q; want \"\"", got)
		}
	})

	t.Run("no module() call", func(t *testing.T) {
		dir := t.TempDir()
		_ = os.WriteFile(filepath.Join(dir, "MODULE.bazel"), []byte("# no module"), 0644)
		got := readRootModuleName(dir)
		if got != "" {
			t.Errorf("readRootModuleName(no module call) = %q; want \"\"", got)
		}
	})
}

func TestBuildRuleCoercesAttrTypes(t *testing.T) {
	spec := clojureparser.RuleSpec{
		Kind: "clojure_library",
		Attrs: map[string]interface{}{
			"name":      "core",
			"resources": []interface{}{"core.clj", "core.cljs"},
			"aot":       []interface{}{"my.core"},
			// Numeric / boolean / string attrs all flow through.
			"shard_count": float64(4),
			"flaky":       true,
			"timeout":     "long",
		},
	}
	r, err := buildRule(spec)
	if err != nil {
		t.Fatalf("buildRule: %v", err)
	}
	if r.Kind() != "clojure_library" {
		t.Errorf("kind = %q; want clojure_library", r.Kind())
	}
	if r.Name() != "core" {
		t.Errorf("name = %q; want core", r.Name())
	}
	if got := r.AttrStrings("resources"); !reflect.DeepEqual(got, []string{"core.clj", "core.cljs"}) {
		t.Errorf("resources = %v; want [core.clj core.cljs]", got)
	}
	if got := r.AttrStrings("aot"); !reflect.DeepEqual(got, []string{"my.core"}) {
		t.Errorf("aot = %v; want [my.core]", got)
	}
	if got := r.AttrString("timeout"); got != "long" {
		t.Errorf("timeout = %q; want long", got)
	}
	// Numeric + bool attrs aren't readable via AttrStrings/AttrString; just
	// check they were set at all.
	for _, k := range []string{"shard_count", "flaky"} {
		if r.Attr(k) == nil {
			t.Errorf("attr %q not set", k)
		}
	}
}

func TestBuildRuleRejectsMissingName(t *testing.T) {
	_, err := buildRule(clojureparser.RuleSpec{
		Kind:  "clojure_library",
		Attrs: map[string]interface{}{"resources": []interface{}{"x.clj"}},
	})
	if err == nil {
		t.Error("buildRule with no :name accepted; want error")
	}
}

func TestBuildRuleAcceptsStringDictAttr(t *testing.T) {
	// clojure_test :env is a string_dict; gen-build emits it as a Clojure
	// map of {string string}. applyAttr must convert to map[string]string
	// so rule.SetAttr sees the typed shape Bazel emits as a dict literal.
	r, err := buildRule(clojureparser.RuleSpec{
		Kind: "clojure_test",
		Attrs: map[string]interface{}{
			"name":    "foo",
			"test_ns": "foo",
			"env":     map[string]interface{}{"K1": "v1", "K2": "v2"},
		},
	})
	if err != nil {
		t.Fatalf("buildRule with string_dict attr rejected: %v", err)
	}
	// rule.Rule has no typed string-dict reader; check that the attr was set
	// and serializes as a Starlark dict literal containing both keys.
	expr := r.Attr("env")
	if expr == nil {
		t.Fatal("env attr not set")
	}
	src := build.FormatString(expr)
	for _, want := range []string{`"K1": "v1"`, `"K2": "v2"`} {
		if !strings.Contains(src, want) {
			t.Errorf("env attr source = %q; want %s", src, want)
		}
	}
}

func TestBuildRuleRejectsNonStringDictValue(t *testing.T) {
	_, err := buildRule(clojureparser.RuleSpec{
		Kind: "clojure_test",
		Attrs: map[string]interface{}{
			"name":    "foo",
			"test_ns": "foo",
			"env":     map[string]interface{}{"K1": 42},
		},
	})
	if err == nil {
		t.Error("buildRule with non-string dict value accepted; want error")
	}
}

func TestBuildRuleRejectsUnknownKind(t *testing.T) {
	_, err := buildRule(clojureparser.RuleSpec{
		Kind:  "clojure_libary", // typo
		Attrs: map[string]interface{}{"name": "x"},
	})
	if err == nil {
		t.Error("buildRule with unknown kind accepted; want error")
	}
}

func TestBuildRuleRejectsNilAttr(t *testing.T) {
	_, err := buildRule(clojureparser.RuleSpec{
		Kind:  "clojure_library",
		Attrs: map[string]interface{}{"name": "x", "resources": nil},
	})
	if err == nil {
		t.Error("buildRule with nil attr accepted; want error")
	}
}
