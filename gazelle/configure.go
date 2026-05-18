// configure.go implements Gazelle's Configurer interface for the
// clojure language. Collects BUILD-file directives into clojureconfig.Configs
// and boots the parser subprocess on the first Configure call.
package gazelle

import (
	"fmt"
	"log"
	"os"
	"path/filepath"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/rule"
	"github.com/bazelbuild/rules_go/go/runfiles"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

// firstRunfile returns the first existing runfile path resolved from
// `candidates`, or "" when none resolve.
func firstRunfile(candidates []string) string {
	for _, candidate := range candidates {
		if p, err := runfiles.Rlocation(candidate); err == nil {
			if _, statErr := os.Stat(p); statErr == nil {
				return p
			}
		}
	}
	return ""
}

// runfilesEnvDiag returns " (RUNFILES_DIR=...; RUNFILES_MANIFEST_FILE=...)"
// for inclusion in error messages when a runfile lookup fails.
func runfilesEnvDiag() string {
	return fmt.Sprintf(" (RUNFILES_DIR=%q, RUNFILES_MANIFEST_FILE=%q)",
		os.Getenv("RUNFILES_DIR"), os.Getenv("RUNFILES_MANIFEST_FILE"))
}

// parserScriptRunfileCandidates covers bazel <=7 (`+`), >=8 (`~`),
// and the `_main` case where rules_clojure is the root module.
func parserScriptRunfileCandidates() []string {
	return []string{
		"rules_clojure+/src/rules_clojure/gazelle_server.bb",
		"rules_clojure~/src/rules_clojure/gazelle_server.bb",
		"_main/src/rules_clojure/gazelle_server.bb",
	}
}

// bbBinaryRunfileCandidates: same separator concern as parserScriptRunfileCandidates.
func bbBinaryRunfileCandidates() []string {
	return []string{
		"rules_multitool++multitool+multitool/tools/bb/bb",
		"rules_multitool~~multitool~multitool/tools/bb/bb",
		"multitool/tools/bb/bb",
	}
}

// resolveParserScript returns the gazelle_server.bb path: $GAZELLE_CLOJURE_PARSER
// if set, else a runfiles probe, else a local-dev path under repoRoot.
func resolveParserScript(repoRoot string) string {
	if env := os.Getenv("GAZELLE_CLOJURE_PARSER"); env != "" {
		return env
	}
	if p := firstRunfile(parserScriptRunfileCandidates()); p != "" {
		return p
	}
	localPath := filepath.Join(repoRoot, "src/rules_clojure/gazelle_server.bb")
	if _, err := os.Stat(localPath); err == nil {
		return localPath
	}
	log.Fatalf("clojure: cannot find gazelle_server.bb under runfiles%s or %s; "+
		"set GAZELLE_CLOJURE_PARSER to the absolute path.",
		runfilesEnvDiag(), repoRoot)
	return ""
}

// resolveBbBinary returns the @multitool//tools/bb runfile path.
func resolveBbBinary() string {
	candidates := bbBinaryRunfileCandidates()
	if p := firstRunfile(candidates); p != "" {
		return p
	}
	log.Fatalf("clojure: bb not found in runfiles%s; tried %v",
		runfilesEnvDiag(), candidates)
	return ""
}

func (*clojureLang) KnownDirectives() []string {
	return clojureconfig.AllDirectives()
}

func (l *clojureLang) Configure(c *config.Config, rel string, f *rule.File) {
	var configs *clojureconfig.Configs
	switch raw := c.Exts[languageName].(type) {
	case nil:
		configs = clojureconfig.New()
		c.Exts[languageName] = configs
		// First call: auto-discover the root deps.edn (Gazelle's walk visits
		// the root before any subpackage).
		depsPath := filepath.Join(c.RepoRoot, "deps.edn")
		if _, err := os.Stat(depsPath); err == nil {
			configs.Set("", clojureconfig.ClojureDepsEdn, depsPath)
		}
	case *clojureconfig.Configs:
		configs = raw
	default:
		log.Fatalf("clojure: c.Exts[%q] is %T; expected *clojureconfig.Configs (plugin conflict?)", languageName, raw)
	}

	// Record raw directive values for this package. clojure_deps_edn is
	// stored absolute so callers don't need to know about RepoRoot.
	if f != nil {
		for _, d := range f.Directives {
			switch d.Key {
			case clojureconfig.ClojureDepsEdn:
				configs.Set(rel, d.Key, filepath.Join(c.RepoRoot, d.Value))
			case clojureconfig.ClojureEnabledDirective,
				clojureconfig.ClojureDepsRepo,
				clojureconfig.ClojureAliases:
				configs.Set(rel, d.Key, d.Value)
			}
		}
	}

	// Boot the parser on the root Configure call.
	if rel == "" && l.session == nil && configs.DepsEdn() != "" {
		l.bootParserSession(c.RepoRoot, configs)
	}
}

// bootParserSession starts the bb subprocess, sends the init request,
// and stores the resolved session on the lang.
func (l *clojureLang) bootParserSession(repoRoot string, configs *clojureconfig.Configs) {
	scriptPath := resolveParserScript(repoRoot)
	bbPath := resolveBbBinary()

	// Failures here must be fatal: a silent continue produces an empty
	// GenerateResult per directory, which Gazelle interprets as "delete all
	// existing rules".
	runner, err := clojureparser.New(bbPath, scriptPath)
	if err != nil {
		log.Fatalf("clojure: failed to start parser %s %s: %v",
			bbPath, scriptPath, err)
	}

	aliases := configs.Aliases()

	resp, err := runner.Init(clojureparser.InitRequest{
		DepsEdnPath: configs.DepsEdn(),
		DepsRepoTag: configs.DepsRepo(""),
		Aliases:     aliases,
	})
	if err != nil {
		if shutdownErr := runner.Shutdown(); shutdownErr != nil {
			log.Printf("clojure: parser shutdown returned: %v", shutdownErr)
		}
		log.Fatalf("clojure: parser init failed: %v\n"+
			"hint: check deps.edn at %s parses and all aliases (%v) exist.",
			err, configs.DepsEdn(), aliases)
	}

	l.session = &parserSession{
		runner:    runner,
		depsIndex: resp,
	}
	log.Printf("clojure: parser started (source_paths=%v, ignore_paths=%v, dep_ns_labels clj=%d cljs=%d)",
		resp.SourcePaths, resp.IgnorePaths, len(resp.DepNsLabels.Clj), len(resp.DepNsLabels.Cljs))
}
