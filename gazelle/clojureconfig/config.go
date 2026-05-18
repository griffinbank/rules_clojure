// Package clojureconfig collects Gazelle directives for the Clojure plugin.
// Holds raw directive values per package with parent-fallback inheritance.
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

// Configs holds raw directive values per package with parent-fallback
// inheritance via Effective().
type Configs struct {
	rels map[string]map[string]string
}

// New returns a Configs ready for use. The zero-value Configs is unsafe
// (nil inner map).
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

// DepsRepo returns the deps repo tag for rel (default "@deps" at the root,
// overridable by `# gazelle:clojure_deps_repo @other_deps` in any ancestor).
func (cs *Configs) DepsRepo(rel string) string {
	return cs.Effective(rel, ClojureDepsRepo, "@deps")
}

// DepsEdn returns the absolute deps.edn path.
func (cs *Configs) DepsEdn() string {
	return cs.Effective("", ClojureDepsEdn, "")
}

// Aliases returns the parsed root-level alias list.
func (cs *Configs) Aliases() []string {
	return ParseAliases(cs.Effective("", ClojureAliases, ""))
}

// Set stores a raw directive value on `rel`. An empty value is dropped
// (a child config setting "" would otherwise shadow the parent).
func (cs *Configs) Set(rel, key, value string) {
	if value == "" {
		return
	}
	if _, ok := cs.rels[rel]; !ok {
		cs.rels[rel] = map[string]string{}
	}
	cs.rels[rel][key] = value
}

// AllDirectives returns directive names recognised in BUILD-file comments.
func AllDirectives() []string {
	return []string{
		ClojureEnabledDirective,
		ClojureDepsEdn,
		ClojureDepsRepo,
		ClojureAliases,
	}
}

// ParseAliases splits a comma-separated alias string, trimming whitespace
// and dropping empties.
func ParseAliases(s string) []string {
	out := []string{}
	for _, raw := range strings.Split(s, ",") {
		if trimmed := strings.TrimSpace(raw); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}
