// generate.go translates per-package {kind, attrs} rule specs from the bb
// parser into Gazelle *rule.Rule. All rule construction decisions live on
// the bb side (src/rules_clojure/gazelle_server.bb); this file is the
// wire-format translator + Gazelle integration layer.
package gazelle

import (
	"fmt"
	"log"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/bazelbuild/bazel-gazelle/language"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

// validRuleKinds is the closed set of kinds buildRule will emit.
var validRuleKinds = func() map[clojureparser.RuleKind]bool {
	m := make(map[clojureparser.RuleKind]bool, len(clojureparser.AllRuleKinds))
	for _, k := range clojureparser.AllRuleKinds {
		m[k] = true
	}
	return m
}()

// clojureSourceExts are the .clj/.cljs/.cljc extensions. .js is handled
// separately (it doesn't contribute to subdir-rollup tracking).
var clojureSourceExts = map[string]bool{
	".clj":  true,
	".cljs": true,
	".cljc": true,
}

// isClojureExt returns true for any extension this plugin emits rules for.
func isClojureExt(ext string) bool {
	return clojureSourceExts[ext] || ext == ".js"
}

// pathUnder returns the first candidate that equals or is a parent directory
// of rel, or "" if none match.
func pathUnder(rel string, candidates []string) string {
	for _, c := range candidates {
		if c == rel || strings.HasPrefix(rel, c+"/") {
			return c
		}
	}
	return ""
}

// subdirHasClojureFiles returns true when absDir recursively contains any
// .clj/.cljs/.cljc file. Fatals on a walk error so a misconfigured tree
// can't silently look empty.
func subdirHasClojureFiles(absDir string) bool {
	found := false
	walkErr := filepath.WalkDir(absDir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return fmt.Errorf("subdir walk %s: %w", path, err)
		}
		if !d.IsDir() && clojureSourceExts[filepath.Ext(path)] {
			found = true
			return filepath.SkipAll
		}
		return nil
	})
	if walkErr != nil {
		log.Fatalf("clojure: %v\n"+
			"hint: an unreadable subtree would otherwise be reported as empty, "+
			"causing Gazelle to delete rules for directories that have content.", walkErr)
	}
	return found
}

// buildRule translates one bb-emitted RuleSpec into a Gazelle *rule.Rule.
// Returns an error (not a panic) so callers can fail the whole run loudly
// instead of silently skipping rules.
func buildRule(spec clojureparser.RuleSpec) (*rule.Rule, error) {
	if !validRuleKinds[spec.Kind] {
		return nil, fmt.Errorf("unknown rule kind %q (expected one of %v)", spec.Kind, clojureparser.AllRuleKinds)
	}
	nameRaw, ok := spec.Attrs["name"]
	if !ok {
		return nil, fmt.Errorf("rule of kind %q missing :name attr", spec.Kind)
	}
	name, ok := nameRaw.(string)
	if !ok {
		return nil, fmt.Errorf("rule of kind %q has non-string :name (%T)", spec.Kind, nameRaw)
	}
	r := rule.NewRule(string(spec.Kind), name)

	// Sort for stable textual output across runs.
	keys := make([]string, 0, len(spec.Attrs))
	for k := range spec.Attrs {
		if k != "name" {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	for _, key := range keys {
		if err := applyAttr(r, key, spec.Attrs[key]); err != nil {
			return nil, fmt.Errorf("rule %q attr %q: %w", name, key, err)
		}
	}
	return r, nil
}

// applyAttr sets one JSON-decoded attr on r. Returns an error on unsupported
// value shapes so misencodings fail loudly.
func applyAttr(r *rule.Rule, key string, v interface{}) error {
	switch val := v.(type) {
	case nil:
		return fmt.Errorf("nil value (Clojure must not emit nil attrs; coerce to absent or empty)")
	case string:
		r.SetAttr(key, val)
	case bool:
		r.SetAttr(key, val)
	case float64:
		// JSON numbers always decode to float64; coerce integer-valued ones
		// back to int so Bazel int attrs round-trip exactly.
		if val != math.Trunc(val) {
			return fmt.Errorf("non-integer float %v for attr %q", val, key)
		}
		r.SetAttr(key, int(val))
	case []interface{}:
		strs := make([]string, 0, len(val))
		for i, item := range val {
			s, ok := item.(string)
			if !ok {
				return fmt.Errorf("element %d is %T, expected string", i, item)
			}
			strs = append(strs, s)
		}
		r.SetAttr(key, strs)
	case map[string]interface{}:
		// Bazel's string_dict attr type (e.g. clojure_test :env).
		strDict := make(map[string]string, len(val))
		for k, v := range val {
			s, ok := v.(string)
			if !ok {
				return fmt.Errorf("dict-valued attr: value at key %q is %T, expected string", k, v)
			}
			strDict[k] = s
		}
		r.SetAttr(key, strDict)
	default:
		return fmt.Errorf("unsupported attr type %T", v)
	}
	return nil
}

func (l *clojureLang) GenerateRules(args language.GenerateArgs) language.GenerateResult {
	switch raw := args.Config.Exts[languageName].(type) {
	case nil:
		return language.GenerateResult{}
	case *clojureconfig.Configs:
		if !raw.ExtensionEnabled(args.Rel) {
			return language.GenerateResult{}
		}
	default:
		log.Fatalf("clojure: args.Config.Exts[%q] is %T; expected *clojureconfig.Configs (plugin conflict?)", languageName, raw)
	}

	session := l.session
	if session == nil {
		log.Fatalf("clojure: parser not started for %s: no deps.edn at "+
			"workspace root. Add deps.edn or set `# gazelle:clojure_deps_edn <path>` "+
			"in the root BUILD file.", args.Rel)
	}

	if pathUnder(args.Rel, session.depsIndex.IgnorePaths) != "" {
		return language.GenerateResult{}
	}
	if pathUnder(args.Rel, session.depsIndex.SourcePaths) == "" {
		return language.GenerateResult{}
	}

	files := make([]string, 0, len(args.RegularFiles))
	for _, f := range args.RegularFiles {
		if isClojureExt(filepath.Ext(f)) {
			files = append(files, f)
		}
	}

	// hasClojureContent is populated bottom-up (Gazelle visits children
	// before parents); the on-disk walk is a defensive fallback.
	var clojureSubdirPaths []string
	for _, sub := range args.Subdirs {
		subRel := filepath.Join(args.Rel, sub)
		if pathUnder(subRel, session.depsIndex.SourcePaths) == "" {
			continue
		}
		has, cached := l.hasClojureContent[subRel]
		if !cached {
			has = subdirHasClojureFiles(filepath.Join(args.Config.RepoRoot, subRel))
		}
		if has {
			clojureSubdirPaths = append(clojureSubdirPaths, subRel)
		}
	}
	sort.Strings(clojureSubdirPaths)

	if len(files) == 0 && len(clojureSubdirPaths) == 0 {
		return language.GenerateResult{}
	}

	// Parse must halt the run on failure: an empty GenerateResult for a
	// previously-rule-bearing package would silently delete every rule.
	resp, err := session.runner.Parse(clojureparser.ParseRequest{
		Dir:                args.Rel,
		Files:              files,
		ClojureSubdirPaths: clojureSubdirPaths,
	})
	if err != nil {
		log.Fatalf("clojure: parse %s: %v\n"+
			"hint: parser subprocess likely died; see stderr above.", args.Rel, err)
	}

	var gen []*rule.Rule
	var imports []interface{}
	hasClojureLibrary := false

	for i := range resp.Namespaces {
		ns := &resp.Namespaces[i]
		for _, spec := range ns.Rules {
			r, err := buildRule(spec)
			if err != nil {
				log.Fatalf("clojure: %s/%s: %v", args.Rel, ns.File, err)
			}
			// Only clojure_library rules carry an imports payload: Resolve
			// short-circuits on the others.
			var imp interface{}
			if spec.Kind == clojureparser.KindClojureLibrary {
				imp = ns
				hasClojureLibrary = true
				l.ruleNs[ruleNsKey{pkg: args.Rel, name: r.Name()}] = ns.Ns
			}
			gen = append(gen, r)
			imports = append(imports, imp)
		}
	}

	for _, spec := range resp.RollupRules {
		r, err := buildRule(spec)
		if err != nil {
			log.Fatalf("clojure: %s rollup: %v", args.Rel, err)
		}
		gen = append(gen, r)
		imports = append(imports, nil)
	}

	// JS-only groups don't count for the parent's rollup (matches the
	// .clj/.cljs/.cljc-only on-disk check in subdirHasClojureFiles).
	l.hasClojureContent[args.Rel] = hasClojureLibrary || len(clojureSubdirPaths) > 0

	return language.GenerateResult{
		Gen:     gen,
		Empty:   nil,
		Imports: imports,
	}
}

