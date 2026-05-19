// configure.go implements Gazelle's Configurer interface (RegisterFlags,
// CheckFlags, KnownDirectives, Configure). Gazelle calls Configure(c, rel, f)
// once per package as it walks the repo, passing each BUILD file's directives.
// This file collects those directives into clojureconfig.Configs (a per-rel
// map), auto-discovers the workspace-root deps.edn on the first call, and
// triggers startParser when the root package is configured. The parser
// subprocess is started with the root-level config values (deps.edn path,
// deps-repo tag, aliases) and lives for the duration of the Gazelle run.
package gazelle

import (
	"flag"
	"log"
	"os"
	"path/filepath"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/rule"
	"github.com/bazelbuild/rules_go/go/runfiles"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

// resolveParserScript locates gazelle_server.bb. Tried in order:
//   - $GAZELLE_CLOJURE_PARSER  — explicit override, absolute path.
//   - Bazel runfiles under either repo prefix:
//       rules_clojure+/src/rules_clojure/gazelle_server.bb  (downstream consumer)
//       _main/src/rules_clojure/gazelle_server.bb            (rules_clojure itself)
//   - <repoRoot>/src/rules_clojure/gazelle_server.bb — local-dev when
//     working inside the rules_clojure repo itself without bazel runfiles.
func resolveParserScript(repoRoot string) string {
	if env := os.Getenv("GAZELLE_CLOJURE_PARSER"); env != "" {
		return env
	}
	// Runfiles lookup honors RUNFILES_DIR / RUNFILES_MANIFEST_FILE that
	// Bazel sets up when invoking a binary. Works for both `bazel run`
	// and direct-binary invocation when runfiles are present alongside.
	for _, candidate := range []string{
		"rules_clojure+/src/rules_clojure/gazelle_server.bb",
		"_main/src/rules_clojure/gazelle_server.bb",
	} {
		if p, err := runfiles.Rlocation(candidate); err == nil {
			if _, statErr := os.Stat(p); statErr == nil {
				return p
			}
		}
	}
	// Local-dev fallback: working inside the rules_clojure repo.
	if p := filepath.Join(repoRoot, "src/rules_clojure/gazelle_server.bb"); fileExists(p) {
		return p
	}
	log.Fatalf("clojure: cannot find gazelle_server.bb under runfiles or %s; "+
		"set GAZELLE_CLOJURE_PARSER to the absolute path.", repoRoot)
	return ""
}

// resolveBbBinary locates the @multitool//tools/bb runfile shipped with
// gazelle_bin.
func resolveBbBinary() string {
	candidates := []string{
		// bzlmod canonical repo name.
		"rules_multitool++multitool+multitool/tools/bb/bb",
		// Apparent name, used by consumers that re-expose @multitool.
		"multitool/tools/bb/bb",
	}
	for _, candidate := range candidates {
		if p, err := runfiles.Rlocation(candidate); err == nil {
			if _, statErr := os.Stat(p); statErr == nil {
				return p
			}
		}
	}
	log.Fatalf("clojure: bb not found in runfiles; tried %v", candidates)
	return ""
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func (*clojureLang) RegisterFlags(_ *flag.FlagSet, _ string, _ *config.Config) {}

func (*clojureLang) CheckFlags(_ *flag.FlagSet, _ *config.Config) error {
	return nil
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
		// Auto-discover root deps.edn on first call. Configure visits the
		// root before any subpackage, so this fires exactly once.
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
	// stored absolute so startParser doesn't have to know about RepoRoot.
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

	// Start the parser on the first Configure (root package). Gazelle's
	// walk visits the root before any subpackage, so this fires before any
	// GenerateRules call.
	if rel == "" && l.session == nil && configs.DepsEdn() != "" {
		l.startParser(c.RepoRoot, configs)
	}
}

func (l *clojureLang) startParser(repoRoot string, configs *clojureconfig.Configs) {
	// The parser is a long-lived babashka subprocess; the Go plugin spawns
	// `bb <script>` and speaks newline-JSON over its stdio. Script path
	// lookup order:
	//   1. GAZELLE_CLOJURE_PARSER env var — explicit override.
	//   2. Bazel runfiles (when the gazelle binary is bazel-spawned).
	//   3. Built-in default at src/rules_clojure/gazelle_server.bb under
	//      repoRoot, for local-dev inside this repo.
	scriptPath := resolveParserScript(repoRoot)
	bbPath := resolveBbBinary()

	// Parser failures must fail loudly: a silent "log + continue" produces an
	// empty GenerateResult per directory, which Gazelle interprets as "delete
	// all existing rules". A green run that scorches the build graph is worse
	// than a noisy exit.
	runner, err := clojureparser.New(bbPath, scriptPath)
	if err != nil {
		log.Fatalf("clojure: failed to start parser %s %s: %v",
			bbPath, scriptPath, err)
	}

	rootDepsRepo := configs.DepsRepo("")
	aliases := configs.Aliases()

	resp, err := runner.Init(clojureparser.InitRequest{
		DepsEdnPath: configs.DepsEdn(),
		DepsRepoTag: rootDepsRepo,
		Aliases:     aliases,
	})
	if err != nil {
		_ = runner.Shutdown()
		log.Fatalf("clojure: parser init failed: %v\n"+
			"hint: check deps.edn at %s parses and all aliases (%v) exist.",
			err, configs.DepsEdn(), aliases)
	}

	l.session = &parserSession{
		runner:      runner,
		initResp:    resp,
		depsRepoTag: rootDepsRepo,
	}
	log.Printf("clojure: parser started (source_paths=%v, ignore_paths=%v, dep_ns_labels clj=%d cljs=%d)",
		resp.SourcePaths, resp.IgnorePaths, len(resp.DepNsLabels["clj"]), len(resp.DepNsLabels["cljs"]))
}
