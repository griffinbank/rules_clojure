package gazelle

import (
	"context"
	"log"

	"github.com/bazelbuild/bazel-gazelle/language"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

const languageName = "clojure"

type clojureLang struct {
	parser      *clojureparser.Runner
	initResp    *clojureparser.InitResponse
	depsRepoTag string
	// ruleNs maps "pkg:ruleName" to namespace name for cross-reference indexing.
	// Populated during GenerateRules, read during Imports.
	ruleNs map[string]string
}

// Compile-time interface checks.
var _ language.Language = (*clojureLang)(nil)
var _ language.ModuleAwareLanguage = (*clojureLang)(nil)
var _ language.LifecycleManager = (*clojureLang)(nil)

func NewLanguage() language.Language {
	return &clojureLang{
		ruleNs: make(map[string]string),
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
			ResolveAttrs:  map[string]bool{"deps": true},
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

// Loads implements the deprecated Language.Loads; we use ApparentLoads instead.
func (*clojureLang) Loads() []rule.LoadInfo {
	return nil
}

// ApparentLoads implements ModuleAwareLanguage.
func (*clojureLang) ApparentLoads(moduleToApparentName func(string) string) []rule.LoadInfo {
	return []rule.LoadInfo{
		{
			Name:    "@rules_clojure//:rules.bzl",
			Symbols: []string{"clojure_library", "clojure_test"},
		},
	}
}

// LifecycleManager methods.

func (*clojureLang) Before(ctx context.Context) {}

func (lang *clojureLang) DoneGeneratingRules() {
	// ruleNs is only needed during Imports() which runs before Resolve().
	// Clear it to free memory for large repos.
	lang.ruleNs = nil
}

func (lang *clojureLang) AfterResolvingDeps(ctx context.Context) {
	if lang.parser != nil {
		lang.parser.Shutdown()
		lang.parser = nil
		log.Println("clojure: parser shut down")
	}
}
