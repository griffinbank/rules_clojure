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
// src/rules_clojure/gazelle_server.clj.
const (
	MsgTypeInit  = "init"
	MsgTypeParse = "parse"
	MsgTypeError = "error"
)

// Platform is one of the values reported by the parser. Promoting from a
// plain string aids self-documentation in resolve.go: `ns.Requires[platform]`
// vs `ns.Requires[string]` makes it clear what the keys are.
type Platform = string

// Platform enum values. Mirror gen_build.clj's clj-path? / cljs-path? / etc.
const (
	PlatformClj  Platform = "clj"
	PlatformCljs Platform = "cljs"
	PlatformJs   Platform = "js"
)

// InitRequest is the one-shot configuration sent at startup. The Clojure
// server reads deps.edn, resolves transitive Maven coords against
// RepositoryDir, and replies with InitResponse.
type InitRequest struct {
	Type           string   `json:"type"`
	DepsEdnPath    string   `json:"deps_edn_path"`    // absolute path to deps.edn
	RepositoryDir  string   `json:"repository_dir"`   // local Maven repo (~/.m2/repository)
	DepsRepoTag    string   `json:"deps_repo_tag"`    // e.g. "@deps" — prefix for resolved labels
	RootModuleName string   `json:"root_module_name"` // bzlmod module(name=...) for self-label canonicalization
	Aliases        []string `json:"aliases"`          // deps.edn :aliases to activate
}

// InitResponse carries the resolved dep graph and configured paths. All
// slice fields are emitted as JSON arrays (never null) so the Go caller can
// range over them without a nil check.
//
// Note on DepNsLabels: the server emits a map keyed by platform string;
// individual platform keys may be absent when the basis has no jars
// providing that platform. resolve.go's lookup is guarded with a comma-ok
// so a missing key becomes a no-op rather than a panic, but downstream
// require-resolution will silently fail to find the dep — review the basis
// if a require that should resolve doesn't.
type InitResponse struct {
	Type string `json:"type"`
	// DepNsLabels maps platform → namespace-string → Bazel-label. Platform
	// keys correspond to MsgType-style strings ("clj", "cljs"); inner keys
	// are namespace symbols rendered as strings ("foo.bar").
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

// RuleSpec is one fully-formed Bazel rule as decided by the Clojure-side
// `gen-build/ns-rules`. Kind names the macro to emit ("clojure_library",
// "clojure_test", "clojure_binary", "java_library"); Attrs carries the
// kwargs to set on it. The `deps` attr is intentionally absent — Gazelle's
// Resolve phase fills that in once the cross-package index is built.
type RuleSpec struct {
	Kind  string                 `json:"kind"`
	Attrs map[string]interface{} `json:"attrs"`
}

// NamespaceInfo is the parser's per-namespace report. Multiple files with
// the same basename (e.g. webauthn.clj + webauthn.cljs) collapse into one
// NamespaceInfo with Platforms reflecting which extensions were present.
//
// The :deps on each clojure_library Rule already include the static parts
// gen-build/ns-rules merges (org_clojure_clojure + clojure-library-args +
// ns-library-meta + pre-resolved import-deps / gen-class-deps). Resolve
// seeds depSet from those and adds only what needs the cross-package
// index (intra-repo lookup, DepNsLabels per-platform, per-target
// deps_bazel overrides).
//
// Error/Ns are not formally a sum type. Either Error is non-empty (fatal,
// caller must abort) or Ns names a real namespace. JS-only groups are the
// exception: Ns is empty and Requires is absent because Resolve never
// reads them for kinds other than clojure_library.
type NamespaceInfo struct {
	// Ns is the namespace symbol from the (ns ...) form, e.g. "foo.core".
	// Empty for JS-only groups; never read in that case (Resolve short-
	// circuits on Kind != "clojure_library").
	Ns string `json:"ns"`
	// File is the basename of the primary file: the .clj if present, else
	// the .cljs (Clojure groups), or the first .js (JS-only groups). Other
	// files in the group are not surfaced individually.
	File string `json:"file"`
	// Requires maps platform → required-namespace list. Per-platform entries
	// may be absent if that platform has no requires; the map itself is
	// absent for JS-only groups (Resolve never reads it for java_library).
	Requires map[Platform][]string `json:"requires,omitempty"`
	// Platforms is a non-empty subset of {"clj","cljs","js"} naming which
	// platforms this group produces output for. Set by the server's
	// ext->platforms helper (clj/cljs/cljc → clj/cljs sets) and hardcoded
	// to ["js"] for JS-only groups.
	Platforms []Platform `json:"platforms"`
	// Rules is the set of Bazel rules to emit for this namespace group,
	// pre-constructed by gen-build/ns-rules. In practice ns-rules emits
	// at most one clojure_library per group; Resolve attaches deps to
	// every clojure_library it sees.
	Rules []RuleSpec `json:"rules"`
	// Error is non-empty when the Clojure server failed to parse this file
	// group. The caller MUST treat this as fatal — silently dropping the
	// entry would cause Gazelle to delete existing rules.
	Error string `json:"error,omitempty"`
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
// Pass a single argument for a script/binary, or multiple for e.g. "java", "-jar", "path.jar".
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
	// Allow up to 10 MB lines (dep_ns_labels can be large).
	scanner.Buffer(make([]byte, 0, 64*1024), 10*1024*1024)

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
	req.Type = MsgTypeInit
	data, err := r.exchange(req)
	if err != nil {
		return nil, err
	}
	var resp initEnvelope
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, fmt.Errorf("clojureparser: malformed init response (%w): %s", err, jsonErrorContext(data, err))
	}
	if resp.Type == MsgTypeError {
		return nil, fmt.Errorf("clojureparser: server error: %s", resp.Message)
	}
	return &resp.InitResponse, nil
}

// Parse sends a parse request and reads the parse response. Same
// concurrency / dead-on-failure / single-unmarshal-with-error-envelope
// semantics as Init.
func (r *Runner) Parse(req ParseRequest) (*ParseResponse, error) {
	req.Type = MsgTypeParse
	data, err := r.exchange(req)
	if err != nil {
		return nil, err
	}
	var resp parseEnvelope
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, fmt.Errorf("clojureparser: malformed parse response (%w): %s", err, jsonErrorContext(data, err))
	}
	if resp.Type == MsgTypeError {
		return nil, fmt.Errorf("clojureparser: server error: %s", resp.Message)
	}
	return &resp.ParseResponse, nil
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
// parse error. When err is a *json.SyntaxError, the window is centered on
// the byte offset; otherwise it falls back to a leading prefix.
func jsonErrorContext(data []byte, err error) string {
	const window = 200
	if syn, ok := err.(*json.SyntaxError); ok {
		off := int(syn.Offset)
		start := off - window/2
		if start < 0 {
			start = 0
		}
		end := start + window
		if end > len(data) {
			end = len(data)
		}
		prefix := "..."
		if start == 0 {
			prefix = ""
		}
		suffix := "..."
		if end == len(data) {
			suffix = ""
		}
		return fmt.Sprintf("at offset %d: %s%s%s", off, prefix, string(data[start:end]), suffix)
	}
	if len(data) <= window {
		return string(data)
	}
	return string(data[:window]) + "..."
}

// Shutdown closes stdin, then waits up to shutdownTimeout for the subprocess
// to exit. Kills the process on timeout. Idempotent and safe on nil receiver.
func (r *Runner) Shutdown() {
	if r == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.shutdown {
		return
	}
	r.shutdown = true

	if r.stdin != nil {
		_ = r.stdin.Close()
	}
	if r.cmd == nil {
		return
	}

	// Wait in a goroutine so we can apply a timeout. cmd.Wait is not
	// cancellable, but Process.Kill unblocks it.
	done := make(chan error, 1)
	go func() { done <- r.cmd.Wait() }()
	select {
	case err := <-done:
		// Clean EOF exit on stdin close is common (server reads EOF and
		// stops). Don't spam the log unless the exit was unexpected.
		if err != nil && r.cmd.ProcessState != nil && !r.cmd.ProcessState.Success() {
			log.Printf("clojureparser: subprocess exit: %v (state=%s)", err, r.cmd.ProcessState)
		}
	case <-time.After(shutdownTimeout):
		log.Printf("clojureparser: subprocess did not exit within %s after stdin close, killing", shutdownTimeout)
		if r.cmd.Process != nil {
			if killErr := r.cmd.Process.Kill(); killErr != nil {
				log.Printf("clojureparser: kill failed: %v (process may already be reaped)", killErr)
			}
		}
		<-done // reap the killed process
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
		return nil, fmt.Errorf("clojureparser: unexpected EOF from subprocess%s", exit)
	}
	// Bytes() returns a slice backed by the scanner's internal buffer that
	// is invalidated by the next Scan() call. The only callers are Init
	// and Parse, both of which json.Unmarshal the slice before releasing
	// the mutex (no opportunity for another Scan()), so the lifetime is
	// safe by construction of the call sites. receive() stays unexported
	// so the contract can't be violated from outside the package.
	return r.stdout.Bytes(), nil
}

// processExitInfo returns a short " (exit=...)" suffix if the subprocess has
// already exited, or "" otherwise. Called from receive() and Shutdown(),
// both of which hold the mutex; the comment is for readers of the call
// site, not a contract about external use.
func (r *Runner) processExitInfo() string {
	if r.cmd == nil || r.cmd.ProcessState == nil {
		return ""
	}
	return fmt.Sprintf(" (exit=%s)", r.cmd.ProcessState)
}
