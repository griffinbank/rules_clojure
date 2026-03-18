package clojureparser

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
)

// Request/response types for the JSON-line protocol with the Clojure parser subprocess.

type InitRequest struct {
	Type          string   `json:"type"`
	DepsEdnPath   string   `json:"deps_edn_path"`
	RepositoryDir string   `json:"repository_dir"`
	DepsRepoTag   string   `json:"deps_repo_tag"`
	Aliases       []string `json:"aliases"`
}

type InitResponse struct {
	Type        string                       `json:"type"`
	DepNsLabels map[string]map[string]string `json:"dep_ns_labels"`
	DepsBazel   map[string]interface{}       `json:"deps_bazel"`
	IgnorePaths []string                     `json:"ignore_paths"`
	SourcePaths []string                     `json:"source_paths"`
}

type ParseRequest struct {
	Type  string   `json:"type"`
	Dir   string   `json:"dir"`
	Files []string `json:"files"`
}

type NamespaceInfo struct {
	Ns           string                 `json:"ns"`
	File         string                 `json:"file"`
	Requires     map[string][]string    `json:"requires"`
	ImportDeps   []string               `json:"import_deps"`
	GenClassDeps []string               `json:"gen_class_deps"`
	NsMeta       map[string]interface{} `json:"ns_meta"`
	Platforms    []string               `json:"platforms"`
}

type ParseResponse struct {
	Type       string          `json:"type"`
	Namespaces []NamespaceInfo `json:"namespaces"`
}

// Runner manages a Clojure parser subprocess, communicating over JSON lines on stdin/stdout.
type Runner struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout *bufio.Scanner
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

// Init sends an init request and reads the init response.
func (r *Runner) Init(req InitRequest) (*InitResponse, error) {
	req.Type = "init"
	if err := r.send(req); err != nil {
		return nil, err
	}
	data, err := r.receive()
	if err != nil {
		return nil, err
	}
	var resp InitResponse
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, fmt.Errorf("clojureparser: unmarshal init response: %w", err)
	}
	if err := checkError(data); err != nil {
		return nil, err
	}
	return &resp, nil
}

// Parse sends a parse request and reads the parse response.
func (r *Runner) Parse(req ParseRequest) (*ParseResponse, error) {
	req.Type = "parse"
	if err := r.send(req); err != nil {
		return nil, err
	}
	data, err := r.receive()
	if err != nil {
		return nil, err
	}
	var resp ParseResponse
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, fmt.Errorf("clojureparser: unmarshal parse response: %w", err)
	}
	if err := checkError(data); err != nil {
		return nil, err
	}
	return &resp, nil
}

// checkError checks if a JSON response is an error message from the subprocess.
func checkError(data []byte) error {
	var envelope struct {
		Type    string `json:"type"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal(data, &envelope); err != nil {
		return nil
	}
	if envelope.Type == "error" {
		return fmt.Errorf("clojureparser: server error: %s", envelope.Message)
	}
	return nil
}

// Shutdown closes stdin and waits for the subprocess to exit.
func (r *Runner) Shutdown() {
	if r.stdin != nil {
		r.stdin.Close()
	}
	if r.cmd != nil {
		if err := r.cmd.Wait(); err != nil {
			log.Printf("clojureparser: subprocess exit: %v", err)
		}
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
		return nil, fmt.Errorf("clojureparser: unexpected EOF from subprocess")
	}
	return r.stdout.Bytes(), nil
}
