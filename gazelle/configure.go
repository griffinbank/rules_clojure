// configure.go implements Gazelle's Configurer interface (RegisterFlags,
// CheckFlags, KnownDirectives, Configure). Gazelle calls Configure(c, rel, f)
// once per package as it walks the repo, passing each BUILD file's directives.
// This file collects those directives into clojureconfig.Configs (a per-rel
// map), auto-discovers the workspace-root deps.edn + bzlmod module name on
// the first call, and triggers startParser when the root package is
// configured. The parser subprocess is started with the root-level config
// values (deps.edn path, deps-repo tag, aliases, etc.) and lives for the
// duration of the Gazelle run.
package gazelle

import (
	"flag"
	"log"
	"os"
	"path/filepath"
	"regexp"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

// moduleNameRe matches `module(name = "...")` in MODULE.bazel — the canonical
// bzlmod declaration. Doesn't parse Starlark, so a commented-out `module(...)`
// line will match; in practice Buildifier formats MODULE.bazel and the
// canonical call sits at the top of file, so this is acceptable.
// The regex accepts mismatched single/double quotes (e.g. open " close ');
// Buildifier always emits double quotes so this never matters in practice.
var moduleNameRe = regexp.MustCompile(`module\(\s*name\s*=\s*["']([^"']+)["']`)

// readRootModuleName extracts the bzlmod root module name from MODULE.bazel
// at repoRoot. Returns "" only when MODULE.bazel is genuinely missing
// (legacy WORKSPACE projects) or has no module() call. Any other read error
// is fatal — silently treating an unreadable MODULE.bazel as "no module"
// would skip self-label canonicalization and produce subtly wrong labels.
func readRootModuleName(repoRoot string) string {
	path := filepath.Join(repoRoot, "MODULE.bazel")
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return ""
		}
		log.Fatalf("clojure: read %s: %v", path, err)
	}
	m := moduleNameRe.FindSubmatch(data)
	if m == nil {
		return ""
	}
	return string(m[1])
}

func (*clojureLang) RegisterFlags(_ *flag.FlagSet, _ string, _ *config.Config) {}

func (*clojureLang) CheckFlags(_ *flag.FlagSet, _ *config.Config) error {
	return nil
}

func (*clojureLang) KnownDirectives() []string {
	return clojureconfig.AllDirectives()
}

func (l *clojureLang) Configure(c *config.Config, rel string, f *rule.File) {
	var configs clojureconfig.Configs
	switch raw := c.Exts[languageName].(type) {
	case nil:
		configs = make(clojureconfig.Configs)
		c.Exts[languageName] = configs
		// Auto-discover root deps.edn + bzlmod module name on first call.
		// Configure visits the root before any subpackage, so this fires
		// exactly once.
		depsPath := filepath.Join(c.RepoRoot, "deps.edn")
		if _, err := os.Stat(depsPath); err == nil {
			configs.Set("", clojureconfig.ClojureDepsEdn, depsPath)
		}
		if name := readRootModuleName(c.RepoRoot); name != "" {
			configs.SetRootModuleName(name)
		}
	case clojureconfig.Configs:
		configs = raw
	default:
		log.Fatalf("clojure: c.Exts[%q] is %T; expected clojureconfig.Configs (plugin conflict?)", languageName, raw)
	}

	// Record raw directive values for this package. clojure_deps_edn is
	// stored absolute so startParser doesn't have to know about RepoRoot.
	if f != nil {
		for _, d := range f.Directives {
			switch d.Key {
			case clojureconfig.ClojureDepsEdn:
				configs.Set(rel, d.Key, filepath.Join(c.RepoRoot, d.Value))
			case clojureconfig.ClojureExtensionDirective,
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

func (l *clojureLang) startParser(repoRoot string, configs clojureconfig.Configs) {
	binaryPath := os.Getenv("GAZELLE_CLOJURE_PARSER")
	if binaryPath == "" {
		binaryPath = filepath.Join(repoRoot, "bazel-bin/src/rules_clojure/gazelle_server")
	}

	// Parser failures must fail loudly: a silent "log + continue" produces an
	// empty GenerateResult per directory, which Gazelle interprets as "delete
	// all existing rules". A green run that scorches the build graph is worse
	// than a noisy exit.
	runner, err := clojureparser.New(binaryPath)
	if err != nil {
		log.Fatalf("clojure: failed to start parser at %s: %v\n"+
			"hint: run `bazel build //src/rules_clojure:gazelle_server` first, "+
			"or set GAZELLE_CLOJURE_PARSER to a built binary.", binaryPath, err)
	}

	// Maven repository path: env override → $HOME/.m2/repository default.
	// The env knob lets CI/containers/Maven-custom-repo setups point at a
	// pre-populated cache without having to symlink into the runner's HOME.
	repositoryDir := os.Getenv("CLOJURE_MAVEN_REPOSITORY")
	if repositoryDir == "" {
		repositoryDir = filepath.Join(os.Getenv("HOME"), ".m2", "repository")
	}
	rootDepsRepo := configs.DepsRepo("")
	aliases := configs.Aliases()

	resp, err := runner.Init(clojureparser.InitRequest{
		DepsEdnPath:    configs.DepsEdn(),
		RepositoryDir:  repositoryDir,
		DepsRepoTag:    rootDepsRepo,
		RootModuleName: configs.RootModuleName(),
		Aliases:        aliases,
	})
	if err != nil {
		runner.Shutdown()
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
