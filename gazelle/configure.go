package gazelle

import (
	"flag"
	"log"
	"os"
	"path/filepath"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

func (*clojureLang) RegisterFlags(fs *flag.FlagSet, cmd string, c *config.Config) {}

func (*clojureLang) CheckFlags(fs *flag.FlagSet, c *config.Config) error {
	return nil
}

func (*clojureLang) KnownDirectives() []string {
	return clojureconfig.AllDirectives()
}

func (l *clojureLang) Configure(c *config.Config, rel string, f *rule.File) {
	configs, _ := c.Exts[languageName].(clojureconfig.Configs)
	if configs == nil {
		configs = make(clojureconfig.Configs)
		c.Exts[languageName] = configs
	}

	var cfg *clojureconfig.Config
	if _, exists := configs[rel]; exists {
		cfg = configs[rel]
	} else if parent := configs.ParentForPackage(rel); parent != nil {
		cfg = parent.NewChild()
	} else {
		cfg = clojureconfig.New()
		// Auto-discover deps.edn at workspace root.
		depsPath := filepath.Join(c.RepoRoot, "deps.edn")
		if _, err := os.Stat(depsPath); err == nil {
			cfg.SetDepsEdn(depsPath)
		}
	}
	configs[rel] = cfg

	// Process directives from BUILD file.
	if f != nil {
		for _, d := range f.Directives {
			switch d.Key {
			case clojureconfig.ClojureExtensionDirective:
				cfg.SetExtensionEnabled(d.Value == "true" || d.Value == "enabled")
			case clojureconfig.ClojureDepsEdn:
				cfg.SetDepsEdn(filepath.Join(c.RepoRoot, d.Value))
			case clojureconfig.ClojureDepsRepo:
				cfg.SetDepsRepo(d.Value)
			case clojureconfig.ClojureAliases:
				cfg.SetAliases(clojureconfig.ParseAliases(d.Value))
			}
		}
	}

	// Start parser lazily when processing the root package.
	if rel == "" && l.parser == nil {
		rootCfg := configs[""]
		if rootCfg != nil && rootCfg.DepsEdn() != "" {
			l.startParser(c.RepoRoot, rootCfg)
		}
	}
}

func (l *clojureLang) startParser(repoRoot string, cfg *clojureconfig.Config) {
	binaryPath := os.Getenv("GAZELLE_CLOJURE_PARSER")
	if binaryPath == "" {
		binaryPath = filepath.Join(repoRoot, "bazel-bin/src/rules_clojure/gazelle_server")
	}

	runner, err := clojureparser.New(binaryPath)
	if err != nil {
		log.Printf("clojure: failed to start parser: %v", err)
		return
	}

	repositoryDir := filepath.Join(os.Getenv("HOME"), ".m2", "repository")

	resp, err := runner.Init(clojureparser.InitRequest{
		DepsEdnPath:   cfg.DepsEdn(),
		RepositoryDir: repositoryDir,
		DepsRepoTag:   cfg.DepsRepo(),
		Aliases:       cfg.Aliases(),
	})
	if err != nil {
		log.Printf("clojure: parser init failed: %v", err)
		runner.Shutdown()
		return
	}

	l.parser = runner
	l.initResp = resp
	l.depsRepoTag = cfg.DepsRepo()
	log.Printf("clojure: parser started (source_paths=%v, ignore_paths=%v, dep_ns_labels clj=%d cljs=%d)",
		resp.SourcePaths, resp.IgnorePaths, len(resp.DepNsLabels["clj"]), len(resp.DepNsLabels["cljs"]))
}
