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
)

// Config holds Clojure-specific Gazelle configuration for a single package.
// Fields use pointer/zero-value semantics: unset values fall through to the
// parent config.
type Config struct {
	parent           *Config
	extensionEnabled *bool
	depsEdn          string
	depsRepo         string
	aliases          []string
}

// Configs maps package-relative paths to their Config.
type Configs map[string]*Config

// New returns a root Config with default values.
func New() *Config {
	enabled := true
	return &Config{
		extensionEnabled: &enabled,
		depsRepo:         "@deps",
	}
}

// NewChild returns a child Config that inherits unset values from c.
func (c *Config) NewChild() *Config {
	return &Config{parent: c}
}

// ExtensionEnabled returns whether the Clojure extension is active.
func (c *Config) ExtensionEnabled() bool {
	if c.extensionEnabled != nil {
		return *c.extensionEnabled
	}
	if c.parent != nil {
		return c.parent.ExtensionEnabled()
	}
	return true
}

// SetExtensionEnabled sets the enabled flag for this config level.
func (c *Config) SetExtensionEnabled(v bool) {
	c.extensionEnabled = &v
}

// DepsEdn returns the deps.edn path.
func (c *Config) DepsEdn() string {
	if c.depsEdn != "" {
		return c.depsEdn
	}
	if c.parent != nil {
		return c.parent.DepsEdn()
	}
	return ""
}

// SetDepsEdn sets the deps.edn path for this config level.
func (c *Config) SetDepsEdn(v string) {
	c.depsEdn = v
}

// DepsRepo returns the Bazel repository tag for resolved deps.
func (c *Config) DepsRepo() string {
	if c.depsRepo != "" {
		return c.depsRepo
	}
	if c.parent != nil {
		return c.parent.DepsRepo()
	}
	return "@deps"
}

// SetDepsRepo sets the deps repo tag for this config level.
func (c *Config) SetDepsRepo(v string) {
	c.depsRepo = v
}

// Aliases returns the deps.edn aliases to activate.
func (c *Config) Aliases() []string {
	if c.aliases != nil {
		return c.aliases
	}
	if c.parent != nil {
		return c.parent.Aliases()
	}
	return nil
}

// SetAliases sets the deps.edn aliases for this config level.
func (c *Config) SetAliases(v []string) {
	c.aliases = v
}

// ParentForPackage returns the Config for the nearest ancestor of rel.
// For "a/b/c" it checks "a/b", then "a", then "".
func (cs Configs) ParentForPackage(rel string) *Config {
	for rel != "" {
		rel = path.Dir(rel)
		if rel == "." {
			rel = ""
		}
		if cfg, ok := cs[rel]; ok {
			return cfg
		}
	}
	return nil
}

// AllDirectives returns all recognized directive names.
func AllDirectives() []string {
	return []string{
		ClojureExtensionDirective,
		ClojureDepsEdn,
		ClojureDepsRepo,
		ClojureAliases,
	}
}

// ParseAliases splits a comma-separated alias string.
func ParseAliases(s string) []string {
	return strings.Split(s, ",")
}
