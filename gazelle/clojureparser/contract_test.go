package clojureparser

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// TestContractInitResponseShape verifies the mock and real parser produce
// responses with the same structure. This is a contract verification test —
// when mock and real diverge, this test fails, prompting a mock update.
//
// Requires: the gazelle_server deploy jar built at
// bazel-bin/src/rules_clojure/gazelle_server_deploy.jar
// and a /tmp/test_deps.edn fixture. Skip if not available.
func TestContractInitResponseShape(t *testing.T) {
	// Find the deploy jar relative to workspace root.
	wsRoot := findWorkspaceRoot(t)
	deployJar := filepath.Join(wsRoot, "bazel-bin/src/rules_clojure/gazelle_server_deploy.jar")
	if _, err := os.Stat(deployJar); err != nil {
		t.Skipf("deploy jar not found at %s (run: bazel build //src/rules_clojure:gazelle_server_deploy.jar)", deployJar)
	}

	// Create minimal test fixture.
	depsEdn := filepath.Join(t.TempDir(), "deps.edn")
	srcDir := filepath.Join(filepath.Dir(depsEdn), "src", "foo")
	os.MkdirAll(srcDir, 0755)
	os.WriteFile(depsEdn, []byte(`{:deps {org.clojure/clojure {:mvn/version "1.12.1"}} :paths ["src"]}`), 0644)
	os.WriteFile(filepath.Join(srcDir, "core.clj"), []byte(`(ns foo.core (:require [clojure.string :as str]))`), 0644)

	// Check java is available.
	if _, err := exec.LookPath("java"); err != nil {
		t.Skip("java not found on PATH")
	}

	// Start real parser.
	realRunner, err := New("java", "-jar", deployJar)
	if err != nil {
		// Try alternative invocation.
		t.Skipf("could not start real parser: %v", err)
	}
	defer realRunner.Shutdown()

	// Start mock parser.
	mockDir := t.TempDir()
	mockScript := writeMockScript(t, mockDir)
	mockRunner, err := New(mockScript)
	if err != nil {
		t.Fatalf("could not start mock parser: %v", err)
	}
	defer mockRunner.Shutdown()

	// Send init to both.
	initReq := InitRequest{
		DepsEdnPath:   depsEdn,
		RepositoryDir: filepath.Join(os.Getenv("HOME"), ".m2", "repository"),
		DepsRepoTag:   "@deps",
		Aliases:       []string{},
	}

	realResp, err := realRunner.Init(initReq)
	if err != nil {
		t.Skipf("real parser init failed (deps not cached?): %v", err)
	}
	mockResp, err := mockRunner.Init(initReq)
	if err != nil {
		t.Fatalf("mock parser init failed: %v", err)
	}

	// Verify structural equivalence (not value equality — the mock has canned data).
	// Type field.
	if realResp.Type != mockResp.Type {
		t.Errorf("type mismatch: real=%q mock=%q", realResp.Type, mockResp.Type)
	}

	// dep_ns_labels must have "clj" and "cljs" keys in both.
	for _, platform := range []string{"clj", "cljs"} {
		if _, ok := realResp.DepNsLabels[platform]; !ok {
			t.Errorf("real response missing dep_ns_labels[%q]", platform)
		}
		if _, ok := mockResp.DepNsLabels[platform]; !ok {
			t.Errorf("mock response missing dep_ns_labels[%q]", platform)
		}
	}

	// source_paths must be non-nil slices.
	if realResp.SourcePaths == nil {
		t.Error("real response has nil source_paths")
	}
	if mockResp.SourcePaths == nil {
		t.Error("mock response has nil source_paths")
	}

	// ignore_paths must be non-nil slices.
	if realResp.IgnorePaths == nil {
		t.Error("real response has nil ignore_paths")
	}
	if mockResp.IgnorePaths == nil {
		t.Error("mock response has nil ignore_paths")
	}

	// Send parse to both.
	parseReq := ParseRequest{
		Dir:   filepath.Join(filepath.Dir(depsEdn), "src", "foo"),
		Files: []string{"core.clj"},
	}

	realParseResp, err := realRunner.Parse(parseReq)
	if err != nil {
		t.Fatalf("real parser parse failed: %v", err)
	}
	mockParseResp, err := mockRunner.Parse(parseReq)
	if err != nil {
		t.Fatalf("mock parser parse failed: %v", err)
	}

	// Type field.
	if realParseResp.Type != mockParseResp.Type {
		t.Errorf("parse type mismatch: real=%q mock=%q", realParseResp.Type, mockParseResp.Type)
	}

	// Both must return non-empty namespaces.
	if len(realParseResp.Namespaces) == 0 {
		t.Error("real parse returned 0 namespaces")
	}
	if len(mockParseResp.Namespaces) == 0 {
		t.Error("mock parse returned 0 namespaces")
	}

	// Verify namespace info structure.
	if len(realParseResp.Namespaces) > 0 && len(mockParseResp.Namespaces) > 0 {
		realNs := realParseResp.Namespaces[0]
		mockNs := mockParseResp.Namespaces[0]

		if (realNs.Ns == "") != (mockNs.Ns == "") {
			t.Errorf("ns field: real=%q mock=%q", realNs.Ns, mockNs.Ns)
		}
		if (realNs.File == "") != (mockNs.File == "") {
			t.Errorf("file field: real=%q mock=%q", realNs.File, mockNs.File)
		}
		if realNs.Requires == nil && mockNs.Requires != nil {
			t.Error("requires: real is nil but mock is not")
		}
		if len(realNs.Platforms) == 0 && len(mockNs.Platforms) > 0 {
			t.Error("platforms: real is empty but mock is not")
		}
	}
}

func findWorkspaceRoot(t *testing.T) string {
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
			t.Skip("could not find WORKSPACE root")
		}
		dir = parent
	}
}
