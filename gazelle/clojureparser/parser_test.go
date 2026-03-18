package clojureparser

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// writeMockScript creates a shell script that reads JSON lines from stdin,
// checks the "type" field, and writes canned responses matching the real
// Clojure parser server's protocol.
func writeMockScript(t *testing.T, dir string) string {
	t.Helper()

	if runtime.GOOS == "windows" {
		t.Skip("test requires unix shell")
	}

	script := `#!/bin/sh
# Read lines from stdin, respond with canned JSON.
while IFS= read -r line; do
  type=$(echo "$line" | sed -n 's/.*"type":"\([^"]*\)".*/\1/p')
  case "$type" in
    init)
      echo '{"type":"init","dep_ns_labels":{"clj":{"clojure.core":"org_clojure_clojure","clojure.string":"org_clojure_clojure"},"cljs":{}},"deps_bazel":{},"ignore_paths":[".cpcache"],"source_paths":["src"]}'
      ;;
    parse)
      echo '{"type":"parse","namespaces":[{"ns":"my.app","file":"app.clj","requires":{"clj":["clojure.string"]},"import_deps":[],"gen_class_deps":[],"ns_meta":{},"platforms":["clj"]}]}'
      ;;
    *)
      echo '{"type":"error","message":"unknown request type"}' >&2
      exit 1
      ;;
  esac
done
`
	scriptPath := filepath.Join(dir, "mock_parser.sh")
	if err := os.WriteFile(scriptPath, []byte(script), 0755); err != nil {
		t.Fatalf("write mock script: %v", err)
	}
	return scriptPath
}

func TestInitRoundTrip(t *testing.T) {
	dir := t.TempDir()
	scriptPath := writeMockScript(t, dir)

	runner, err := New(scriptPath)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer runner.Shutdown()

	resp, err := runner.Init(InitRequest{
		DepsEdnPath:   "/repo/deps.edn",
		RepositoryDir: "/repo",
		DepsRepoTag:   "@deps",
		Aliases:       []string{"dev"},
	})
	if err != nil {
		t.Fatalf("Init: %v", err)
	}

	if resp.Type != "init" {
		t.Errorf("expected type init, got %q", resp.Type)
	}
	if len(resp.SourcePaths) != 1 || resp.SourcePaths[0] != "src" {
		t.Errorf("expected source_paths [src], got %v", resp.SourcePaths)
	}
	if len(resp.IgnorePaths) != 1 || resp.IgnorePaths[0] != ".cpcache" {
		t.Errorf("expected ignore_paths [.cpcache], got %v", resp.IgnorePaths)
	}
	// Verify platform-keyed dep_ns_labels structure.
	cljLabels, ok := resp.DepNsLabels["clj"]
	if !ok {
		t.Fatal("missing dep_ns_labels platform 'clj'")
	}
	if cljLabels["clojure.core"] != "org_clojure_clojure" {
		t.Errorf("unexpected clojure.core label: %q", cljLabels["clojure.core"])
	}
	if _, ok := resp.DepNsLabels["cljs"]; !ok {
		t.Fatal("missing dep_ns_labels platform 'cljs'")
	}
}

func TestParseRoundTrip(t *testing.T) {
	dir := t.TempDir()
	scriptPath := writeMockScript(t, dir)

	runner, err := New(scriptPath)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer runner.Shutdown()

	_, err = runner.Init(InitRequest{
		DepsEdnPath:   "/repo/deps.edn",
		RepositoryDir: "/repo",
		DepsRepoTag:   "@deps",
	})
	if err != nil {
		t.Fatalf("Init: %v", err)
	}

	resp, err := runner.Parse(ParseRequest{
		Dir:   "src/my",
		Files: []string{"app.clj"},
	})
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}

	if resp.Type != "parse" {
		t.Errorf("expected type parse, got %q", resp.Type)
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
	cljReqs, ok := ns.Requires["clj"]
	if !ok || len(cljReqs) != 1 || cljReqs[0] != "clojure.string" {
		t.Errorf("expected requires {clj: [clojure.string]}, got %v", ns.Requires)
	}
}

func TestInitRequestJSON(t *testing.T) {
	req := InitRequest{
		Type:          "init",
		DepsEdnPath:   "/repo/deps.edn",
		RepositoryDir: "/repo",
		DepsRepoTag:   "@deps",
		Aliases:       []string{"dev", "test"},
	}
	data, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var decoded map[string]interface{}
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if decoded["type"] != "init" {
		t.Errorf("expected type init, got %v", decoded["type"])
	}
	if decoded["deps_edn_path"] != "/repo/deps.edn" {
		t.Errorf("expected deps_edn_path /repo/deps.edn, got %v", decoded["deps_edn_path"])
	}
}
