package gazelle

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
)

// configureRoot drives the root-package Configure with a fixture deps.edn +
// MODULE.bazel and returns the populated Configs and clojureLang.
func configureRoot(t *testing.T, modContent string) (*clojureconfig.Configs, *clojureLang) {
	t.Helper()
	dir := t.TempDir()
	if modContent != "" {
		if err := os.WriteFile(filepath.Join(dir, "MODULE.bazel"), []byte(modContent), 0644); err != nil {
			t.Fatal(err)
		}
	}
	// deps.edn presence triggers ClojureDepsEdn auto-discovery but Configure
	// only calls startParser when DepsEdn() is non-empty. We deliberately
	// skip startParser by NOT writing a deps.edn: startParser would shell
	// out and fail in tests. Configure's pre-startParser bookkeeping is
	// what we exercise here.
	c := &config.Config{
		RepoRoot: dir,
		Exts:     map[string]interface{}{},
	}
	l := &clojureLang{
		ruleNs:            map[ruleNsKey]string{},
		hasClojureContent: map[string]bool{},
	}
	l.Configure(c, "", nil)
	configs, ok := c.Exts[languageName].(*clojureconfig.Configs)
	if !ok {
		t.Fatalf("Configure did not populate Exts[%q]; got %T", languageName, c.Exts[languageName])
	}
	return configs, l
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
	l := &clojureLang{ruleNs: map[ruleNsKey]string{}, hasClojureContent: map[string]bool{}}
	// Root Configure.
	l.Configure(c, "", nil)
	// Sub-package Configure with `# gazelle:clojure_enabled false`.
	f := &rule.File{
		Directives: []rule.Directive{
			{Key: clojureconfig.ClojureEnabledDirective, Value: "false"},
		},
	}
	l.Configure(c, "src/disabled", f)
	configs := c.Exts[languageName].(*clojureconfig.Configs)
	if configs.ExtensionEnabled("src/disabled") {
		t.Error("ExtensionEnabled(src/disabled) = true; want false after directive")
	}
	if !configs.ExtensionEnabled("src/enabled") {
		t.Error("ExtensionEnabled(src/enabled) = false; want true (no directive there)")
	}
}

// preBootedLang returns a clojureLang with l.session pre-populated so
// Configure's bootParserSession condition (l.session == nil) is false -
// tests can then exercise directive bookkeeping without the parser
// subprocess startup that would fail in a `go test` environment.
func preBootedLang() *clojureLang {
	return &clojureLang{
		ruleNs:            map[ruleNsKey]string{},
		hasClojureContent: map[string]bool{},
		session:           &parserSession{},
	}
}

func TestConfigureClojureDepsEdnDirectiveResolvesRelativeToRepoRoot(t *testing.T) {
	dir := t.TempDir()
	c := &config.Config{RepoRoot: dir, Exts: map[string]interface{}{}}
	l := preBootedLang()
	f := &rule.File{
		Directives: []rule.Directive{
			{Key: clojureconfig.ClojureDepsEdn, Value: "alt/deps.edn"},
		},
	}
	l.Configure(c, "", f)
	configs := c.Exts[languageName].(*clojureconfig.Configs)
	want := filepath.Join(dir, "alt/deps.edn")
	if got := configs.DepsEdn(); got != want {
		t.Errorf("DepsEdn() = %q; want %q (directive value should be joined with RepoRoot)", got, want)
	}
}

func TestConfigureClojureDepsRepoDirectiveIsPerPackage(t *testing.T) {
	dir := t.TempDir()
	c := &config.Config{RepoRoot: dir, Exts: map[string]interface{}{}}
	l := preBootedLang()
	// Root Configure with default repo tag.
	l.Configure(c, "", &rule.File{
		Directives: []rule.Directive{
			{Key: clojureconfig.ClojureDepsRepo, Value: "@root_deps"},
		},
	})
	// Sub-package Configure overrides the tag.
	l.Configure(c, "src/foo", &rule.File{
		Directives: []rule.Directive{
			{Key: clojureconfig.ClojureDepsRepo, Value: "@other_deps"},
		},
	})
	configs := c.Exts[languageName].(*clojureconfig.Configs)
	if got, want := configs.DepsRepo("src/foo"), "@other_deps"; got != want {
		t.Errorf("DepsRepo(src/foo) = %q; want %q (per-package override)", got, want)
	}
	if got, want := configs.DepsRepo(""), "@root_deps"; got != want {
		t.Errorf("DepsRepo(\"\") = %q; want %q (root tag)", got, want)
	}
	if got, want := configs.DepsRepo("src/elsewhere"), "@root_deps"; got != want {
		t.Errorf("DepsRepo(src/elsewhere) = %q; want %q (unscoped, falls back to root)", got, want)
	}
}

func TestConfigureRespectsAliasDirectiveAtRoot(t *testing.T) {
	dir := t.TempDir()
	c := &config.Config{RepoRoot: dir, Exts: map[string]interface{}{}}
	l := &clojureLang{ruleNs: map[ruleNsKey]string{}, hasClojureContent: map[string]bool{}}
	f := &rule.File{
		Directives: []rule.Directive{
			{Key: clojureconfig.ClojureAliases, Value: ":dev,:test"},
		},
	}
	l.Configure(c, "", f)
	configs := c.Exts[languageName].(*clojureconfig.Configs)
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

// containsString reports whether want appears anywhere in xs.
func containsString(xs []string, want string) bool {
	for _, x := range xs {
		if x == want {
			return true
		}
	}
	return false
}

// Pin both `+` (bazel <=7) and `~` (bazel >=8 / local_path_override).
// Originally missing the `~` form, which broke `bazel run` under bazel 8.
func TestParserScriptCandidatesCoverBazelSeparators(t *testing.T) {
	got := parserScriptRunfileCandidates()
	for _, want := range []string{
		"rules_clojure+/src/rules_clojure/gazelle_server.bb",
		"rules_clojure~/src/rules_clojure/gazelle_server.bb",
	} {
		if !containsString(got, want) {
			t.Errorf("parserScriptRunfileCandidates missing %q; got %v", want, got)
		}
	}
}

// Same separator pin for the bb binary.
func TestBbBinaryCandidatesCoverBazelSeparators(t *testing.T) {
	got := bbBinaryRunfileCandidates()
	if !containsString(got, "rules_multitool++multitool+multitool/tools/bb/bb") {
		t.Errorf("bbBinaryRunfileCandidates missing bazel<=7 (`+`) candidate; got %v", got)
	}
	// At least one tilde-form candidate must be present for bazel >=8.
	tildeFound := false
	for _, c := range got {
		if strings.Contains(c, "rules_multitool~~") {
			tildeFound = true
			break
		}
	}
	if !tildeFound {
		t.Errorf("bbBinaryRunfileCandidates missing bazel>=8 (`~~`) candidate; got %v", got)
	}
}
