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
	// Minimal @deps/BUILD.bazel — no real jars, just enough to satisfy the
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

	// The bb script discovers @deps/BUILD.bazel via `bazel info output_base`.
	// For tests we shortcut that by setting the per-project cache to point
	// at our fake — but the cleanest path is to point GAZELLE_DEPS_BUILD at
	// our fixture; the script honours that env override.
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
// runfiles library. Fatals when missing — every test invocation is via
// `bazel test`, which guarantees the env var.
func resolveRunfile(t *testing.T, envVar string) string {
	t.Helper()
	v := os.Getenv(envVar)
	if v == "" {
		t.Fatalf("%s not set", envVar)
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
	if len(resp.SourcePaths) == 0 {
		t.Errorf("expected at least one source path, got empty")
	}
	if resp.IgnorePaths == nil {
		t.Error("ignore_paths is nil; expected non-nil slice")
	}
	if _, ok := resp.DepNsLabels[PlatformClj]; !ok {
		t.Errorf("missing dep_ns_labels platform %q", PlatformClj)
	}
	if _, ok := resp.DepNsLabels[PlatformCljs]; !ok {
		t.Errorf("missing dep_ns_labels platform %q", PlatformCljs)
	}
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
	req := InitRequest{
		Type:        MsgTypeInit,
		DepsEdnPath: "/repo/deps.edn",
		DepsRepoTag: "@deps",
		Aliases:     []string{"dev", "test"},
	}
	data, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	// Assert against the literal byte stream so the snake_case JSON tags
	// (deps_edn_path / deps_repo_tag / aliases) stay locked in — the bb
	// server keys on these names.
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
