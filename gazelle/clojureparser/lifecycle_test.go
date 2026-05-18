package clojureparser

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// stubScript creates a temporary executable shell script with the given
// content and returns its path. The script is removed on test cleanup.
func stubScript(t *testing.T, content string) string {
	t.Helper()
	if _, err := exec.LookPath("sh"); err != nil {
		t.Skip("sh not on PATH")
	}
	path := filepath.Join(t.TempDir(), "stub.sh")
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"+content), 0755); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestRunnerExchangeMarksDeadOnEOF(t *testing.T) {
	// Script exits immediately after reading one line. The first exchange
	// receives nothing back → unexpected EOF, runner.dead = true.
	stub := stubScript(t, "read first\n")
	runner, err := New(stub)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer runner.Shutdown()

	if _, err := runner.Init(InitRequest{DepsEdnPath: "/dev/null"}); err == nil {
		t.Fatal("first Init expected error from EOF on dying subprocess")
	}
	if !runner.dead {
		t.Error("runner.dead = false after exchange failure; want true")
	}

	// Second call must short-circuit on the dead flag, not race the corpse.
	_, err = runner.Parse(ParseRequest{Dir: "x"})
	if err != ErrRunnerDead {
		t.Errorf("second call err = %v; want ErrRunnerDead", err)
	}
}

func TestRunnerShutdownIdempotent(t *testing.T) {
	stub := stubScript(t, "cat\n")
	runner, err := New(stub)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	runner.Shutdown()
	runner.Shutdown() // must not panic, deadlock, or double-close
}

func TestRunnerShutdownOnNilReceiver(t *testing.T) {
	var r *Runner
	r.Shutdown() // must not panic
}

func TestInitRejectsMalformedJSON(t *testing.T) {
	stub := stubScript(t, "read line\n"+`printf 'not json at all\n'`+"\n")
	runner, err := New(stub)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer runner.Shutdown()
	_, err = runner.Init(InitRequest{DepsEdnPath: "/dev/null"})
	if err == nil {
		t.Fatal("Init accepted malformed JSON; want error")
	}
	if !strings.Contains(err.Error(), "init failed: malformed response") {
		t.Errorf("err = %q; want 'init failed: malformed response' marker", err.Error())
	}
}

func TestInitRejectsErrorEnvelope(t *testing.T) {
	stub := stubScript(t, "read line\n"+`printf '%s\n' '{"type":"error","message":"boom"}'`+"\n")
	runner, err := New(stub)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer runner.Shutdown()
	_, err = runner.Init(InitRequest{DepsEdnPath: "/dev/null"})
	if err == nil {
		t.Fatal("Init accepted error envelope; want error")
	}
	if !strings.Contains(err.Error(), "boom") {
		t.Errorf("err = %q; want 'boom' message", err.Error())
	}
}

func TestRunnerExchangeRoundTrip(t *testing.T) {
	// Script echoes a canned response after reading one request.
	resp := `{"type":"init","dep_ns_labels":{},"deps_bazel":{},"ignore_paths":[],"source_paths":[]}`
	stub := stubScript(t, "read line\n"+`printf '%s\n' '`+resp+`'`+"\n")
	runner, err := New(stub)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer runner.Shutdown()

	got, err := runner.Init(InitRequest{DepsEdnPath: "/dev/null"})
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	if got.Type != MsgTypeInit {
		t.Errorf("Type = %q; want %q", got.Type, MsgTypeInit)
	}
	// defer runner.Shutdown() calls cmd.Wait, so no explicit sleep needed.
}
