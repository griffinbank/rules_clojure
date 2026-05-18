package clojureconfig

import (
	"reflect"
	"testing"
)

func TestParseAliases(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want []string
	}{
		{name: "empty", in: "", want: []string{}},
		{name: "single", in: "dev", want: []string{"dev"}},
		{name: "two", in: "dev,test", want: []string{"dev", "test"}},
		{name: "trims whitespace", in: " dev , test ", want: []string{"dev", "test"}},
		{name: "drops empty entries", in: ",,dev,,test,", want: []string{"dev", "test"}},
		{name: "all empty", in: ",,,", want: []string{}},
		{name: "colon-prefixed kept verbatim", in: ":dev", want: []string{":dev"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := ParseAliases(tc.in)
			if !reflect.DeepEqual(got, tc.want) {
				t.Errorf("ParseAliases(%q) = %v; want %v", tc.in, got, tc.want)
			}
		})
	}
}

func TestEffectiveWalksParentChain(t *testing.T) {
	cs := New()
	cs.Set("", ClojureDepsRepo, "@root")
	cs.Set("a", ClojureDepsRepo, "@a")
	cs.Set("a/b/c", ClojureDepsRepo, "@deep")
	cases := map[string]string{
		"":        "@root",
		"a":       "@a",
		"a/b":     "@a",    // a/b unset → a
		"a/b/c":   "@deep", // exact match
		"a/b/c/d": "@deep", // child of c → deep
		"x/y/z":   "@root", // unrelated → root default
	}
	for rel, want := range cases {
		t.Run(rel, func(t *testing.T) {
			got := cs.DepsRepo(rel)
			if got != want {
				t.Errorf("DepsRepo(%q) = %q; want %q", rel, got, want)
			}
		})
	}
}

func TestEffectiveDefaultsWhenNothingSet(t *testing.T) {
	cs := New()
	if got := cs.DepsRepo("anything"); got != "@deps" {
		t.Errorf("DepsRepo on empty Configs = %q; want @deps default", got)
	}
}

func TestExtensionEnabledFalseSticks(t *testing.T) {
	cs := New()
	if !cs.ExtensionEnabled("") {
		t.Error("default ExtensionEnabled = false; want true")
	}
	cs.Set("a", ClojureEnabledDirective, "false")
	if cs.ExtensionEnabled("a") {
		t.Error("ExtensionEnabled(a) after false override = true")
	}
	// Child inherits parent's false.
	if cs.ExtensionEnabled("a/b") {
		t.Error("child ExtensionEnabled = true; want inherited false")
	}
	// Sibling unaffected.
	if !cs.ExtensionEnabled("other") {
		t.Error("sibling ExtensionEnabled = false; want default true")
	}
}

func TestAliasesParsesRootDirective(t *testing.T) {
	cs := New()
	cs.Set("", ClojureAliases, "dev,test")
	got := cs.Aliases()
	if !reflect.DeepEqual(got, []string{"dev", "test"}) {
		t.Errorf("Aliases() = %v; want [dev test]", got)
	}
}

