package clojureparser

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/bazelbuild/rules_go/go/runfiles"
)

// realRunnerFixture starts a Runner backed by the real babashka parser
// script against a self-contained tempdir fixture: a tiny deps.edn + a
// tiny @deps/BUILD.bazel + a fixture .clj file. Deliberately offline so
// the test exercises the wire protocol without depending on any
// project-specific Bazel state.
//
// Returns the Runner, the fixture deps.edn path, and the fixture src
// directory absolute path.
func realRunnerFixture(t *testing.T) (runner *Runner, depsEdn string, srcDir string) {
	t.Helper()
	bbPath := resolveRunfile(t, "GAZELLE_CLOJURE_BB")
	scriptPath := resolveRunfile(t, "GAZELLE_CLOJURE_PARSER")

	tmp := t.TempDir()
	depsEdn = filepath.Join(tmp, "deps.edn")
	srcDir = filepath.Join(tmp, "src", "my")
	if err := os.MkdirAll(srcDir, 0755); err != nil {
		t.Fatalf("mkdir %s: %v", srcDir, err)
	}
	if err := os.WriteFile(depsEdn, []byte(`{:paths ["src"]}`), 0644); err != nil {
		t.Fatalf("write %s: %v", depsEdn, err)
	}
	if err := os.WriteFile(filepath.Join(srcDir, "app.clj"),
		[]byte("(ns my.app (:require [clojure.string :as str]))\n"), 0644); err != nil {
		t.Fatalf("write app.clj: %v", err)
	}
	// Minimal @deps/BUILD.bazel: no real jars, just enough to satisfy the
	// parser's expectation that the file exists. The parser will scan it,
	// find no java_import / clojure_library blocks, and produce an empty
	// dep-ns->label map. That's fine for these wire-level tests; they
	// validate the round-trip protocol, not basis content.
	depsBuildDir := filepath.Join(tmp, "fakedeps")
	if err := os.MkdirAll(depsBuildDir, 0755); err != nil {
		t.Fatalf("mkdir %s: %v", depsBuildDir, err)
	}
	if err := os.WriteFile(filepath.Join(depsBuildDir, "BUILD.bazel"),
		[]byte("# empty @deps for tests\n"), 0644); err != nil {
		t.Fatalf("write fake @deps/BUILD.bazel: %v", err)
	}

	// Point the bb script at the fixture @deps/BUILD.bazel via GAZELLE_DEPS_BUILD
	// (skips the normal `bazel info output_base` probe).
	t.Setenv("GAZELLE_DEPS_BUILD", filepath.Join(depsBuildDir, "BUILD.bazel"))

	r, err := New(bbPath, scriptPath)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	t.Cleanup(func() { _ = r.Shutdown() })
	return r, depsEdn, srcDir
}

// resolveRunfile reads `envVar` (set by the BUILD rule to an
// rlocationpath) and returns the absolute filesystem path via the
// runfiles library. Skips when missing so raw `go test` doesn't fail -
// the env var is guaranteed under `bazel test`.
func resolveRunfile(t *testing.T, envVar string) string {
	t.Helper()
	v := os.Getenv(envVar)
	if v == "" {
		t.Skipf("%s not set (run via `bazel test`, not raw `go test`)", envVar)
	}
	p, err := runfiles.Rlocation(v)
	if err != nil {
		t.Fatalf("runfiles.Rlocation(%q) from %s: %v", v, envVar, err)
	}
	if _, err := os.Stat(p); err != nil {
		t.Fatalf("%s=%q resolved to %q but file missing: %v", envVar, v, p, err)
	}
	return p
}

// initRequestFor builds an InitRequest pointing at the fixture deps.edn.
func initRequestFor(depsEdn string) InitRequest {
	return InitRequest{
		DepsEdnPath: depsEdn,
		DepsRepoTag: "@deps",
		Aliases:     []string{},
	}
}

func TestInitRoundTrip(t *testing.T) {
	runner, depsEdn, _ := realRunnerFixture(t)

	resp, err := runner.Init(initRequestFor(depsEdn))
	if err != nil {
		t.Fatalf("Init: %v", err)
	}

	if resp.Type != MsgTypeInit {
		t.Errorf("expected type %q, got %q", MsgTypeInit, resp.Type)
	}
	// Exact source-paths shape (fixture has only :paths ["src"]).
	if got, want := resp.SourcePaths, []string{"src"}; !slicesEqual(got, want) {
		t.Errorf("SourcePaths = %v; want %v", got, want)
	}
	// IgnorePaths must be present (non-nil) and empty for the fixture
	// (no :bazel :ignore configured). A regression that lazily returned a
	// non-empty stale slice would pass a bare nil-check.
	if resp.IgnorePaths == nil || len(resp.IgnorePaths) != 0 {
		t.Errorf("IgnorePaths = %v; want empty non-nil slice", resp.IgnorePaths)
	}
	// Both platform maps must be non-nil (bb side emits an empty map when no
	// jars provide that platform: never null).
	if resp.DepNsLabels.Clj == nil {
		t.Errorf("DepNsLabels.Clj = nil; want empty map")
	}
	if resp.DepNsLabels.Cljs == nil {
		t.Errorf("DepNsLabels.Cljs = nil; want empty map")
	}
}

func slicesEqual(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestParseRoundTrip(t *testing.T) {
	runner, depsEdn, srcDir := realRunnerFixture(t)

	if _, err := runner.Init(initRequestFor(depsEdn)); err != nil {
		t.Fatalf("Init: %v", err)
	}

	resp, err := runner.Parse(ParseRequest{
		Dir:   srcDir,
		Files: []string{"app.clj"},
	})
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}

	if resp.Type != MsgTypeParse {
		t.Errorf("expected type %q, got %q", MsgTypeParse, resp.Type)
	}
	if len(resp.Namespaces) != 1 {
		t.Fatalf("expected 1 namespace, got %d", len(resp.Namespaces))
	}
	ns := resp.Namespaces[0]
	if ns.Ns != "my.app" {
		t.Errorf("expected ns my.app, got %q", ns.Ns)
	}
	if ns.File != "app.clj" {
		t.Errorf("expected file app.clj, got %q", ns.File)
	}
	cljReqs, ok := ns.Requires[PlatformClj]
	if !ok || len(cljReqs) != 1 || cljReqs[0] != "clojure.string" {
		t.Errorf("expected requires {%q: [clojure.string]}, got %v", PlatformClj, ns.Requires)
	}
	foundClj := false
	for _, p := range ns.Platforms {
		if p == PlatformClj {
			foundClj = true
			break
		}
	}
	if !foundClj {
		t.Errorf("platforms = %v; want %q present", ns.Platforms, PlatformClj)
	}
}

func TestInitRequestJSON(t *testing.T) {
	// Marshal goes through taggedRequest so the wire shape carries
	// "type":"init" alongside the snake_case payload fields.
	wire := taggedRequest[InitRequest]{
		Type: MsgTypeInit,
		Payload: InitRequest{
			DepsEdnPath: "/repo/deps.edn",
			DepsRepoTag: "@deps",
			Aliases:     []string{"dev", "test"},
		},
	}
	data, err := json.Marshal(wire)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	for _, key := range []string{
		`"type"`, `"deps_edn_path"`, `"deps_repo_tag"`, `"aliases"`,
	} {
		if !strings.Contains(string(data), key) {
			t.Errorf("marshalled InitRequest missing %s; got %s", key, data)
		}
	}
	// Round-trip the discriminator separately so a tag-name regression
	// surfaces with the actual offending value, not just a missing field.
	var decoded map[string]interface{}
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if decoded["type"] != MsgTypeInit {
		t.Errorf("type round-trip = %v; want %q", decoded["type"], MsgTypeInit)
	}
}
