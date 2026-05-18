// Package gazelle is the Clojure language plugin. Rule construction lives
// in the bb parser subprocess (src/rules_clojure/gazelle_server.bb); this
// package translates the resulting {kind, attrs} specs into *rule.Rule
// and runs cross-package dep resolution.
package gazelle

import (
	"context"
	"flag"
	"log"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/label"
	"github.com/bazelbuild/bazel-gazelle/language"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

const languageName = "clojure"

// parserSession pairs the subprocess runner with its resolved init response.
type parserSession struct {
	runner    *clojureparser.Runner
	depsIndex *clojureparser.InitResponse
}

// ruleNsKey identifies a generated rule by its (package, name) pair.
type ruleNsKey struct{ pkg, name string }

type clojureLang struct {
	session *parserSession
	// ruleNs maps a generated rule to its namespace symbol (Imports
	// consults this for the cross-package index).
	ruleNs map[ruleNsKey]string
	// hasClojureContent[rel] is true when rel has any Clojure rules or
	// transitively contains a subdir that does.
	hasClojureContent map[string]bool
}

// Compile-time interface checks.
var _ language.Language = (*clojureLang)(nil)
var _ language.ModuleAwareLanguage = (*clojureLang)(nil)
var _ language.LifecycleManager = (*clojureLang)(nil)

func NewLanguage() language.Language {
	return &clojureLang{
		ruleNs:            make(map[ruleNsKey]string),
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
			MergeableAttrs: map[string]bool{
				"env":       true,
				"tags":      true,
				"jvm_flags": true,
				"size":      true,
				"timeout":   true,
			},
			// No ResolveAttrs: Resolve short-circuits on non-clojure_library
			// kinds, so declaring "deps" here would let Gazelle clear it.
		},
		"clojure_binary": {
			NonEmptyAttrs: map[string]bool{"main_class": true},
			MergeableAttrs: map[string]bool{
				"runtime_deps": true,
				"args":         true,
				"jvm_flags":    true,
			},
			// No ResolveAttrs: ns-rules' :runtime_deps already includes the
			// library target.
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

// Loads implements the deprecated Language.Loads via ApparentLoads with an
// identity callback.
func (l *clojureLang) Loads() []rule.LoadInfo {
	return l.ApparentLoads(func(s string) string { return s })
}

// ApparentLoads returns load specs using the apparent rules_clojure module
// name (so a user who imports as `@my_rules_clojure` still gets a working
// load()). Fatals when rules_clojure isn't a bzlmod dep.
func (*clojureLang) ApparentLoads(moduleToApparentName func(string) string) []rule.LoadInfo {
	apparent := moduleToApparentName("rules_clojure")
	if apparent == "" {
		log.Fatalf("clojure: rules_clojure not declared in MODULE.bazel; " +
			"add `bazel_dep(name = \"rules_clojure\", version = \"...\")` before running gazelle.")
	}
	return []rule.LoadInfo{
		{
			Name:    "@" + apparent + "//:rules.bzl",
			Symbols: []string{"clojure_library", "clojure_test", "clojure_binary"},
		},
	}
}

// Interface no-ops, grouped to keep them out of configure.go / generate.go /
// resolve.go where they have no relation to the surrounding code.
func (*clojureLang) RegisterFlags(_ *flag.FlagSet, _ string, _ *config.Config) {}
func (*clojureLang) CheckFlags(_ *flag.FlagSet, _ *config.Config) error          { return nil }
func (*clojureLang) Embeds(_ *rule.Rule, _ label.Label) []label.Label             { return nil }
func (*clojureLang) Fix(_ *config.Config, _ *rule.File)                           {}
func (*clojureLang) Before(ctx context.Context)                                   {}

func (lang *clojureLang) DoneGeneratingRules() {
	// Drop ruleNs but keep a non-nil empty map in case Gazelle invokes
	// GenerateRules after DoneGeneratingRules.
	lang.ruleNs = make(map[ruleNsKey]string)
}

func (lang *clojureLang) AfterResolvingDeps(ctx context.Context) {
	if lang.session == nil {
		return
	}
	if err := lang.session.runner.Shutdown(); err != nil {
		// Non-zero subprocess exit at shutdown means a mid-run fatal the
		// wire envelope never surfaced. Fail loudly so Gazelle exits non-zero.
		log.Fatalf("clojure: parser shutdown: %v", err)
	}
	lang.session = nil
	log.Println("clojure: parser shut down")
}
