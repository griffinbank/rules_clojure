// Package clojureconfig collects Gazelle directives for the Clojure plugin.
// It holds raw directive values per package (rel → kv-map) and supports the
// parent-fallback walk Gazelle's per-package config model implies. Semantic
// interpretation of resolved values — basis assembly, label generation,
// effective rule shape — belongs in the Clojure server; the trivial value-
// string normalization needed for directive consumption (alias parsing,
// trim-empty) lives here because every Go-side caller needs it.
package clojureconfig

import (
	"path"
	"strings"
)

// Directive name constants used in BUILD file comments.
const (
	ClojureExtensionDirective = "clojure_enabled"
	ClojureDepsEdn            = "clojure_deps_edn"
	ClojureDepsRepo           = "clojure_deps_repo"
	ClojureAliases            = "clojure_aliases"

	// rootModuleName is auto-discovered from MODULE.bazel; it isn't a
	// user-facing directive but is stored under the same map.
	rootModuleName = "_root_module_name"
)

// Configs maps package-relative paths to their raw directive values.
// Lookup goes through Effective() which walks ancestors for inheritance.
type Configs map[string]map[string]string

// Effective returns the value for `key` at `rel`, falling back to ancestor
// rels (parent dirs) and finally `dflt` when no override was set.
func (cs Configs) Effective(rel, key, dflt string) string {
	for {
		if v, ok := cs[rel][key]; ok {
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
func (cs Configs) ExtensionEnabled(rel string) bool {
	switch cs.Effective(rel, ClojureExtensionDirective, "true") {
	case "false":
		return false
	default:
		return true
	}
}

// DepsRepo returns the deps repo tag for rel — default "@deps" at the root,
// overridden by `# gazelle:clojure_deps_repo @other_deps` in any ancestor.
func (cs Configs) DepsRepo(rel string) string {
	return cs.Effective(rel, ClojureDepsRepo, "@deps")
}

// DepsEdn returns the workspace-relative deps.edn path captured at the root
// during the initial Configure pass. Auto-discovered if no directive set.
func (cs Configs) DepsEdn() string {
	return cs.Effective("", ClojureDepsEdn, "")
}

// RootModuleName returns the bzlmod module(name=...) value, captured during
// the root Configure pass by reading MODULE.bazel. Used to canonicalize
// self-referencing labels (`@<root>//foo` → `@@//foo`) so generated rules
// agree with the apparent-name view Bazel exposes to consumers.
func (cs Configs) RootModuleName() string {
	return cs.Effective("", rootModuleName, "")
}

// SetRootModuleName stashes the discovered bzlmod root module name on the
// root config.
func (cs Configs) SetRootModuleName(name string) {
	if _, ok := cs[""]; !ok {
		cs[""] = map[string]string{}
	}
	cs[""][rootModuleName] = name
}

// Aliases returns the parsed alias list from the root-level
// `# gazelle:clojure_aliases` directive. Per-package alias overrides are
// not supported (aliases feed the basis, which is computed once at init).
func (cs Configs) Aliases() []string {
	return ParseAliases(cs.Effective("", ClojureAliases, ""))
}

// Set stores a raw directive value on `rel`. Empty values are treated as
// "unset" so a child config doesn't accidentally override its parent with
// the zero value — Effective() falls through to the next ancestor.
func (cs Configs) Set(rel, key, value string) {
	if value == "" {
		return
	}
	if _, ok := cs[rel]; !ok {
		cs[rel] = map[string]string{}
	}
	cs[rel][key] = value
}

// AllDirectives returns the directive names Gazelle should recognize from
// `# gazelle:<name> <value>` comments in BUILD files.
func AllDirectives() []string {
	return []string{
		ClojureExtensionDirective,
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
