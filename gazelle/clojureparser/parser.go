// Package clojureparser is the Go client for the long-running Clojure
// parser subprocess. It defines the JSON-line wire protocol (request and
// response types) and Runner, which owns the subprocess lifecycle: start,
// Init, Parse, Shutdown. Calls serialize on an internal mutex; any I/O
// error marks the runner dead so subsequent calls short-circuit instead
// of racing a corpse.
package clojureparser

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"
)

// shutdownTimeout caps how long Shutdown waits for the subprocess to exit
// after stdin close. If exceeded, the process is killed.
const shutdownTimeout = 10 * time.Second

// ErrRunnerDead is returned by Init/Parse after any prior send/receive
// failure to short-circuit subsequent calls that would race a dead subprocess.
var ErrRunnerDead = errors.New("clojureparser: runner is dead (prior failure)")

// Request/response types for the JSON-line protocol with the Clojure parser subprocess.

// Message type discriminators. Keep in sync with handle-request in
// src/rules_clojure/gazelle_server.bb.
const (
	MsgTypeInit  = "init"
	MsgTypeParse = "parse"
	MsgTypeError = "error"
)

// Platform is one of the values reported by the parser. Promoting from a
// plain string aids self-documentation in resolve.go: `ns.Requires[platform]`
// vs `ns.Requires[string]` makes it clear what the keys are.
type Platform = string

// Platform enum values. Mirror src/rules_clojure/gazelle_server.bb's
// clj-path? / cljs-path? / js-path?.
const (
	PlatformClj  Platform = "clj"
	PlatformCljs Platform = "cljs"
	PlatformJs   Platform = "js"
)

// InitRequest is the one-shot configuration sent at startup. The bb
// server reads deps.edn, parses @deps/BUILD.bazel for the resolved
// jar/label graph, and replies with InitResponse.
type InitRequest struct {
	Type        string   `json:"type"`
	DepsEdnPath string   `json:"deps_edn_path"` // absolute path to deps.edn
	DepsRepoTag string   `json:"deps_repo_tag"` // e.g. "@deps" — prefix the bb server uses on labels it emits
	Aliases     []string `json:"aliases"`       // deps.edn :aliases to activate (extra-paths only)
}

// InitResponse carries the resolved dep graph and configured paths.
// Slice fields are always emitted as JSON arrays (never null).
// DepNsLabels platform keys ("clj", "cljs") may be absent when no jars
// provide that platform; resolve.go uses comma-ok for safe lookup.
type InitResponse struct {
	Type string `json:"type"`
	// DepNsLabels maps platform → namespace-string → Bazel-label. Platform
	// keys are Platform* constants ("clj", "cljs"); inner keys are
	// namespace symbols rendered as strings ("foo.bar").
	DepNsLabels map[string]map[string]string `json:"dep_ns_labels"`
	// DepsBazel mirrors the :bazel key from deps.edn (allows per-target
	// override of generated deps). Other fields under :bazel are decoded
	// and silently ignored — only :deps is consumed by resolve.go.
	DepsBazel DepsBazel `json:"deps_bazel"`
	// IgnorePaths are package-relative paths Gazelle should not generate
	// rules for. SourcePaths is the same shape — paths included for
	// generation, mirroring deps.edn :paths after alias merging.
	IgnorePaths []string `json:"ignore_paths"`
	SourcePaths []string `json:"source_paths"`
}

// DepsBazel carries the per-target dep overrides from deps.edn's :bazel
// map. The Clojure server emits the whole :bazel map; this type only
// names the field Resolve consumes (Deps), so other keys decode but stay
// inert. Resolve adds the entries in Deps[<label>].Deps to the target's
// :deps set after intra-repo + DepNsLabels resolution.
type DepsBazel struct {
	Deps map[string]DepsBazelTarget `json:"deps"`
}

// DepsBazelTarget is the per-target override block. Deps is a list of
// fully-qualified Bazel labels that should be appended to the target's
// generated :deps.
type DepsBazelTarget struct {
	Deps []string `json:"deps"`
}

// ParseRequest asks the server to parse one directory's Clojure files.
// Files are basename-only (no Dir prefix). Dir is normally the
// package-relative path; the server also accepts absolute paths (used by
// tests / programmatic callers that don't have a workspace root).
//
// ClojureSubdirPaths lists workspace-relative subdir paths that
// transitively contain Clojure content — populated bottom-up by Gazelle's
// walk (via clojureLang.hasClojureContent). The server uses these to build
// the __clj_lib / __clj_files rollup rules so the rule construction stays
// in the Clojure side.
type ParseRequest struct {
	Type               string   `json:"type"`
	Dir                string   `json:"dir"`
	Files              []string `json:"files"`
	ClojureSubdirPaths []string `json:"clojure_subdir_paths,omitempty"`
}

// RuleSpec is one fully-formed Bazel rule as decided by the bb server's
// ns-rules. Kind names the macro ("clojure_library", "clojure_test",
// "clojure_binary", "java_library", "filegroup"); Attrs carries kwargs.
// `deps` may be present as a seed (org_clojure_clojure + import-deps +
// gen-class-deps); Resolve adds the cross-package-index pieces on top.
type RuleSpec struct {
	Kind  string                 `json:"kind"`
	Attrs map[string]interface{} `json:"attrs"`
}

// NamespaceInfo is the parser's per-namespace report. Files sharing a
// basename (e.g. webauthn.clj + webauthn.cljs) collapse into one entry,
// with Platforms reflecting which extensions were present.
//
// Two shapes: a Clojure group (Ns set, Requires present, Platforms ⊆
// {clj,cljs}) and a JS-only group (Ns="", Requires absent,
// Platforms=["js"]). Rules.Kind discriminates downstream — Resolve
// only attaches deps to clojure_library rules.
//
// Per-file parse failures are logged to the parser's stderr and the
// group is omitted from Namespaces. Gazelle's default update semantics
// then leave any pre-existing rules in that package untouched (the Go
// language Empty implementation returns nil). Protocol-level failures
// (malformed envelope, subprocess crash) are fatal in Go and surface
// via Parse's error return.
type NamespaceInfo struct {
	// Ns is the namespace symbol from the (ns ...) form ("foo.core"), or
	// empty for JS-only groups.
	Ns string `json:"ns"`
	// File is the primary file name (.clj > .cljs > .js).
	File string `json:"file"`
	// Requires maps platform → required-namespace list. Absent platforms
	// mean no requires on that side.
	Requires map[Platform][]string `json:"requires,omitempty"`
	// Platforms is non-empty: a subset of {"clj","cljs","js"}.
	Platforms []Platform `json:"platforms"`
	// Rules is the set of Bazel rules to emit for this group, pre-built
	// by the bb server's ns-rules.
	Rules []RuleSpec `json:"rules"`
}

// ParseResponse wraps the per-group NamespaceInfo entries plus the
// directory-level rollup rules (__clj_lib / __clj_files). The rollup specs
// already incorporate ClojureSubdirPaths from the request, so the Go side
// just translates them into *rule.Rule without further bookkeeping.
type ParseResponse struct {
	Type        string          `json:"type"`
	Namespaces  []NamespaceInfo `json:"namespaces"`
	RollupRules []RuleSpec      `json:"rollup_rules,omitempty"`
}

// Runner manages a Clojure parser subprocess, communicating over JSON lines
// on stdin/stdout. Safe for concurrent use: Init, Parse, and Shutdown all
// serialize on an internal mutex.
type Runner struct {
	cmd      *exec.Cmd
	stdin    io.WriteCloser
	stdout   *bufio.Scanner
	mu       sync.Mutex // serializes all operations (exchange round-trips and Shutdown)
	dead     bool       // set after any I/O failure; subsequent calls short-circuit
	shutdown bool       // set by Shutdown to make it idempotent
}

// New starts the parser subprocess and returns a Runner.
func New(binaryPath string, args ...string) (*Runner, error) {
	cmd := exec.Command(binaryPath, args...)
	cmd.Stderr = os.Stderr

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, fmt.Errorf("clojureparser: stdin pipe: %w", err)
	}

	stdoutPipe, err := cmd.StdoutPipe()
	if err != nil {
		stdin.Close()
		return nil, fmt.Errorf("clojureparser: stdout pipe: %w", err)
	}

	if err := cmd.Start(); err != nil {
		stdin.Close()
		return nil, fmt.Errorf("clojureparser: start %s: %w", binaryPath, err)
	}

	scanner := bufio.NewScanner(stdoutPipe)
	// Allow up to 64 MB lines. dep_ns_labels can be large (a moderate-sized
	// monorepo with ~3000 ns entries serializes around 250 KB); larger or
	// deeper dep graphs may approach the limit. If a line exceeds this,
	// bufio.Scanner returns ErrTooLong as "unexpected EOF" — exchange()
	// catches the resulting decode failure and surfaces it.
	scanner.Buffer(make([]byte, 0, 64*1024), 64*1024*1024)

	return &Runner{
		cmd:    cmd,
		stdin:  stdin,
		stdout: scanner,
	}, nil
}

// Init sends an init request and reads the init response. Holds the
// internal mutex for the full round-trip; marks the runner dead on any I/O
// failure so subsequent calls short-circuit via ErrRunnerDead. Single
// json.Unmarshal pass into a Message-carrying superset of InitResponse:
// when the server replies with {type:error,message:...}, the Message
// field captures it; on a normal init response Message is "" and the rest
// of the struct decodes typed fields directly.
func (r *Runner) Init(req InitRequest) (*InitResponse, error) {
	if req.DepsEdnPath != "" && !filepath.IsAbs(req.DepsEdnPath) {
		return nil, fmt.Errorf("clojureparser: init: deps_edn_path must be absolute, got %q", req.DepsEdnPath)
	}
	req.Type = MsgTypeInit
	resp, err := decodeEnvelope[initEnvelope](r, req, MsgTypeInit)
	if err != nil {
		return nil, err
	}
	return &resp.InitResponse, nil
}

// Parse sends a parse request and reads the parse response. Same
// concurrency / dead-on-failure / single-unmarshal-with-error-envelope
// semantics as Init.
func (r *Runner) Parse(req ParseRequest) (*ParseResponse, error) {
	req.Type = MsgTypeParse
	resp, err := decodeEnvelope[parseEnvelope](r, req, MsgTypeParse)
	if err != nil {
		return nil, err
	}
	// Sanity-check the Platforms non-empty invariant. An empty slice would
	// silently produce a clojure_library with no resolved deps; the bb side
	// always reports at least one platform per group, so a zero-length one
	// means the wire shape drifted.
	for i, ns := range resp.Namespaces {
		if len(ns.Platforms) == 0 {
			return nil, fmt.Errorf("clojureparser: parse response namespace %d (file=%q) has empty platforms — bb wire shape drift", i, ns.File)
		}
	}
	return &resp.ParseResponse, nil
}

// envelope is anything with a discriminator and an error message. Both
// init/parse envelopes embed their typed payload, so the caller just
// pulls out the inner response.
type envelope interface {
	envelopeType() string
	envelopeMessage() string
}

func (e initEnvelope) envelopeType() string     { return e.Type }
func (e initEnvelope) envelopeMessage() string  { return e.Message }
func (e parseEnvelope) envelopeType() string    { return e.Type }
func (e parseEnvelope) envelopeMessage() string { return e.Message }

// decodeEnvelope shares the marshal -> exchange -> unmarshal -> error-check
// dance between Init and Parse. opName tags malformed-response and server-
// error messages so grepping logs for "init failed" / "parse failed" works.
func decodeEnvelope[T envelope](r *Runner, req interface{}, opName string) (T, error) {
	var zero T
	data, err := r.exchange(req)
	if err != nil {
		return zero, err
	}
	var resp T
	if err := json.Unmarshal(data, &resp); err != nil {
		return zero, fmt.Errorf("clojureparser: %s failed: malformed response (%w): %s", opName, err, jsonErrorContext(data, err))
	}
	if resp.envelopeType() == MsgTypeError {
		return zero, fmt.Errorf("clojureparser: %s failed: server error: %s", opName, resp.envelopeMessage())
	}
	return resp, nil
}

// initEnvelope / parseEnvelope decode the {type, message?, ...payload} wire
// shape in a single json.Unmarshal pass. The Message field is captured
// from error responses; on success it's empty and InitResponse/Parse-
// Response carries the typed payload. Embedding rather than composing
// keeps the JSON shape identical to the bare response types — Go's
// encoding/json walks embedded struct fields as if they were inline.
type initEnvelope struct {
	Message string `json:"message,omitempty"`
	InitResponse
}

type parseEnvelope struct {
	Message string `json:"message,omitempty"`
	ParseResponse
}

// exchange sends a request and reads one response line, holding the mutex
// across the full round-trip. Marks the runner dead on any I/O failure so
// subsequent calls short-circuit instead of racing a corpse.
func (r *Runner) exchange(req interface{}) ([]byte, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.dead {
		return nil, ErrRunnerDead
	}
	if err := r.send(req); err != nil {
		r.dead = true
		return nil, err
	}
	data, err := r.receive()
	if err != nil {
		r.dead = true
		return nil, err
	}
	return data, nil
}

// jsonErrorContext extracts a readable window from `data` around a JSON
// parse error. Centres on the offset for SyntaxError / UnmarshalTypeError;
// otherwise falls back to a leading prefix.
func jsonErrorContext(data []byte, err error) string {
	const window = 200
	switch e := err.(type) {
	case *json.SyntaxError:
		return jsonErrorWindow(data, int(e.Offset), window, "")
	case *json.UnmarshalTypeError:
		return jsonErrorWindow(data, int(e.Offset), window, fmt.Sprintf(" (field %q expects %s, got %s)", e.Field, e.Type, e.Value))
	}
	if len(data) <= window {
		return string(data)
	}
	return string(data[:window]) + "..."
}

func jsonErrorWindow(data []byte, off, window int, extra string) string {
	start := max(off-window/2, 0)
	end := min(start+window, len(data))
	prefix := "..."
	if start == 0 {
		prefix = ""
	}
	suffix := "..."
	if end == len(data) {
		suffix = ""
	}
	return fmt.Sprintf("at offset %d%s: %s%s%s", off, extra, prefix, string(data[start:end]), suffix)
}

// Shutdown closes stdin, then waits up to shutdownTimeout for the subprocess
// to exit. Kills the process on timeout. Idempotent and safe on nil receiver.
// Returns a non-nil error if the subprocess exited non-zero — callers
// (AfterResolvingDeps) treat that as a mid-run fatal that the request/response
// envelope never surfaced.
func (r *Runner) Shutdown() error {
	if r == nil {
		return nil
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.shutdown {
		return nil
	}
	r.shutdown = true
	// dead short-circuits subsequent Parse calls — once we've started
	// shutting down, the subprocess is going away.
	r.dead = true

	if r.stdin != nil {
		if err := r.stdin.Close(); err != nil {
			log.Printf("clojureparser: stdin close: %v (subprocess may already have exited)", err)
		}
	}
	if r.cmd == nil {
		return nil
	}

	// Wait in a goroutine so we can apply a timeout. cmd.Wait is not
	// cancellable, but Process.Kill unblocks it.
	done := make(chan error, 1)
	go func() { done <- r.cmd.Wait() }()
	select {
	case err := <-done:
		// Clean EOF exit on stdin close is common (server reads EOF and
		// stops). A non-zero exit means the subprocess hit a fatal mid-run
		// that the wire envelope didn't catch.
		if err != nil && r.cmd.ProcessState != nil && !r.cmd.ProcessState.Success() {
			return fmt.Errorf("clojureparser: subprocess exited non-zero (%s): %w", r.cmd.ProcessState, err)
		}
		return nil
	case <-time.After(shutdownTimeout):
		log.Printf("clojureparser: subprocess did not exit within %s after stdin close, killing", shutdownTimeout)
		var killErr error
		if r.cmd.Process != nil {
			if err := r.cmd.Process.Kill(); err != nil {
				killErr = err
				log.Printf("clojureparser: kill failed: %v (process may already be reaped)", err)
			}
		}
		<-done // reap the killed process
		if killErr != nil {
			return fmt.Errorf("clojureparser: subprocess did not exit within %s; kill also failed: %w", shutdownTimeout, killErr)
		}
		return fmt.Errorf("clojureparser: subprocess did not exit within %s and was killed", shutdownTimeout)
	}
}

func (r *Runner) send(v interface{}) error {
	data, err := json.Marshal(v)
	if err != nil {
		return fmt.Errorf("clojureparser: marshal: %w", err)
	}
	data = append(data, '\n')
	if _, err := r.stdin.Write(data); err != nil {
		return fmt.Errorf("clojureparser: write: %w", err)
	}
	return nil
}

func (r *Runner) receive() ([]byte, error) {
	if !r.stdout.Scan() {
		if err := r.stdout.Err(); err != nil {
			return nil, fmt.Errorf("clojureparser: read: %w", err)
		}
		// Include subprocess exit info when available — debugging "why did my
		// BUILD silently empty?" is far harder without it.
		exit := r.processExitInfo()
		return nil, fmt.Errorf("clojureparser: unexpected EOF from subprocess%s — see subprocess stderr above for details", exit)
	}
	// Bytes() returns a slice backed by the scanner's internal buffer that
	// is invalidated by the next Scan() call. Copy so callers can hold the
	// slice safely; Init/Parse run rarely so the extra allocation is free.
	src := r.stdout.Bytes()
	dst := make([]byte, len(src))
	copy(dst, src)
	return dst, nil
}

// processExitInfo returns " (exit=...)" if the subprocess has already
// exited, "" otherwise. Callers hold the mutex.
func (r *Runner) processExitInfo() string {
	if r.cmd == nil || r.cmd.ProcessState == nil {
		return ""
	}
	return fmt.Sprintf(" (exit=%s)", r.cmd.ProcessState)
}
