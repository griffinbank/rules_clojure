// Package gazelle is the Clojure language plugin for Gazelle. It is glue
// between Gazelle's language.Language interface and a long-running Clojure
// parser subprocess (gazelle/clojureparser). The parser owns rule
// construction via rules-clojure.gen-build; this package translates the
// resulting {kind, attrs} specs into *rule.Rule and runs cross-package
// dep resolution against Gazelle's index.
package gazelle

import (
	"context"
	"log"

	"github.com/bazelbuild/bazel-gazelle/language"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

const languageName = "clojure"

// parserSession bundles the three fields that share a lifetime: they're set
// together in startParser and cleared together in AfterResolvingDeps.
// Grouping them lets callers check `l.session == nil` instead of any one
// field individually, avoiding a fragile "parser != nil implies initResp
// != nil" invariant.
type parserSession struct {
	runner      *clojureparser.Runner
	initResp    *clojureparser.InitResponse
	depsRepoTag string
}

type clojureLang struct {
	session *parserSession
	// ruleNs maps "pkg:ruleName" to namespace name for cross-reference
	// indexing. Populated during GenerateRules, read during Imports.
	ruleNs map[string]string
	// hasClojureContent records whether `rel` has any Clojure rules or
	// transitively contains a subdir that does. Populated bottom-up by
	// GenerateRules so the parent's `__clj_lib` rollup check is an O(1)
	// map lookup instead of an O(n) WalkDir per subdir.
	hasClojureContent map[string]bool
}

// Compile-time interface checks.
var _ language.Language = (*clojureLang)(nil)
var _ language.ModuleAwareLanguage = (*clojureLang)(nil)
var _ language.LifecycleManager = (*clojureLang)(nil)

func NewLanguage() language.Language {
	return &clojureLang{
		ruleNs:            make(map[string]string),
		hasClojureContent: make(map[string]bool),
	}
}

func (*clojureLang) Name() string {
	return languageName
}

func (*clojureLang) Kinds() map[string]rule.KindInfo {
	return map[string]rule.KindInfo{
		"clojure_library": {
			NonEmptyAttrs:  map[string]bool{"resources": true},
			MergeableAttrs: map[string]bool{"resources": true, "srcs": true},
			ResolveAttrs:   map[string]bool{"deps": true},
		},
		"clojure_test": {
			NonEmptyAttrs: map[string]bool{"test_ns": true},
			// No ResolveAttrs — Resolve below short-circuits unless
			// Kind() == "clojure_library", so declaring "deps" as a
			// resolve-attr would let Gazelle clear it without ever giving
			// us a chance to rebuild it.
		},
		"clojure_binary": {
			NonEmptyAttrs: map[string]bool{"main_class": true},
			// No ResolveAttrs — binaries get their dep on the library
			// directly from gen-build/ns-rules' :runtime_deps.
		},
		"java_library": {
			NonEmptyAttrs:  map[string]bool{"resources": true},
			MergeableAttrs: map[string]bool{"resources": true},
		},
		"filegroup": {
			NonEmptyAttrs:  map[string]bool{"srcs": true},
			MergeableAttrs: map[string]bool{"srcs": true},
		},
	}
}

// Loads implements the deprecated Language.Loads. We use ApparentLoads
// instead but some Gazelle code paths still consult Loads; delegate so the
// two views agree. `clojure_library`, `clojure_test`, and `clojure_binary`
// need an explicit load — `java_library` and `filegroup` are Bazel built-ins.
func (l *clojureLang) Loads() []rule.LoadInfo {
	return l.ApparentLoads(func(s string) string { return s })
}

// ApparentLoads implements ModuleAwareLanguage. The
// `moduleToApparentName` callback maps a canonical module name to whatever
// apparent name the user gave it in MODULE.bazel; we use it so a user who
// imports rules_clojure as `@my_rules_clojure` still gets a working load().
// log.Fatalf when rules_clojure isn't a bzlmod dep — falling back to the
// canonical name produces a Bazel-load error far from the cause.
func (*clojureLang) ApparentLoads(moduleToApparentName func(string) string) []rule.LoadInfo {
	apparent := moduleToApparentName("rules_clojure")
	if apparent == "" {
		log.Fatalf("clojure: rules_clojure not declared in MODULE.bazel — " +
			"add `bazel_dep(name = \"rules_clojure\", version = \"...\")` before running gazelle.")
	}
	return []rule.LoadInfo{
		{
			Name:    "@" + apparent + "//:rules.bzl",
			Symbols: []string{"clojure_library", "clojure_test", "clojure_binary"},
		},
	}
}

// LifecycleManager methods.

func (*clojureLang) Before(ctx context.Context) {}

func (lang *clojureLang) DoneGeneratingRules() {
	// ruleNs is populated during GenerateRules and read by Imports() for
	// newly generated rules; rules already in existing BUILD files take the
	// AOT-attr fallback in resolve.go. Release the map but keep a usable
	// empty value so an unexpected post-Done GenerateRules call doesn't
	// panic on a nil map.
	lang.ruleNs = make(map[string]string)
}

func (lang *clojureLang) AfterResolvingDeps(ctx context.Context) {
	if lang.session == nil {
		// Startup failed earlier; log.Fatalf already aborted. Reaching here
		// in tests or library use without startParser is a no-op.
		return
	}
	lang.session.runner.Shutdown()
	lang.session = nil
	log.Println("clojure: parser shut down")
}
