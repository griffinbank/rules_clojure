// Package clojureconfig collects Gazelle directives for the Clojure plugin.
// It holds raw directive values per package (rel → kv-map) and supports
// Gazelle's parent-fallback walk. Semantic interpretation of resolved
// values — dep-index assembly, label generation, effective rule shape —
// belongs in the bb server; only the trivial value-string normalization
// (alias parsing, trim-empty) lives here.
package clojureconfig

import (
	"path"
	"strings"
)

// Directive name constants used in BUILD file comments.
const (
	ClojureEnabledDirective = "clojure_enabled"
	ClojureDepsEdn          = "clojure_deps_edn"
	ClojureDepsRepo         = "clojure_deps_repo"
	ClojureAliases          = "clojure_aliases"
)

// Configs holds raw directive values per package, with parent-fallback
// inheritance via Effective(). The inner map is unexported so callers
// can't bypass Set's empty-value guard (an empty value would otherwise
// shadow an inherited parent value).
type Configs struct {
	rels map[string]map[string]string
}

// New returns a fresh Configs. Use this in tests and inside Configure;
// don't construct a zero-value Configs{} (its inner map is nil).
func New() *Configs {
	return &Configs{rels: map[string]map[string]string{}}
}

// Effective returns the value for `key` at `rel`, falling back to ancestor
// rels (parent dirs) and finally `dflt` when no override was set.
func (cs *Configs) Effective(rel, key, dflt string) string {
	for {
		if v, ok := cs.rels[rel][key]; ok {
			return v
		}
		if rel == "" {
			return dflt
		}
		rel = path.Dir(rel)
		if rel == "." {
			rel = ""
		}
	}
}

// ExtensionEnabled returns whether the Clojure extension is active for rel,
// honouring per-package `# gazelle:clojure_enabled false` overrides.
// Defaults to true at the root.
func (cs *Configs) ExtensionEnabled(rel string) bool {
	return cs.Effective(rel, ClojureEnabledDirective, "true") != "false"
}

// DepsRepo returns the deps repo tag for rel — default "@deps" at the root,
// overridden by `# gazelle:clojure_deps_repo @other_deps` in any ancestor.
func (cs *Configs) DepsRepo(rel string) string {
	return cs.Effective(rel, ClojureDepsRepo, "@deps")
}

// DepsEdn returns the absolute deps.edn path stored at the root during the
// initial Configure pass (configure.go joins with c.RepoRoot before calling
// Set). Auto-discovered if no directive set.
func (cs *Configs) DepsEdn() string {
	return cs.Effective("", ClojureDepsEdn, "")
}

// Aliases returns the parsed alias list from the root-level
// `# gazelle:clojure_aliases` directive. Per-package alias overrides
// aren't supported — aliases feed the source-path set and the bb
// server's dep index, both computed once at init.
func (cs *Configs) Aliases() []string {
	return ParseAliases(cs.Effective("", ClojureAliases, ""))
}

// Set stores a raw directive value on `rel`. Empty values are treated as
// "unset" so a child config doesn't accidentally override its parent with
// the zero value — Effective() falls through to the next ancestor.
func (cs *Configs) Set(rel, key, value string) {
	if value == "" {
		return
	}
	if _, ok := cs.rels[rel]; !ok {
		cs.rels[rel] = map[string]string{}
	}
	cs.rels[rel][key] = value
}

// AllDirectives returns the directive names Gazelle should recognize from
// `# gazelle:<name> <value>` comments in BUILD files.
func AllDirectives() []string {
	return []string{
		ClojureEnabledDirective,
		ClojureDepsEdn,
		ClojureDepsRepo,
		ClojureAliases,
	}
}

// ParseAliases splits a comma-separated alias string, trims whitespace,
// and drops empty entries. strings.Split("", ",") returns [""], and a
// naive split of "dev, test" leaves a leading space on the second entry
// — both yield malformed keywords on the Clojure side.
func ParseAliases(s string) []string {
	out := []string{}
	for _, raw := range strings.Split(s, ",") {
		if trimmed := strings.TrimSpace(raw); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}
