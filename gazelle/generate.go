// generate.go implements Gazelle's RuleGenerator interface (GenerateRules,
// Fix — Kinds + Loads live in lang.go). Gazelle calls GenerateRules(args)
// per package after Configure. This file filters the package's files by
// extension, computes which subdirs have Clojure content for the rollup
// (`__clj_lib` / `__clj_files`), RPCs the Clojure parser with the file list
// + subdir paths, and translates each returned `{kind, attrs}` rule spec
// into a Gazelle *rule.Rule via buildRule + applyAttr.
//
// All rule construction decisions (AOT, test attrs, library shape, JS
// fallback, rollup composition) live Clojure-side in rules-clojure.gen-build.
// This file is the wire-format translator + the Gazelle integration layer;
// it doesn't decide what a clojure_library should look like.
package gazelle

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/language"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/griffinbank/rules_clojure/gazelle/clojureconfig"
	"github.com/griffinbank/rules_clojure/gazelle/clojureparser"
)

// validRuleKinds is the closed set of rule kinds the Clojure server is
// allowed to emit. A Kind outside this set means the server is buggy or
// out-of-sync with this Go plugin; emitting it via rule.NewRule would
// produce a BUILD file Bazel can't load.
var validRuleKinds = map[string]bool{
	"clojure_library": true,
	"clojure_test":    true,
	"clojure_binary":  true,
	"java_library":    true,
	"filegroup":       true,
}

// clojureSourceExts are the Clojure-side extensions (.clj/.cljs/.cljc).
// JS is handled separately because it groups into java_library targets, not
// into the recursive subdir-has-Clojure rollup check.
var clojureSourceExts = map[string]bool{
	".clj":  true,
	".cljs": true,
	".cljc": true,
}

// isClojureExt returns true for any extension this plugin generates rules
// for, including .js (rolled up into java_library next to clojure_library).
func isClojureExt(ext string) bool {
	if clojureSourceExts[ext] {
		return true
	}
	return ext == ".js"
}

// sourceRoot finds the source path from initResp.SourcePaths that is a prefix
// of rel. Returns empty string if none match.
func sourceRoot(sourcePaths []string, rel string) string {
	for _, sp := range sourcePaths {
		if sp == rel || strings.HasPrefix(rel, sp+"/") {
			return sp
		}
	}
	return ""
}

// subdirHasClojureFiles checks if a directory recursively contains any
// .clj/.cljs/.cljc files. Matches gen_srcs behavior which only includes
// subdirs in __clj_lib rollup if they contain Clojure sources.
//
// A WalkDir error (permission denied, broken symlink, etc.) is fatal so a
// misconfigured tree doesn't silently look "empty" and let Gazelle delete
// rules for a directory that genuinely contains Clojure files.
func subdirHasClojureFiles(absDir string) bool {
	found := false
	walkErr := filepath.WalkDir(absDir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return fmt.Errorf("subdir walk %s: %w", path, err)
		}
		if found {
			return filepath.SkipAll
		}
		if !d.IsDir() && clojureSourceExts[filepath.Ext(path)] {
			found = true
			return filepath.SkipAll
		}
		return nil
	})
	if walkErr != nil {
		log.Fatalf("clojure: %v\n"+
			"hint: an unreadable subtree would otherwise be reported as 'empty', "+
			"causing Gazelle to delete rules for directories that have content.", walkErr)
	}
	return found
}

// buildRule translates a Clojure-side {kind, attrs} spec into a Gazelle
// *rule.Rule. The Clojure server (gen-build/ns-rules) is authoritative on
// what rules to emit and which attrs to set; this is purely a wire-format
// translator. Returns an error rather than panicking on malformed specs so
// the caller can fail the whole run loudly (vs. silently skipping rules).
func buildRule(spec clojureparser.RuleSpec) (*rule.Rule, error) {
	if !validRuleKinds[spec.Kind] {
		return nil, fmt.Errorf("unknown rule kind %q (expected one of clojure_library/clojure_test/clojure_binary/java_library/filegroup)", spec.Kind)
	}
	nameRaw, ok := spec.Attrs["name"]
	if !ok {
		return nil, fmt.Errorf("rule of kind %q missing :name attr", spec.Kind)
	}
	name, ok := nameRaw.(string)
	if !ok {
		return nil, fmt.Errorf("rule of kind %q has non-string :name (%T)", spec.Kind, nameRaw)
	}
	r := rule.NewRule(spec.Kind, name)

	// Apply attrs in sorted order so the generated rule's textual form is
	// stable across runs.
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

// applyAttr maps one JSON-decoded attr value to rule.SetAttr. JSON numbers
// always decode to float64, so we coerce integer-valued Bazel attrs back
// here. Returns an error on unknown shapes so misencodings fail loudly.
func applyAttr(r *rule.Rule, key string, v interface{}) error {
	switch val := v.(type) {
	case nil:
		return fmt.Errorf("nil value (Clojure must not emit nil attrs — coerce to absent or empty)")
	case string:
		r.SetAttr(key, val)
	case bool:
		r.SetAttr(key, val)
	case float64:
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
		// String-keyed string-valued maps map to Bazel's string_dict attr type
		// (e.g. clojure_test :env). All-string is the only shape gen-build
		// emits — fail loudly on anything else so a future Clojure-side change
		// to a dict with non-string values surfaces here.
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
	// Get config, check extension enabled. A non-Configs value here means
	// another plugin claimed our languageName slot — fail loudly rather than
	// silently overwriting it later in Configure.
	switch raw := args.Config.Exts[languageName].(type) {
	case nil:
		return language.GenerateResult{}
	case clojureconfig.Configs:
		if !raw.ExtensionEnabled(args.Rel) {
			return language.GenerateResult{}
		}
	default:
		log.Fatalf("clojure: args.Config.Exts[%q] is %T; expected clojureconfig.Configs (plugin conflict?)", languageName, raw)
	}

	// Parser session must be running. startParser log.Fatalfs on failure, so
	// reaching here with a nil session means the extension is enabled but
	// startParser was never called — fail loudly rather than silently
	// producing empty results.
	session := l.session
	if session == nil {
		log.Fatalf("clojure: parser not started for %s — "+
			"check that Configure ran for the root package", args.Rel)
	}

	// Only process directories under source paths; skip ignored paths.
	for _, ip := range session.initResp.IgnorePaths {
		if args.Rel == ip || strings.HasPrefix(args.Rel, ip+"/") {
			return language.GenerateResult{}
		}
	}
	if sourceRoot(session.initResp.SourcePaths, args.Rel) == "" {
		return language.GenerateResult{}
	}

	// Filter files for relevant extensions.
	files := make([]string, 0, len(args.RegularFiles))
	for _, f := range args.RegularFiles {
		if isClojureExt(filepath.Ext(f)) {
			files = append(files, f)
		}
	}

	// Compute the subdir paths whose rollups this package should aggregate.
	// Gazelle visits children before parents so `hasClojureContent[subRel]`
	// is populated by the time we ask. Falls back to the on-disk walk for
	// any subdir we haven't visited yet (defensive — should not happen in
	// normal Gazelle ordering).
	var clojureSubdirPaths []string
	for _, sub := range args.Subdirs {
		subRel := filepath.Join(args.Rel, sub)
		if sourceRoot(session.initResp.SourcePaths, subRel) == "" {
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

	// Nothing to do when this package has no direct Clojure/JS files AND no
	// Clojure-bearing subdirs. Returning early before the parse RPC saves a
	// round-trip; the rollup would be empty for this package anyway.
	if len(files) == 0 && len(clojureSubdirPaths) == 0 {
		return language.GenerateResult{}
	}

	// Parse the directory. A subprocess transport failure must halt the run:
	// returning empty GenerateResult{} for previously-rule-bearing packages
	// would silently delete every clojure_library/clojure_test rule.
	resp, err := session.runner.Parse(clojureparser.ParseRequest{
		Dir:                args.Rel,
		Files:              files,
		ClojureSubdirPaths: clojureSubdirPaths,
	})
	if err != nil {
		log.Fatalf("clojure: parse %s: %v\n"+
			"hint: parser subprocess likely died — see stderr above.", args.Rel, err)
	}

	var gen []*rule.Rule
	var imports []interface{}
	hasClojureLibrary := false

	for i := range resp.Namespaces {
		ns := &resp.Namespaces[i]
		if ns.Error != "" {
			log.Fatalf("clojure: parse failed for %s/%s: %s\n"+
				"hint: see parser subprocess stderr above for the stack trace.",
				args.Rel, ns.File, ns.Error)
		}
		for _, spec := range ns.Rules {
			r, err := buildRule(spec)
			if err != nil {
				log.Fatalf("clojure: %s/%s: %v", args.Rel, ns.File, err)
			}
			// Resolve uses NamespaceInfo to look up requires/import_deps/etc.
			// Only clojure_library rules participate in dep resolution.
			var imp interface{}
			if spec.Kind == "clojure_library" {
				imp = ns
				hasClojureLibrary = true
				l.ruleNs[args.Rel+":"+r.Name()] = ns.Ns
			}
			gen = append(gen, r)
			imports = append(imports, imp)
		}
	}

	// Translate the rollup specs the Clojure server produced. They already
	// incorporate `clojureSubdirPaths` from the request, so no further
	// bookkeeping is needed on the Go side.
	for _, spec := range resp.RollupRules {
		r, err := buildRule(spec)
		if err != nil {
			log.Fatalf("clojure: %s rollup: %v", args.Rel, err)
		}
		gen = append(gen, r)
		imports = append(imports, nil)
	}

	// Record whether this package contributes Clojure content for the
	// parent's rollup. JS-only groups don't count (matches
	// subdirHasClojureFiles' .clj/.cljs/.cljc-only on-disk check).
	l.hasClojureContent[args.Rel] = hasClojureLibrary || len(clojureSubdirPaths) > 0

	return language.GenerateResult{
		Gen:     gen,
		Empty:   nil,
		Imports: imports,
	}
}

func (*clojureLang) Fix(_ *config.Config, _ *rule.File) {}
