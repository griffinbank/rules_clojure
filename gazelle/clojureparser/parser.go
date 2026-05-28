// Package clojureparser is the Go client for the long-running Clojure
// parser subprocess. It defines the JSON-line wire protocol and Runner,
// which owns the subprocess lifecycle (Init, Parse, Shutdown).
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
	"slices"
	"strings"
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

// Platform identifies a Clojure dialect surface: one of "clj", "cljs", "js".
type Platform string

// Platform enum values. Mirror src/rules_clojure/gazelle_server.bb's
// clj-path? / cljs-path? / js-path?.
const (
	PlatformClj  Platform = "clj"
	PlatformCljs Platform = "cljs"
	PlatformJs   Platform = "js"
)

// InitRequest is the one-shot configuration sent at startup.
type InitRequest struct {
	DepsEdnPath string   `json:"deps_edn_path"` // absolute path to deps.edn
	DepsRepoTag string   `json:"deps_repo_tag"` // prefix the bb server uses on emitted labels (e.g. "@deps")
	Aliases     []string `json:"aliases"`       // deps.edn :aliases to activate (extra-paths only)
}

// DepNsLabels maps namespace strings to Bazel labels, per platform.
type DepNsLabels struct {
	Clj  map[string]string `json:"clj"`
	Cljs map[string]string `json:"cljs"`
}

// InitResponse carries the resolved dep graph and configured paths.
// Slice fields are always non-null JSON arrays.
type InitResponse struct {
	Type        string      `json:"type"`
	DepNsLabels DepNsLabels `json:"dep_ns_labels"`
	DepsBazel   DepsBazel   `json:"deps_bazel"`
	IgnorePaths []string    `json:"ignore_paths"` // package-relative paths Gazelle skips
	SourcePaths []string    `json:"source_paths"` // package-relative paths Gazelle scans
}

// DepsBazel carries the per-target dep overrides from deps.edn's :bazel map.
// Only :deps is consumed; other keys decode but stay inert.
type DepsBazel struct {
	Deps map[string]DepsBazelTarget `json:"deps"`
}

// DepsBazelTarget is the per-target override block.
type DepsBazelTarget struct {
	Deps []string `json:"deps"`
}

// ParseRequest asks the server to parse one directory's Clojure files.
// Dir is package-relative (absolute is also accepted). Files are
// basename-only. ClojureSubdirPaths are workspace-relative subdirs that
// transitively contain Clojure content; the server uses them to build
// the __clj_lib / __clj_files rollup rules.
type ParseRequest struct {
	Dir                string   `json:"dir"`
	Files              []string `json:"files"`
	ClojureSubdirPaths []string `json:"clojure_subdir_paths,omitempty"`
}

// RuleKind names a Bazel macro the bb server may emit.
type RuleKind string

const (
	KindClojureLibrary RuleKind = "clojure_library"
	KindClojureTest    RuleKind = "clojure_test"
	KindClojureBinary  RuleKind = "clojure_binary"
	KindJavaLibrary    RuleKind = "java_library"
	KindFilegroup      RuleKind = "filegroup"
)

// AllRuleKinds is the closed set of values RuleSpec.Kind may take.
var AllRuleKinds = []RuleKind{
	KindClojureLibrary,
	KindClojureTest,
	KindClojureBinary,
	KindJavaLibrary,
	KindFilegroup,
}

// RuleSpec is one fully-formed Bazel rule.
type RuleSpec struct {
	Kind  RuleKind               `json:"kind"`
	Attrs map[string]interface{} `json:"attrs"`
}

// NamespaceInfo is the parser's per-namespace report. Two valid shapes:
// a Clojure group (Ns set, Requires present, Platforms ⊆ {clj,cljs})
// or a JS-only group (Ns="", Requires absent, Platforms=["js"]).
type NamespaceInfo struct {
	Ns        string                `json:"ns"`                 // (ns ...) symbol, or "" for JS-only groups
	File      string                `json:"file"`               // primary file basename (.clj > .cljs > .js)
	Requires  map[Platform][]string `json:"requires,omitempty"` // per-platform required ns list
	Platforms []Platform            `json:"platforms"`          // non-empty subset of {"clj","cljs","js"}
	Rules     []RuleSpec            `json:"rules"`
}

// ParseResponse wraps per-group NamespaceInfo entries plus the
// directory-level rollup rules (__clj_lib / __clj_files).
type ParseResponse struct {
	Type        string          `json:"type"`
	Namespaces  []NamespaceInfo `json:"namespaces"`
	RollupRules []RuleSpec      `json:"rollup_rules,omitempty"`
}

const stderrRingBufferBytes = 8 * 1024

// stderrRing retains the last stderrRingBufferBytes of bytes written to
// it, for inclusion in processExitInfo on subprocess failure.
type stderrRing struct {
	mu  sync.Mutex
	buf []byte // most recent suffix, up to stderrRingBufferBytes
}

func (s *stderrRing) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	combined := append(s.buf, p...)
	if len(combined) > stderrRingBufferBytes {
		combined = combined[len(combined)-stderrRingBufferBytes:]
	}
	s.buf = combined
	return len(p), nil
}

func (s *stderrRing) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return string(s.buf)
}

// Runner manages a Clojure parser subprocess over JSON lines on
// stdin/stdout. Safe for concurrent use.
type Runner struct {
	cmd      *exec.Cmd
	stdin    io.WriteCloser
	stdout   *bufio.Scanner
	stderr   *stderrRing
	mu       sync.Mutex // serializes all operations (exchange round-trips and Shutdown)
	dead     bool       // set after any I/O failure; subsequent calls short-circuit
	shutdown bool       // set by Shutdown to make it idempotent
}

// New starts the parser subprocess and returns a Runner.
func New(binaryPath string, args ...string) (*Runner, error) {
	cmd := exec.Command(binaryPath, args...)
	ring := &stderrRing{}
	cmd.Stderr = io.MultiWriter(os.Stderr, ring)

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
	// 64 MB line cap; dep_ns_labels can run ~250 KB on a moderate monorepo
	// with ~3000 ns entries.
	scanner.Buffer(make([]byte, 0, 64*1024), 64*1024*1024)

	return &Runner{
		cmd:    cmd,
		stdin:  stdin,
		stdout: scanner,
		stderr: ring,
	}, nil
}

// Init sends an init request and returns the init response.
func (r *Runner) Init(req InitRequest) (*InitResponse, error) {
	if req.DepsEdnPath != "" && !filepath.IsAbs(req.DepsEdnPath) {
		return nil, fmt.Errorf("clojureparser: init: deps_edn_path must be absolute, got %q", req.DepsEdnPath)
	}
	if !strings.HasPrefix(req.DepsRepoTag, "@") {
		return nil, fmt.Errorf("clojureparser: init: deps_repo_tag must start with '@', got %q", req.DepsRepoTag)
	}
	wire := taggedRequest[InitRequest]{Type: MsgTypeInit, Payload: req}
	data, err := r.exchange(wire)
	if err != nil {
		return nil, err
	}
	var resp initEnvelope
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, fmt.Errorf("clojureparser: init failed: malformed response (%w): %s", err, jsonErrorContext(data, err))
	}
	if resp.Type == MsgTypeError {
		return nil, fmt.Errorf("clojureparser: init failed: server error: %s", resp.Message)
	}
	if resp.DepNsLabels.Clj == nil {
		return nil, fmt.Errorf("clojureparser: init response dep_ns_labels.clj missing (bb wire shape drift)")
	}
	if resp.DepNsLabels.Cljs == nil {
		return nil, fmt.Errorf("clojureparser: init response dep_ns_labels.cljs missing (bb wire shape drift)")
	}
	return &resp.InitResponse, nil
}

// Parse sends a parse request and returns the parse response.
func (r *Runner) Parse(req ParseRequest) (*ParseResponse, error) {
	wire := taggedRequest[ParseRequest]{Type: MsgTypeParse, Payload: req}
	data, err := r.exchange(wire)
	if err != nil {
		return nil, err
	}
	var resp parseEnvelope
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, fmt.Errorf("clojureparser: parse failed: malformed response (%w): %s", err, jsonErrorContext(data, err))
	}
	if resp.Type == MsgTypeError {
		return nil, fmt.Errorf("clojureparser: parse failed: server error: %s", resp.Message)
	}
	for i := range resp.Namespaces {
		if err := validateNamespaceShape(&resp.Namespaces[i]); err != nil {
			return nil, fmt.Errorf("clojureparser: parse response namespace %d: %w", i, err)
		}
	}
	return &resp.ParseResponse, nil
}

// validateNamespaceShape returns an error when ns mixes the Clojure-group
// and JS-only shapes (see NamespaceInfo doc).
func validateNamespaceShape(ns *NamespaceInfo) error {
	if len(ns.Platforms) == 0 {
		return fmt.Errorf("file=%q: empty platforms (bb wire shape drift)", ns.File)
	}
	jsOnly := len(ns.Platforms) == 1 && ns.Platforms[0] == PlatformJs
	if jsOnly {
		if ns.Ns != "" {
			return fmt.Errorf("file=%q: js-only group must have empty Ns, got %q", ns.File, ns.Ns)
		}
		return nil
	}
	if ns.Ns == "" {
		return fmt.Errorf("file=%q: Clojure group must have non-empty Ns (got platforms=%v)", ns.File, ns.Platforms)
	}
	for _, p := range ns.Platforms {
		if p != PlatformClj && p != PlatformCljs {
			return fmt.Errorf("file=%q: Clojure group has non-Clojure platform %q", ns.File, p)
		}
	}
	return nil
}

// taggedRequest prepends `"type":...,` to Payload's JSON object on marshal.
type taggedRequest[T any] struct {
	Type    string
	Payload T
}

func (t taggedRequest[T]) MarshalJSON() ([]byte, error) {
	payload, err := json.Marshal(t.Payload)
	if err != nil {
		return nil, err
	}
	if len(payload) < 2 || payload[0] != '{' {
		return nil, fmt.Errorf("clojureparser: taggedRequest payload must marshal to a JSON object, got %s", payload)
	}
	prefix := fmt.Sprintf(`{"type":%q,`, t.Type)
	if len(payload) == 2 {
		return []byte(strings.TrimSuffix(prefix, ",") + "}"), nil
	}
	out := make([]byte, 0, len(prefix)+len(payload)-1)
	out = append(out, prefix...)
	out = append(out, payload[1:]...)
	return out, nil
}

// initEnvelope / parseEnvelope decode the {type, message?, ...payload}
// wire shape in one json.Unmarshal pass.
type initEnvelope struct {
	Message string `json:"message,omitempty"`
	InitResponse
}

type parseEnvelope struct {
	Message string `json:"message,omitempty"`
	ParseResponse
}

// exchange sends a request and reads one response line.
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

// jsonErrorContext renders a window from `data` around a JSON parse error.
func jsonErrorContext(data []byte, err error) string {
	const window = 200
	var off int
	var extra string
	switch e := err.(type) {
	case *json.SyntaxError:
		off = int(e.Offset)
	case *json.UnmarshalTypeError:
		off = int(e.Offset)
		extra = fmt.Sprintf(" (field %q expects %s, got %s)", e.Field, e.Type, e.Value)
	default:
		if len(data) <= window {
			return string(data)
		}
		return string(data[:window]) + "..."
	}
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

// Shutdown closes stdin and waits up to shutdownTimeout for the subprocess
// to exit; kills it on timeout. Idempotent and nil-receiver safe.
// Returns a non-nil error when the subprocess exited non-zero.
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
	r.dead = true

	if r.stdin != nil {
		if err := r.stdin.Close(); err != nil {
			log.Printf("clojureparser: stdin close: %v (subprocess may already have exited)", err)
		}
	}
	if r.cmd == nil {
		return nil
	}

	// cmd.Wait isn't cancellable; run it in a goroutine and apply the
	// timeout via select. Process.Kill unblocks Wait on timeout.
	done := make(chan error, 1)
	go func() { done <- r.cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil && r.cmd.ProcessState != nil && !r.cmd.ProcessState.Success() {
			return fmt.Errorf("clojureparser: subprocess exited non-zero (%s): %w", r.cmd.ProcessState, err)
		}
		if err != nil {
			log.Printf("clojureparser: subprocess exited cleanly but cmd.Wait returned: %v", err)
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
			if errors.Is(err, bufio.ErrTooLong) {
				return nil, fmt.Errorf("clojureparser: response exceeded the 64 MB line buffer; bump scanner.Buffer in New() if your dep graph is genuinely this large")
			}
			return nil, fmt.Errorf("clojureparser: read: %w", err)
		}
		exit := r.processExitInfo()
		return nil, fmt.Errorf("clojureparser: unexpected EOF from subprocess%s (see subprocess stderr above)", exit)
	}
	// Bytes() is invalidated by the next Scan(); clone so callers can hold it.
	return slices.Clone(r.stdout.Bytes()), nil
}

// processExitInfo returns " (exit=...; stderr tail: ...)" when the
// subprocess has already exited, "" otherwise.
func (r *Runner) processExitInfo() string {
	if r.cmd == nil || r.cmd.ProcessState == nil {
		return ""
	}
	tail := ""
	if r.stderr != nil {
		if s := strings.TrimRight(r.stderr.String(), "\n"); s != "" {
			tail = fmt.Sprintf("; stderr tail: %s", s)
		}
	}
	return fmt.Sprintf(" (exit=%s%s)", r.cmd.ProcessState, tail)
}
