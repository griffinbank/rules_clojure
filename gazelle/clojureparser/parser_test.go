package clojureparser

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// realRunnerFixture starts a Runner backed by the real Clojure parser
// deploy jar. Skips the test cleanly if the jar or `java` aren't
// available (clean CI without a prior `bazel build`) — unless
// GAZELLE_INTEGRATION_TEST_REQUIRED=1, in which case the same conditions
// cause a Fatal. CI sets the env var so a missing fixture doesn't
// silently zero-cover the integration path.
//
// Returns the Runner + the workspace-relative path of the fixture
// deps.edn the caller can pass through InitRequest.
func realRunnerFixture(t *testing.T) (*Runner, string) {
	t.Helper()
	required := os.Getenv("GAZELLE_INTEGRATION_TEST_REQUIRED") == "1"
	skipOrFail := func(format string, args ...interface{}) {
		t.Helper()
		if required {
			t.Fatalf(format, args...)
		}
		t.Skipf(format, args...)
	}

	deployJar := os.Getenv("GAZELLE_SERVER_JAR")
	if deployJar == "" {
		wsRoot := findWorkspaceRoot(t, required)
		deployJar = filepath.Join(wsRoot, "bazel-bin/src/rules_clojure/gazelle_server_deploy.jar")
	}
	if _, err := os.Stat(deployJar); err != nil {
		skipOrFail("deploy jar not found at %s (run: bazel build //src/rules_clojure:gazelle_server_deploy.jar, or set GAZELLE_SERVER_JAR)", deployJar)
	}
	if _, err := exec.LookPath("java"); err != nil {
		skipOrFail("java not found on PATH")
	}

	// Minimal fixture deps.edn + one .clj file the parse tests can target.
	depsEdn := filepath.Join(t.TempDir(), "deps.edn")
	srcDir := filepath.Join(filepath.Dir(depsEdn), "src", "my")
	if err := os.MkdirAll(srcDir, 0755); err != nil {
		t.Fatalf("mkdir %s: %v", srcDir, err)
	}
	if err := os.WriteFile(depsEdn, []byte(`{:deps {org.clojure/clojure {:mvn/version "1.12.1"}} :paths ["src"]}`), 0644); err != nil {
		t.Fatalf("write %s: %v", depsEdn, err)
	}
	if err := os.WriteFile(filepath.Join(srcDir, "app.clj"), []byte(`(ns my.app (:require [clojure.string :as str]))`), 0644); err != nil {
		t.Fatalf("write app.clj: %v", err)
	}

	runner, err := New("java", "-jar", deployJar)
	if err != nil {
		skipOrFail("could not start parser: %v", err)
	}
	t.Cleanup(runner.Shutdown)
	return runner, depsEdn
}

// findWorkspaceRoot walks up from the test's cwd looking for WORKSPACE
// (the Bazel workspace marker) so we can resolve the deploy jar at the
// standard bazel-bin path. Skips the test if no workspace is found,
// unless required=true (CI mode) in which case it fatals.
func findWorkspaceRoot(t *testing.T, required bool) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "WORKSPACE")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			if required {
				t.Fatal("GAZELLE_INTEGRATION_TEST_REQUIRED=1 but no WORKSPACE found")
			}
			t.Skip("could not find WORKSPACE root")
		}
		dir = parent
	}
}

// initRequestFor builds an InitRequest pointing at the fixture deps.edn
// + the host's local Maven cache (the real parser needs to resolve
// transitive coords from there).
func initRequestFor(depsEdn string) InitRequest {
	return InitRequest{
		DepsEdnPath:   depsEdn,
		RepositoryDir: filepath.Join(os.Getenv("HOME"), ".m2", "repository"),
		DepsRepoTag:   "@deps",
		Aliases:       []string{},
	}
}

func TestInitRoundTrip(t *testing.T) {
	runner, depsEdn := realRunnerFixture(t)

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
	runner, depsEdn := realRunnerFixture(t)

	if _, err := runner.Init(initRequestFor(depsEdn)); err != nil {
		t.Fatalf("Init: %v", err)
	}

	resp, err := runner.Parse(ParseRequest{
		Dir:   filepath.Join(filepath.Dir(depsEdn), "src", "my"),
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
		Type:          MsgTypeInit,
		DepsEdnPath:   "/repo/deps.edn",
		RepositoryDir: "/repo",
		DepsRepoTag:   "@deps",
		Aliases:       []string{"dev", "test"},
	}
	data, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	// Assert against the literal byte stream so the snake_case JSON tags
	// (deps_edn_path / repository_dir / deps_repo_tag / root_module_name /
	// aliases) stay locked in — the Clojure server keys on these names.
	for _, key := range []string{
		`"type"`, `"deps_edn_path"`, `"repository_dir"`, `"deps_repo_tag"`,
		`"root_module_name"`, `"aliases"`,
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
