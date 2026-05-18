package gazelle

import (
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	build "github.com/bazelbuild/buildtools/build"
	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/language"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

func TestIsClojureExt(t *testing.T) {
	cases := map[string]bool{
		".clj":  true,
		".cljs": true,
		".cljc": true,
		".js":   true,
		".java": false,
		".txt":  false,
		"":      false,
	}
	for ext, want := range cases {
		if got := isClojureExt(ext); got != want {
			t.Errorf("isClojureExt(%q) = %v; want %v", ext, got, want)
		}
	}
}

func TestPathUnderMatchesPrefix(t *testing.T) {
	candidates := []string{"src/main/clojure", "src/test/clojure"}
	cases := map[string]string{
		"src/main/clojure":         "src/main/clojure",
		"src/main/clojure/foo/bar": "src/main/clojure",
		"src/test/clojure/x":       "src/test/clojure",
		"src/main":                 "", // shorter than any candidate
		"unrelated":                "",
		"":                         "",
	}
	for rel, want := range cases {
		if got := pathUnder(rel, candidates); got != want {
			t.Errorf("pathUnder(%q) = %q; want %q", rel, got, want)
		}
	}
}

func TestSubdirHasClojureFiles(t *testing.T) {
	dir := t.TempDir()

	// Empty dir → false.
	if subdirHasClojureFiles(dir) {
		t.Errorf("subdirHasClojureFiles(empty) = true; want false")
	}

	// Non-Clojure file → false.
	if err := os.WriteFile(filepath.Join(dir, "README.md"), []byte("x"), 0644); err != nil {
		t.Fatal(err)
	}
	if subdirHasClojureFiles(dir) {
		t.Errorf("subdirHasClojureFiles(only README) = true; want false")
	}

	// Nested .clj → true.
	subdir := filepath.Join(dir, "src", "foo")
	if err := os.MkdirAll(subdir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(subdir, "core.clj"), []byte("(ns foo.core)"), 0644); err != nil {
		t.Fatal(err)
	}
	if !subdirHasClojureFiles(dir) {
		t.Errorf("subdirHasClojureFiles(with nested .clj) = false; want true")
	}

	// .js does NOT count (rollup excludes JS-only subdirs).
	jsOnly := t.TempDir()
	if err := os.WriteFile(filepath.Join(jsOnly, "lib.js"), []byte("x"), 0644); err != nil {
		t.Fatal(err)
	}
	if subdirHasClojureFiles(jsOnly) {
		t.Errorf("subdirHasClojureFiles(JS only) = true; want false")
	}
}

func TestSubdirHasClojureFilesSkipsSymlinkedDir(t *testing.T) {
	// filepath.WalkDir does NOT follow symlinked directories by default
	// (correct behaviour). Pin this so a future migration to filepath.Walk
	// (which DOES follow) doesn't silently change semantics.
	root := t.TempDir()
	target := t.TempDir()
	if err := os.WriteFile(filepath.Join(target, "core.clj"), []byte("(ns x)"), 0644); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "symlinked")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("Symlink unsupported on this filesystem: %v", err)
	}
	if subdirHasClojureFiles(root) {
		t.Errorf("subdirHasClojureFiles followed a symlink to find .clj; want false (WalkDir contract)")
	}
}

func TestBuildRuleRejectsNonIntegerFloat(t *testing.T) {
	// applyAttr coerces float64 to int when integer-valued; non-integer
	// floats are an error. Without this test, a regression that dropped
	// the integer check would silently truncate shard_count = 1.5 to 1.
	spec := clojureparser.RuleSpec{
		Kind: "clojure_test",
		Attrs: map[string]interface{}{
			"name":        "core_test",
			"shard_count": float64(1.5),
		},
	}
	_, err := buildRule(spec)
	if err == nil {
		t.Fatal("buildRule accepted non-integer float; want error")
	}
	if !strings.Contains(err.Error(), "non-integer float") {
		t.Errorf("err = %q; want 'non-integer float' marker", err.Error())
	}
}

func TestBuildRuleCoercesAttrTypes(t *testing.T) {
	spec := clojureparser.RuleSpec{
		Kind: "clojure_library",
		Attrs: map[string]interface{}{
			"name":      "core",
			"resources": []interface{}{"core.clj", "core.cljs"},
			"aot":       []interface{}{"my.core"},
			// Numeric / boolean / string attrs all flow through.
			"shard_count": float64(4),
			"flaky":       true,
			"timeout":     "long",
		},
	}
	r, err := buildRule(spec)
	if err != nil {
		t.Fatalf("buildRule: %v", err)
	}
	if r.Kind() != "clojure_library" {
		t.Errorf("kind = %q; want clojure_library", r.Kind())
	}
	if r.Name() != "core" {
		t.Errorf("name = %q; want core", r.Name())
	}
	if got := r.AttrStrings("resources"); !reflect.DeepEqual(got, []string{"core.clj", "core.cljs"}) {
		t.Errorf("resources = %v; want [core.clj core.cljs]", got)
	}
	if got := r.AttrStrings("aot"); !reflect.DeepEqual(got, []string{"my.core"}) {
		t.Errorf("aot = %v; want [my.core]", got)
	}
	if got := r.AttrString("timeout"); got != "long" {
		t.Errorf("timeout = %q; want long", got)
	}
	// Numeric + bool attrs aren't readable via AttrStrings/AttrString; just
	// check they were set at all.
	for _, k := range []string{"shard_count", "flaky"} {
		if r.Attr(k) == nil {
			t.Errorf("attr %q not set", k)
		}
	}
}

func TestBuildRuleRejectsMissingName(t *testing.T) {
	_, err := buildRule(clojureparser.RuleSpec{
		Kind:  "clojure_library",
		Attrs: map[string]interface{}{"resources": []interface{}{"x.clj"}},
	})
	if err == nil {
		t.Error("buildRule with no :name accepted; want error")
	}
}

func TestBuildRuleAcceptsStringDictAttr(t *testing.T) {
	// clojure_test :env is a string_dict; ns-rules (gazelle_server.bb)
	// emits it as a Clojure map of {string string}. applyAttr must
	// convert to map[string]string
	// so rule.SetAttr sees the typed shape Bazel emits as a dict literal.
	r, err := buildRule(clojureparser.RuleSpec{
		Kind: "clojure_test",
		Attrs: map[string]interface{}{
			"name":    "foo",
			"test_ns": "foo",
			"env":     map[string]interface{}{"K1": "v1", "K2": "v2"},
		},
	})
	if err != nil {
		t.Fatalf("buildRule with string_dict attr rejected: %v", err)
	}
	// rule.Rule has no typed string-dict reader; check that the attr was set
	// and serializes as a Starlark dict literal containing both keys.
	expr := r.Attr("env")
	if expr == nil {
		t.Fatal("env attr not set")
	}
	src := build.FormatString(expr)
	for _, want := range []string{`"K1": "v1"`, `"K2": "v2"`} {
		if !strings.Contains(src, want) {
			t.Errorf("env attr source = %q; want %s", src, want)
		}
	}
}

func TestBuildRuleRejectsNonStringDictValue(t *testing.T) {
	_, err := buildRule(clojureparser.RuleSpec{
		Kind: "clojure_test",
		Attrs: map[string]interface{}{
			"name":    "foo",
			"test_ns": "foo",
			"env":     map[string]interface{}{"K1": 42},
		},
	})
	if err == nil {
		t.Error("buildRule with non-string dict value accepted; want error")
	}
}

func TestBuildRuleRejectsUnknownKind(t *testing.T) {
	_, err := buildRule(clojureparser.RuleSpec{
		Kind:  "clojure_libary", // typo
		Attrs: map[string]interface{}{"name": "x"},
	})
	if err == nil {
		t.Error("buildRule with unknown kind accepted; want error")
	}
}

func TestBuildRuleRejectsNilAttr(t *testing.T) {
	_, err := buildRule(clojureparser.RuleSpec{
		Kind:  "clojure_library",
		Attrs: map[string]interface{}{"name": "x", "resources": nil},
	})
	if err == nil {
		t.Error("buildRule with nil attr accepted; want error")
	}
}

// writeStubBB creates a stub bb-like script that responds to one init
// request (returning the supplied JSON) and then exits. Used by the
// fatal-path tests below: the subprocess "succeeds" at init but dies
// before the test's parse call, so generate.GenerateRules trips the
// log.Fatalf path it would on a real mid-run parser death.
func writeStubBB(t *testing.T, initResponse string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "stub.sh")
	body := "#!/bin/sh\nread first\nprintf '%s\\n' '" + initResponse + "'\n"
	if err := os.WriteFile(path, []byte(body), 0755); err != nil {
		t.Fatal(err)
	}
	return path
}

// writeStubBBMultiResponse builds a stub bb-like script that responds
// to multiple newline-JSON requests in order, then waits for EOF on stdin.
// Lets one test exercise the full init -> parse -> generate flow without
// invoking the real bb subprocess.
func writeStubBBMultiResponse(t *testing.T, responses ...string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "stub.sh")
	var body strings.Builder
	body.WriteString("#!/bin/sh\n")
	for _, r := range responses {
		body.WriteString("read line\nprintf '%s\\n' '")
		body.WriteString(r)
		body.WriteString("'\n")
	}
	body.WriteString("read line\n") // hang waiting for AfterResolvingDeps to close stdin
	if err := os.WriteFile(path, []byte(body.String()), 0755); err != nil {
		t.Fatal(err)
	}
	return path
}

// TestGenerateRulesEndToEnd drives Configure -> GenerateRules -> Resolve
// against a stubbed bb subprocess, asserting that a single .clj file
// flows through to a clojure_library rule with the deps the bb side
// emitted. Closest the unit-test layer gets to a full bazel gazelle_bin
// integration test (which requires a Bazel test target with the real
// gazelle_bin + bb runfiles wired in).
func TestGenerateRulesEndToEnd(t *testing.T) {
	initResp := `{"type":"init","dep_ns_labels":{"clj":{"clojure.string":"org_clojure_clojure"},"cljs":{}},"deps_bazel":{"deps":{}},"ignore_paths":[],"source_paths":["src"]}`
	parseResp := `{"type":"parse","namespaces":[{"ns":"my.app","file":"app.clj","requires":{"clj":["clojure.string"]},"platforms":["clj"],"rules":[{"kind":"clojure_library","attrs":{"name":"app","resources":["app.clj"],"resource_strip_prefix":"src","aot":["my.app"],"srcs":["app.clj"],"deps":["@deps//:org_clojure_clojure"]}}]}],"rollup_rules":[]}`
	stub := writeStubBBMultiResponse(t, initResp, parseResp)
	runner, err := clojureparser.New(stub)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer runner.Shutdown()

	resp, err := runner.Init(clojureparser.InitRequest{DepsEdnPath: "/dev/null", DepsRepoTag: "@deps"})
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	l := &clojureLang{
		ruleNs:            map[ruleNsKey]string{},
		hasClojureContent: map[string]bool{},
		session:           &parserSession{runner: runner, depsIndex: resp},
	}
	c := &config.Config{Exts: map[string]interface{}{languageName: clojureconfig.New()}}
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "app.clj"), []byte("(ns my.app)"), 0644); err != nil {
		t.Fatal(err)
	}
	result := l.GenerateRules(language.GenerateArgs{
		Config:       c,
		Rel:          "src",
		RegularFiles: []string{"app.clj"},
		Dir:          dir,
	})
	if len(result.Gen) != 1 {
		t.Fatalf("Gen has %d rules; want 1", len(result.Gen))
	}
	r := result.Gen[0]
	if r.Kind() != "clojure_library" {
		t.Errorf("rule kind = %q; want clojure_library", r.Kind())
	}
	if r.Name() != "app" {
		t.Errorf("rule name = %q; want app", r.Name())
	}
	if got := r.AttrStrings("deps"); !reflect.DeepEqual(got, []string{"@deps//:org_clojure_clojure"}) {
		t.Errorf("deps = %v; want [@deps//:org_clojure_clojure]", got)
	}
	if got, want := r.AttrStrings("aot"), []string{"my.app"}; !reflect.DeepEqual(got, want) {
		t.Errorf("aot = %v; want %v", got, want)
	}
	// Imports payload carries the parsed NamespaceInfo so Resolve can
	// look up requires when reconciling cross-package deps.
	if len(result.Imports) != 1 {
		t.Fatalf("Imports has %d entries; want 1", len(result.Imports))
	}
	if ns, ok := result.Imports[0].(*clojureparser.NamespaceInfo); !ok || ns.Ns != "my.app" {
		t.Errorf("Imports[0] = %v; want NamespaceInfo with Ns=my.app", result.Imports[0])
	}
}

// TestGenerateRulesFatalsOnParserDeath exercises generate.go's
// log.Fatalf-on-Parse-error path. Uses the standard Go pattern:
// re-exec the test binary with an env var so the fatal happens in
// the child, then assert the child exited non-zero.
//
// log.Fatalf calls os.Exit(1), so we can't `recover` in-process.
func TestGenerateRulesFatalsOnParserDeath(t *testing.T) {
	if os.Getenv("FATAL_TEST_CHILD") == "1" {
		// Child: run the GenerateRules call that should fatal.
		stub := writeStubBB(t,
			`{"type":"init","dep_ns_labels":{"clj":{},"cljs":{}},`+
				`"deps_bazel":{"deps":{}},"ignore_paths":[],"source_paths":["src"]}`)
		runner, err := clojureparser.New(stub)
		if err != nil {
			t.Fatalf("New: %v", err)
		}
		resp, err := runner.Init(clojureparser.InitRequest{DepsEdnPath: "/dev/null", DepsRepoTag: "@deps"})
		if err != nil {
			t.Fatalf("Init: %v", err)
		}
		l := &clojureLang{
			ruleNs:            map[ruleNsKey]string{},
			hasClojureContent: map[string]bool{},
			session: &parserSession{
				runner:      runner,
				depsIndex:   resp,
			},
		}
		c := &config.Config{Exts: map[string]interface{}{languageName: clojureconfig.New()}}
		// First Parse should fail: stub script has already exited.
		l.GenerateRules(language.GenerateArgs{
			Config:       c,
			Rel:          "src",
			RegularFiles: []string{"core.clj"},
			Dir:          t.TempDir(),
		})
		// If we get here, GenerateRules failed to fatal: fail the child
		// with a non-fatal Errorf so the parent sees a successful exit
		// and reports the missing-fatal.
		t.Errorf("GenerateRules returned without log.Fatalf: expected fatal exit on dead parser")
		return
	}
	cmd := exec.Command(os.Args[0], "-test.run=TestGenerateRulesFatalsOnParserDeath", "-test.v")
	cmd.Env = append(os.Environ(), "FATAL_TEST_CHILD=1")
	out, err := cmd.CombinedOutput()
	if exitErr, ok := err.(*exec.ExitError); ok && !exitErr.Success() {
		// Exited non-zero (log.Fatalf → exit 1): expected.
		// Pin to the actual fatal message so a future refactor that
		// log.Fatalfs for a different reason doesn't quietly start
		// passing this test.
		wantPhrase := "parser subprocess likely died"
		if !strings.Contains(string(out), wantPhrase) {
			t.Errorf("child exited %v but stderr didn't contain %q:\n%s", err, wantPhrase, out)
		}
		return
	}
	t.Fatalf("subprocess exited cleanly (err=%v); expected log.Fatalf exit. Output:\n%s", err, out)
}

// TestSubdirHasClojureFilesFatalOnWalkError exercises the log.Fatalf path
// when WalkDir returns an error. We point subdirHasClojureFiles at a
// non-existent path to force os.ErrNotExist, which WalkDir surfaces via
// the walk callback's err parameter.
func TestSubdirHasClojureFilesFatalOnWalkError(t *testing.T) {
	if os.Getenv("WALK_FATAL_CHILD") == "1" {
		subdirHasClojureFiles("/definitely/does/not/exist")
		t.Errorf("subdirHasClojureFiles returned without log.Fatalf: expected fatal exit on walk error")
		return
	}
	cmd := exec.Command(os.Args[0], "-test.run=TestSubdirHasClojureFilesFatalOnWalkError", "-test.v")
	cmd.Env = append(os.Environ(), "WALK_FATAL_CHILD=1")
	out, err := cmd.CombinedOutput()
	if exitErr, ok := err.(*exec.ExitError); ok && !exitErr.Success() {
		wantPhrase := "unreadable subtree would otherwise be reported as empty"
		if !strings.Contains(string(out), wantPhrase) {
			t.Errorf("child exited %v but stderr didn't contain %q:\n%s", err, wantPhrase, out)
		}
		return
	}
	t.Fatalf("subprocess exited cleanly (err=%v); expected log.Fatalf exit. Output:\n%s", err, out)
}
