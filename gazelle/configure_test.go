package gazelle

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
)

// configureRoot drives the root-package Configure with a fixture deps.edn +
// MODULE.bazel and returns the populated Configs and clojureLang.
func configureRoot(t *testing.T, modContent string) (clojureconfig.Configs, *clojureLang) {
	t.Helper()
	dir := t.TempDir()
	if modContent != "" {
		if err := os.WriteFile(filepath.Join(dir, "MODULE.bazel"), []byte(modContent), 0644); err != nil {
			t.Fatal(err)
		}
	}
	// deps.edn presence triggers ClojureDepsEdn auto-discovery but Configure
	// only calls startParser when DepsEdn() is non-empty. We deliberately
	// skip startParser by NOT writing a deps.edn — startParser would shell
	// out and fail in tests. Configure's pre-startParser bookkeeping is
	// what we exercise here.
	c := &config.Config{
		RepoRoot: dir,
		Exts:     map[string]interface{}{},
	}
	l := &clojureLang{
		ruleNs:            map[string]string{},
		hasClojureContent: map[string]bool{},
	}
	l.Configure(c, "", nil)
	configs, ok := c.Exts[languageName].(clojureconfig.Configs)
	if !ok {
		t.Fatalf("Configure did not populate Exts[%q]; got %T", languageName, c.Exts[languageName])
	}
	return configs, l
}

func TestConfigureAutoDiscoversModuleName(t *testing.T) {
	configs, _ := configureRoot(t, `module(name = "my_module", version = "0.1.0")`)
	if got := configs.RootModuleName(); got != "my_module" {
		t.Errorf("RootModuleName = %q; want my_module", got)
	}
}

func TestConfigureNoModuleBazel(t *testing.T) {
	configs, _ := configureRoot(t, "")
	if got := configs.RootModuleName(); got != "" {
		t.Errorf("RootModuleName = %q; want \"\" (legacy WORKSPACE projects)", got)
	}
}

func TestConfigureNoDepsEdnSkipsParser(t *testing.T) {
	_, l := configureRoot(t, "")
	if l.session != nil {
		t.Errorf("session populated despite missing deps.edn; want nil")
	}
}

func TestConfigureRespectsExtensionDisableDirective(t *testing.T) {
	dir := t.TempDir()
	c := &config.Config{RepoRoot: dir, Exts: map[string]interface{}{}}
	l := &clojureLang{ruleNs: map[string]string{}, hasClojureContent: map[string]bool{}}
	// Root Configure.
	l.Configure(c, "", nil)
	// Sub-package Configure with `# gazelle:clojure_extension false`.
	f := &rule.File{
		Directives: []rule.Directive{
			{Key: clojureconfig.ClojureExtensionDirective, Value: "false"},
		},
	}
	l.Configure(c, "src/disabled", f)
	configs := c.Exts[languageName].(clojureconfig.Configs)
	if configs.ExtensionEnabled("src/disabled") {
		t.Error("ExtensionEnabled(src/disabled) = true; want false after directive")
	}
	if !configs.ExtensionEnabled("src/enabled") {
		t.Error("ExtensionEnabled(src/enabled) = false; want true (no directive there)")
	}
}

func TestConfigureRespectsAliasDirectiveAtRoot(t *testing.T) {
	dir := t.TempDir()
	c := &config.Config{RepoRoot: dir, Exts: map[string]interface{}{}}
	l := &clojureLang{ruleNs: map[string]string{}, hasClojureContent: map[string]bool{}}
	f := &rule.File{
		Directives: []rule.Directive{
			{Key: clojureconfig.ClojureAliases, Value: ":dev,:test"},
		},
	}
	l.Configure(c, "", f)
	configs := c.Exts[languageName].(clojureconfig.Configs)
	got := configs.Aliases()
	want := []string{":dev", ":test"}
	if len(got) != len(want) {
		t.Fatalf("Aliases() = %v; want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("Aliases()[%d] = %q; want %q", i, got[i], want[i])
		}
	}
}
