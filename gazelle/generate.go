package gazelle

import (
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

var clojureExts = map[string]bool{
	".clj":  true,
	".cljs": true,
	".cljc": true,
	".js":   true,
}

func isClojureExt(ext string) bool {
	return clojureExts[ext]
}

func isTestFile(filename string) bool {
	return strings.Contains(filename, "_test.clj")
}

func filenameSansExt(filename string) string {
	return strings.TrimSuffix(filename, filepath.Ext(filename))
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

const metaKeyClojureLibrary = "bazel/clojure_library"
const metaKeyClojureTest = "bazel/clojure_test"

// clojureLibraryMeta extracts the "bazel/clojure_library" metadata map from a NamespaceInfo.
func clojureLibraryMeta(ns *clojureparser.NamespaceInfo) map[string]interface{} {
	raw, ok := ns.NsMeta[metaKeyClojureLibrary]
	if !ok {
		return nil
	}
	m, ok := raw.(map[string]interface{})
	if !ok {
		return nil
	}
	return m
}

// clojureTestMeta extracts the "bazel/clojure_test" metadata map from a NamespaceInfo.
func clojureTestMeta(ns *clojureparser.NamespaceInfo) map[string]interface{} {
	raw, ok := ns.NsMeta[metaKeyClojureTest]
	if !ok {
		return nil
	}
	m, ok := raw.(map[string]interface{})
	if !ok {
		return nil
	}
	return m
}

// aotEnabled returns whether AOT compilation should be enabled for a namespace.
// AOT is enabled by default for non-test .clj/.cljc files. Disabled if ns_meta
// has "bazel/clojure_library" with "aot": false.
func aotEnabled(ns *clojureparser.NamespaceInfo) bool {
	ext := filepath.Ext(ns.File)
	if ext != ".clj" && ext != ".cljc" {
		return false
	}
	if isTestFile(ns.File) {
		return false
	}
	if m := clojureLibraryMeta(ns); m != nil {
		if aot, ok := m["aot"]; ok {
			if b, ok := aot.(bool); ok {
				return b
			}
		}
	}
	return true
}

// subdirHasClojureFiles checks if a directory recursively contains any
// .clj/.cljs/.cljc files. Matches gen_srcs behavior which only includes
// subdirs in __clj_lib rollup if they contain Clojure sources.
func subdirHasClojureFiles(absDir string) bool {
	found := false
	filepath.WalkDir(absDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || found {
			return filepath.SkipDir
		}
		if !d.IsDir() {
			ext := filepath.Ext(path)
			if ext == ".clj" || ext == ".cljs" || ext == ".cljc" {
				found = true
				return filepath.SkipAll
			}
		}
		return nil
	})
	return found
}

func (l *clojureLang) GenerateRules(args language.GenerateArgs) language.GenerateResult {
	// Get config, check extension enabled.
	configs, _ := args.Config.Exts[languageName].(clojureconfig.Configs)
	if configs == nil {
		return language.GenerateResult{}
	}
	cfg := configs[args.Rel]
	if cfg == nil || !cfg.ExtensionEnabled() {
		return language.GenerateResult{}
	}

	// Parser must be running.
	if l.parser == nil {
		return language.GenerateResult{}
	}

	// Only process directories under source paths, skip ignored paths.
	if l.initResp != nil {
		for _, ip := range l.initResp.IgnorePaths {
			if args.Rel == ip || strings.HasPrefix(args.Rel, ip+"/") {
				return language.GenerateResult{}
			}
		}
		if sourceRoot(l.initResp.SourcePaths, args.Rel) == "" {
			return language.GenerateResult{}
		}
	}

	// Filter files for relevant extensions.
	var files []string
	for _, f := range args.RegularFiles {
		if isClojureExt(filepath.Ext(f)) {
			files = append(files, f)
		}
	}
	if len(files) == 0 {
		return language.GenerateResult{}
	}

	// Parse the directory.
	resp, err := l.parser.Parse(clojureparser.ParseRequest{
		Dir:   args.Rel,
		Files: files,
	})
	if err != nil {
		log.Printf("clojure: parse %s: %v", args.Rel, err)
		return language.GenerateResult{}
	}

	// Build namespace map keyed by filename for ordered iteration.
	nsByFile := make(map[string]*clojureparser.NamespaceInfo, len(resp.Namespaces))
	for i := range resp.Namespaces {
		nsByFile[resp.Namespaces[i].File] = &resp.Namespaces[i]
	}

	stripPrefix := sourceRoot(l.initResp.SourcePaths, args.Rel)

	var gen []*rule.Rule
	var imports []interface{}

	// Track per-file target names for rollup.
	var libDepNames []string
	var cljSrcFiles []string

	// Group files by basename. gen_srcs creates ONE target per basename group
	// (e.g. webauthn.clj + webauthn.cljs → single "webauthn" target).
	sort.Strings(files)
	type fileGroup struct {
		baseName string
		files    []string // all files in this group (sorted)
	}
	groupMap := make(map[string]*fileGroup)
	var groupOrder []string // preserve sorted order
	for _, filename := range files {
		base := filenameSansExt(filename)
		if g, ok := groupMap[base]; ok {
			g.files = append(g.files, filename)
		} else {
			groupMap[base] = &fileGroup{baseName: base, files: []string{filename}}
			groupOrder = append(groupOrder, base)
		}
	}

	for _, baseName := range groupOrder {
		group := groupMap[baseName]

		// Check if this group has JS files (separate java_library target)
		var jsFiles []string
		var cljFiles []string
		for _, f := range group.files {
			if filepath.Ext(f) == ".js" {
				jsFiles = append(jsFiles, f)
			} else {
				cljFiles = append(cljFiles, f)
			}
		}

		// java_library for JS-only groups
		if len(cljFiles) == 0 && len(jsFiles) > 0 {
			r := rule.NewRule("java_library", baseName)
			r.SetAttr("resources", jsFiles)
			if stripPrefix != "" {
				r.SetAttr("resource_strip_prefix", stripPrefix)
			}
			gen = append(gen, r)
			imports = append(imports, nil)
			libDepNames = append(libDepNames, baseName)
			continue
		}

		if len(cljFiles) == 0 {
			continue
		}

		// Find namespace info from parser (use any clj* file in the group).
		var ns *clojureparser.NamespaceInfo
		for _, f := range cljFiles {
			if n, ok := nsByFile[f]; ok {
				ns = n
				break
			}
		}
		if ns == nil {
			log.Printf("clojure: no namespace info for %s/%v, skipping", args.Rel, cljFiles)
			continue
		}

		// All files in the group (clj + js) go into resources.
		allFiles := append(cljFiles, jsFiles...)
		sort.Strings(allFiles)

		// clojure_library — one per basename group
		r := rule.NewRule("clojure_library", baseName)
		r.SetAttr("resources", allFiles)
		if stripPrefix != "" {
			r.SetAttr("resource_strip_prefix", stripPrefix)
		}
		if aotEnabled(ns) {
			r.SetAttr("srcs", allFiles)
			r.SetAttr("aot", []string{ns.Ns})
		}
		r.SetAttr("deps", []string{})

		gen = append(gen, r)
		imports = append(imports, ns)

		// Register namespace for cross-reference indexing.
		key := args.Rel + ":" + baseName
		l.ruleNs[key] = ns.Ns

		libDepNames = append(libDepNames, baseName)
		for _, f := range cljFiles {
			cljSrcFiles = append(cljSrcFiles, f)
		}

		// clojure_test (only for test .clj/.cljc files, not .cljs)
		hasTestClj := false
		for _, f := range cljFiles {
			ext := filepath.Ext(f)
			if isTestFile(f) && (ext == ".clj" || ext == ".cljc") {
				hasTestClj = true
				break
			}
		}
		if hasTestClj {
			tr := rule.NewRule("clojure_test", baseName+".test")
			tr.SetAttr("test_ns", ns.Ns)
			testDeps := []string{":" + baseName}

			// Apply bazel/clojure_test metadata.
			if testMeta := clojureTestMeta(ns); testMeta != nil {
				if extraDeps, ok := testMeta["deps"]; ok {
					if depList, ok := extraDeps.([]interface{}); ok {
						for _, d := range depList {
							if s, ok := d.(string); ok {
								testDeps = append(testDeps, s)
							}
						}
					}
				}
				// Apply other test attributes (tags, timeout, data, etc.)
				for _, attr := range []string{"tags", "data", "jvm_flags", "timeout", "size"} {
					if v, ok := testMeta[attr]; ok {
						switch val := v.(type) {
						case []interface{}:
							strs := make([]string, 0, len(val))
							for _, item := range val {
								if s, ok := item.(string); ok {
									strs = append(strs, s)
								}
							}
							if len(strs) > 0 {
								tr.SetAttr(attr, strs)
							}
						case string:
							tr.SetAttr(attr, val)
						}
					}
				}
			}

			tr.SetAttr("deps", testDeps)
			gen = append(gen, tr)
			imports = append(imports, nil)
		}
	}

	// Filter subdirs to only those under source paths that actually contain
	// clj/cljs/cljc files (matching gen_srcs behavior which checks recursively).
	var clojureSubdirs []string
	for _, sub := range args.Subdirs {
		subRel := filepath.Join(args.Rel, sub)
		if sourceRoot(l.initResp.SourcePaths, subRel) != "" {
			if subdirHasClojureFiles(filepath.Join(args.Config.RepoRoot, subRel)) {
				clojureSubdirs = append(clojureSubdirs, sub)
			}
		}
	}
	sort.Strings(clojureSubdirs)

	// Rollup: __clj_lib
	if len(libDepNames) > 0 || len(clojureSubdirs) > 0 {
		rollupDeps := make([]string, 0, len(libDepNames)+len(clojureSubdirs))
		for _, name := range libDepNames {
			rollupDeps = append(rollupDeps, ":"+name)
		}
		for _, sub := range clojureSubdirs {
			rollupDeps = append(rollupDeps, "//"+filepath.Join(args.Rel, sub)+":__clj_lib")
		}
		r := rule.NewRule("clojure_library", "__clj_lib")
		r.SetAttr("deps", rollupDeps)
		gen = append(gen, r)
		imports = append(imports, nil)
	}

	// Rollup: __clj_files
	if len(cljSrcFiles) > 0 || len(clojureSubdirs) > 0 {
		sort.Strings(cljSrcFiles)
		r := rule.NewRule("filegroup", "__clj_files")
		if len(cljSrcFiles) > 0 {
			r.SetAttr("srcs", cljSrcFiles)
		}

		var data []string
		for _, sub := range clojureSubdirs {
			data = append(data, "//"+filepath.Join(args.Rel, sub)+":__clj_files")
		}
		if len(data) > 0 {
			r.SetAttr("data", data)
		}
		gen = append(gen, r)
		imports = append(imports, nil)
	}

	return language.GenerateResult{
		Gen:     gen,
		Empty:   nil,
		Imports: imports,
	}
}

func (*clojureLang) Fix(_ *config.Config, _ *rule.File) {}
