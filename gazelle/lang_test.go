package gazelle

import (
	"os"
	"os/exec"
	"strings"
	"testing"
)

// TestApparentLoadsPicksUpRemappedModule pins that the rules_clojure module
// is referenced via its apparent name in MODULE.bazel — so a user who
// `bazel_dep(name = "rules_clojure", repo_name = "my_rules_clojure")` still
// gets a working load() line.
func TestApparentLoadsPicksUpRemappedModule(t *testing.T) {
	l := &clojureLang{}
	loads := l.ApparentLoads(func(canonical string) string {
		if canonical == "rules_clojure" {
			return "my_rules_clojure"
		}
		return canonical
	})
	if len(loads) == 0 {
		t.Fatalf("ApparentLoads returned no LoadInfo entries")
	}
	wantPrefix := "@my_rules_clojure//"
	found := false
	for _, li := range loads {
		if strings.HasPrefix(li.Name, wantPrefix) {
			found = true
			// Sanity check: the clojure_library symbol must be in the load.
			hasLib := false
			for _, s := range li.Symbols {
				if s == "clojure_library" {
					hasLib = true
					break
				}
			}
			if !hasLib {
				t.Errorf("LoadInfo %q does not include clojure_library symbol; got %v", li.Name, li.Symbols)
			}
			break
		}
	}
	if !found {
		t.Errorf("ApparentLoads did not include a LoadInfo prefixed with %q; got %v", wantPrefix, loads)
	}
}

// TestApparentLoadsFatalsOnMissingModule exercises the log.Fatalf path
// when rules_clojure is not declared in MODULE.bazel (apparent name is "").
// Uses the re-exec pattern from TestGenerateRulesFatalsOnParserDeath.
func TestApparentLoadsFatalsOnMissingModule(t *testing.T) {
	if os.Getenv("APPARENT_FATAL_CHILD") == "1" {
		l := &clojureLang{}
		l.ApparentLoads(func(string) string { return "" })
		t.Errorf("ApparentLoads returned without log.Fatalf — expected fatal exit on missing module")
		return
	}
	cmd := exec.Command(os.Args[0], "-test.run=TestApparentLoadsFatalsOnMissingModule", "-test.v")
	cmd.Env = append(os.Environ(), "APPARENT_FATAL_CHILD=1")
	out, err := cmd.CombinedOutput()
	if exitErr, ok := err.(*exec.ExitError); ok && !exitErr.Success() {
		wantPhrase := "rules_clojure not declared in MODULE.bazel"
		if !strings.Contains(string(out), wantPhrase) {
			t.Errorf("child exited %v but stderr didn't contain %q:\n%s", err, wantPhrase, out)
		}
		return
	}
	t.Fatalf("subprocess exited cleanly (err=%v); expected log.Fatalf exit. Output:\n%s", err, out)
}

// TestLoadsDelegatesToApparentLoads pins that the deprecated Loads() method
// returns the same shape as ApparentLoads with an identity callback.
func TestLoadsDelegatesToApparentLoads(t *testing.T) {
	l := &clojureLang{}
	loads := l.Loads()
	apparent := l.ApparentLoads(func(s string) string { return s })
	if len(loads) != len(apparent) {
		t.Fatalf("Loads len = %d; ApparentLoads len = %d; expected equal", len(loads), len(apparent))
	}
	for i, li := range loads {
		if li.Name != apparent[i].Name {
			t.Errorf("Loads[%d].Name = %q; ApparentLoads[%d].Name = %q", i, li.Name, i, apparent[i].Name)
		}
	}
}
