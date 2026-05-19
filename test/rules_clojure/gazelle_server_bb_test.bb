#!/usr/bin/env bb

;; Unit tests for src/rules_clojure/gazelle_server.bb. Runs under bb, so the
;; build target just shells out via `bb gazelle_server_bb_test.bb`. Returns
;; non-zero on test failure so a wrapping sh_test fails the bazel target.

;; clojure.edn / clojure.java.io are required explicitly because they're
;; not pulled in by gazelle_server.bb's own require (loaded below via
;; load-file). babashka.fs / cheshire.core / clojure.string come from
;; load-file at runtime — kondo doesn't trace that path, but at runtime
;; the aliases are present.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.test :as t :refer [deftest is testing]])

;; Resolve gazelle_server.bb regardless of cwd. The script is exported from
;; src/rules_clojure; tests run from $BUILD_WORKING_DIRECTORY (when invoked
;; via bazel test) or wherever the user shelled in.
(def script-path
  (let [candidates
        (cond-> []
          (System/getenv "GAZELLE_SERVER_BB")
          (conj (System/getenv "GAZELLE_SERVER_BB"))

          true
          (into ["src/rules_clojure/gazelle_server.bb"
                 "../src/rules_clojure/gazelle_server.bb"]))]
    (or (some #(when (fs/exists? %) (str (fs/absolutize %))) candidates)
        (throw (ex-info "Cannot find gazelle_server.bb" {:tried candidates})))))

(load-file script-path)

;; ---------------------------------------------------------------------------
;; NS form parsing
;; ---------------------------------------------------------------------------

(deftest parse-ns-form-plain
  (let [form (parse-ns-form "(ns foo.bar (:require [clojure.string :as s]))" #{:clj})]
    (is (= 'foo.bar (second form))
        "parses straightforward (ns ...) form, picks the ns symbol off")))

(deftest parse-ns-form-cljc-conditional
  (testing "with :features #{:clj}, reader-conditional resolves to the :clj branch"
    (let [src "(ns foo (:require #?(:clj [clojure.spec.alpha :as s]
                                    :cljs [cljs.spec.alpha :as s])))"
          form (parse-ns-form src #{:clj})
          deps (deps-from-ns-decl form)]
      (is (contains? deps 'clojure.spec.alpha))
      (is (not (contains? deps 'cljs.spec.alpha)))))
  (testing "with :features #{:cljs}, resolves to the :cljs branch"
    (let [src "(ns foo (:require #?(:clj [clojure.spec.alpha :as s]
                                    :cljs [cljs.spec.alpha :as s])))"
          form (parse-ns-form src #{:cljs})
          deps (deps-from-ns-decl form)]
      (is (contains? deps 'cljs.spec.alpha))
      (is (not (contains? deps 'clojure.spec.alpha))))))

(deftest parse-ns-form-splice-conditional
  (testing "#?@ splice form expands its branch under the chosen platform"
    (let [src "(ns foo (:require #?@(:clj [[a.b :as ab] [c.d :as cd]]
                                     :cljs [[x.y :as xy]])))"
          deps (deps-from-ns-decl (parse-ns-form src #{:clj}))]
      (is (contains? deps 'a.b))
      (is (contains? deps 'c.d))
      (is (not (contains? deps 'x.y))))))

(deftest deps-from-ns-form-handles-libspec-shapes
  (testing "prefix-list form: (:require [foo [bar :as b] [baz :as bz]])"
    (let [form '(:require [foo [bar :as b] [baz :as bz]])
          deps (set (mapcat #(deps-from-libspec nil %) (rest form)))]
      (is (= #{'foo.bar 'foo.baz} deps))))
  (testing ":as-alias is treated as a non-require (skipped)"
    (let [form '(:require [foo.bar :as-alias fb])
          deps (set (mapcat #(deps-from-libspec nil %) (rest form)))]
      (is (empty? deps))))
  (testing "bare symbol requires resolve to themselves"
    (let [form '(:require foo.bar)
          deps (set (mapcat #(deps-from-libspec nil %) (rest form)))]
      (is (= #{'foo.bar} deps)))))

(deftest get-ns-meta-on-docstring-and-attr-map
  (testing "(ns foo \"docstring\" {:k :v})  reads {:k :v} as ns-meta"
    (is (= {:k :v} (get-ns-meta '(ns foo "docstring" {:k :v})))))
  (testing "(ns foo {:k :v}) — no docstring — reads {:k :v}"
    (is (= {:k :v} (get-ns-meta '(ns foo {:k :v})))))
  (testing "(ns foo) — no meta — returns nil"
    (is (nil? (get-ns-meta '(ns foo))))))

(deftest imports-from-ns-decl-handles-import-shapes
  (testing "(:import package.Class)"
    (is (= #{'java.util.List}
           (imports-from-ns-decl '(ns x (:import java.util.List))))))
  (testing "(:import [package Class1 Class2])"
    (is (= #{'java.util.List 'java.util.Set}
           (imports-from-ns-decl '(ns x (:import [java.util List Set])))))))

;; ---------------------------------------------------------------------------
;; @deps/BUILD.bazel parsing
;; ---------------------------------------------------------------------------

(defn- write-fake-deps-build [content]
  (let [tmp (fs/create-temp-dir)
        path (str (fs/path tmp "BUILD.bazel"))]
    (spit path content)
    [tmp path]))

(defn- write-jar-with-clj-entries
  "Write a minimal jar at jar-path with one empty .clj entry per ns symbol
  so scan-jar populates clj-nses (path-derivation only; no parsing)."
  [jar-path ns-syms]
  (let [^java.io.FileOutputStream fos (java.io.FileOutputStream. (str jar-path))
        ^java.util.jar.JarOutputStream jos (java.util.jar.JarOutputStream. fos)]
    (try
      (doseq [ns-sym ns-syms
              :let [path (-> (str ns-sym)
                             (str/replace "." "/")
                             (str/replace "-" "_")
                             (str ".clj"))]]
        (.putNextEntry jos (java.util.jar.JarEntry. path))
        (.write jos (byte-array 0))
        (.closeEntry jos))
      (finally (.close jos)))))

(deftest parse-deps-build-multi-aot-entries
  (testing "regression: re-find captured only the first AOT ns in
            `aot = [\"a\" \"b\"]`. New impl uses re-seq so all entries
            map to the same wrapper label. We assert all three AOT
            namespaces end up in clj-ns->label pointing at the wrapper
            label (ns_multi_aot)."
    (let [[tmp path] (write-fake-deps-build
                      (str "java_import(\n"
                           "    name = \"some_jar\",\n"
                           "    jars = [\"some.jar\"],\n"
                           ")\n"
                           "clojure_library(\n"
                           "    name = \"ns_multi_aot\",\n"
                           "    aot = [\"foo.a\", \"foo.b\", \"foo.c\"],\n"
                           "    deps = [\":some_jar\"],\n"
                           ")\n"))]
      (try
        ;; Build a real jar at some.jar (relative to @deps/BUILD.bazel's dir)
        ;; with __init.class entries for foo.a/foo.b/foo.c so scan-jar lifts
        ;; them into clj-ns->label.
        (write-jar-with-clj-entries (fs/path tmp "some.jar")
                                    '[foo.a foo.b foo.c])
        (let [{:keys [clj-ns->label]} (parse-deps-build path #{})]
          (is (= "ns_multi_aot" (clj-ns->label 'foo.a))
              "first AOT ns maps to wrapper label")
          (is (= "ns_multi_aot" (clj-ns->label 'foo.b))
              "second AOT ns maps to wrapper label (was dropped by re-find regression)")
          (is (= "ns_multi_aot" (clj-ns->label 'foo.c))
              "third AOT ns maps to wrapper label (was dropped by re-find regression)"))
        (finally (fs/delete-tree tmp))))))

(deftest parse-deps-build-block-with-trailing-content
  (testing "back-to-back well-formed blocks parse cleanly (each closing
            paren is on its own line; buildifier-canonical shape). The
            block-end regex anchors on ^)\\s*$, so any future variant
            with `)  # comment` trailing content would not match — that
            edge case is not exercised here."
    (let [[tmp path] (write-fake-deps-build
                      (str "java_import(\n"
                           "    name = \"a\",\n"
                           "    jars = [\"a.jar\"],\n"
                           ")\n"
                           "java_import(\n"
                           "    name = \"b\",\n"
                           "    jars = [\"b.jar\"],\n"
                           ")\n"))]
      (try
        (let [{:keys [sha256] :as result} (parse-deps-build path #{})]
          (is (string? sha256))
          (is (contains? result :clj-ns->label))
          (is (contains? result :cljs-ns->label)))
        (finally (fs/delete-tree tmp))))))

(deftest parse-deps-build-multi-line-blocks
  (testing "regression for the original single-line-regex bug: real @deps
            files always emit multi-line rules. The parser must walk blocks,
            not match `^clojure_library\\(name = ...` on a single line.
            Asserts the parse pulls jar_imports + AOT mappings out (visible
            via the sha256 changing when those entries change)."
    (let [tmp (fs/create-temp-dir)
          build-file (str (fs/path tmp "BUILD.bazel"))]
      (try
        (spit build-file
              (str "java_import(\n"
                   "    name = \"cheshire_cheshire\",\n"
                   "    jars = [\"repository/cheshire/cheshire/5.11.0/cheshire-5.11.0.jar\"],\n"
                   ")\n"
                   "clojure_library(\n"
                   "    name = \"ns_cheshire_cheshire_cheshire_core\",\n"
                   "    aot = [\"cheshire.core\"],\n"
                   "    deps = [\":cheshire_cheshire\"],\n"
                   ")\n"
                   "clojure_library(\n"
                   "    name = \"ns_cheshire_cheshire_cheshire_factory\",\n"
                   "    aot = [\"cheshire.factory\"],\n"
                   "    deps = [],\n"
                   ")\n"))
        ;; parse-deps-build relies on scan-jar for the actual jar contents.
        ;; The fake jar doesn't exist, so scan-jar will return empty maps —
        ;; but the regex parse of aot/jar names runs first, and that's the
        ;; bit we're testing. We assert the parse succeeded without throwing.
        (let [result (parse-deps-build build-file #{})]
          (is (map? result))
          (is (contains? result :clj-ns->label))
          (is (= #{:clj-ns->label :cljs-ns->label :class->label :sha256}
                 (set (keys result))))
          (is (string? (:sha256 result)))
          (is (= 64 (count (:sha256 result))) "sha256 is 32 bytes hex"))
        (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; rollup-rules data shape
;; ---------------------------------------------------------------------------

(deftest rollup-rules-empty
  (is (= [] (rollup-rules {:lib-deps [] :src-files [] :clojure-subdir-paths []}))))

(deftest rollup-rules-libs-only
  (let [out (rollup-rules {:lib-deps ["a" "b"] :src-files [] :clojure-subdir-paths []})]
    (is (= 1 (count out)))
    (is (= :clojure_library (-> out first :type)))
    (is (= [":a" ":b"] (-> out first :attrs :deps)))))

(deftest rollup-rules-srcs-only
  (let [out (rollup-rules {:lib-deps [] :src-files ["a.clj"] :clojure-subdir-paths []})]
    (is (= 1 (count out)))
    (is (= :filegroup (-> out first :type)))
    (is (= ["a.clj"] (-> out first :attrs :srcs)))))

(deftest rollup-rules-with-subdirs
  (let [out (rollup-rules {:lib-deps ["x"]
                           :src-files ["x.clj"]
                           :clojure-subdir-paths ["src/foo"]})
        by-name (into {} (map (juxt (comp :name :attrs) identity)) out)]
    (is (= 2 (count out)))
    (is (= [":x" "//src/foo:__clj_lib"]
           (-> (by-name "__clj_lib") :attrs :deps)))
    (is (= ["//src/foo:__clj_files"]
           (-> (by-name "__clj_files") :attrs :data)))))

(deftest rollup-rules-matches-shared-parity-fixtures
  (testing "shared fixtures pin the cross-process contract between
            gazelle_server.bb's rollup-rules and gen_build.clj's
            rollup-rules. Drift between the two implementations
            surfaces here OR in the JVM-side mirror test instead of
            silently producing divergent BUILD output."
    (let [fixture-rel "test/rules_clojure/rollup_rules_fixtures.edn"
          fixture-path (or (System/getenv "ROLLUP_FIXTURES") fixture-rel)
          test-srcdir (System/getenv "TEST_SRCDIR")
          resolved (some (fn [p] (when (.exists (io/file p)) p))
                         (cond-> [fixture-path
                                  (str "_main/" fixture-rel)
                                  (str "../" fixture-rel)]
                           test-srcdir (conj (str test-srcdir "/_main/" fixture-rel))))
          fixtures (edn/read-string (slurp resolved))]
      (doseq [{name* :name :keys [input expected]} fixtures]
        (testing (str "fixture: " name*)
          (is (= expected (rollup-rules input))))))))

;; ---------------------------------------------------------------------------
;; Wire protocol — handle-init / handle-parse
;; ---------------------------------------------------------------------------

(deftest handle-parse-rejects-without-init
  (let [!state (atom nil)
        resp (handle-request !state {:type "parse" :dir "." :files []})]
    (is (= "error" (:type resp)))
    (is (str/includes? (:message resp) "init"))))

(deftest handle-request-rejects-unknown-type
  (let [!state (atom nil)
        resp (handle-request !state {:type "wat"})]
    (is (= "error" (:type resp)))
    (is (str/includes? (:message resp) "unknown"))))

;; ---------------------------------------------------------------------------
;; ns-rules — rule construction
;; ---------------------------------------------------------------------------

(def base-ctx
  {:deps-repo-tag "@deps"
   :clj-ns->label {}
   :cljs-ns->label {}
   :class->label (delay {})
   :src-ns-resolver (constantly nil)
   :global-lib-deps []
   :lib-jvm-flags []
   :test-jvm-flags []})

(defn write-temp-file [content suffix]
  (let [f (fs/create-temp-file {:suffix suffix})]
    (spit (str f) content)
    (str f)))

(deftest ns-rules-emits-clojure-library-for-clj
  (testing "a .clj file produces a single clojure_library with aot"
    (let [path (write-temp-file "(ns foo.bar (:require [clojure.string :as s]))" ".clj")
          parsed [(parse-file path)]
          rules (ns-rules base-ctx parsed [path] "src")]
      (is (= 1 (count rules)))
      (is (= :clojure_library (-> rules first :type)))
      (is (= ["foo.bar"] (-> rules first :attrs :aot))))))

(deftest ns-rules-emits-clojure-test-for-test-clj
  (testing "a _test.clj file produces a clojure_library + clojure_test"
    (let [path (write-temp-file "(ns foo.bar-test (:require [clojure.test]))" "_test.clj")
          parsed [(parse-file path)]
          rules (ns-rules base-ctx parsed [path] "test")
          types (set (map :type rules))]
      (is (contains? types :clojure_library))
      (is (contains? types :clojure_test)))))

(deftest ns-rules-no-aot-for-tests
  (testing "test files don't get AOT compiled (aot key absent)"
    (let [path (write-temp-file "(ns foo.bar-test (:require [clojure.test]))" "_test.clj")
          parsed [(parse-file path)]
          rules (ns-rules base-ctx parsed [path] "test")
          lib (some #(when (= :clojure_library (:type %)) %) rules)]
      (is (nil? (get-in lib [:attrs :aot]))
          "tests must not be AOT-compiled — they'd compile the test runner into the jar"))))

(deftest ns-rules-deps-resolution-clj-platform
  (testing "deps from a .clj file resolve against clj-ns->label"
    (let [path (write-temp-file "(ns foo (:require [my.dep :as d]))" ".clj")
          ctx (assoc base-ctx :clj-ns->label {'my.dep "my_dep_label"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (some #{"@deps//:my_dep_label"} deps)))))

(deftest ns-rules-cljc-resolves-both-platforms
  (testing "a .cljc file's per-platform requires resolve against both maps"
    (let [path (write-temp-file
                "(ns foo (:require #?(:clj [clj.only :as c] :cljs [cljs.only :as c])))"
                ".cljc")
          ctx (assoc base-ctx
                     :clj-ns->label {'clj.only "clj_label"}
                     :cljs-ns->label {'cljs.only "cljs_label"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (some #{"@deps//:clj_label"} deps))
      (is (some #{"@deps//:cljs_label"} deps)))))

(deftest ns-rules-honours-deps-repo-tag
  (testing "deps-repo-tag is used as label prefix instead of hardcoded '@deps//:'"
    (let [path (write-temp-file "(ns foo (:require [my.dep :as d]))" ".clj")
          ctx (assoc base-ctx
                     :deps-repo-tag "@my_custom_deps"
                     :clj-ns->label {'my.dep "my_dep_label"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (some #{"@my_custom_deps//:my_dep_label"} deps))
      (is (some #{"@my_custom_deps//:org_clojure_clojure"} deps)))))

(deftest ns-rules-cljs-clojure-prefix-auto-aliases-to-cljs
  (testing "clojure.X with no CLJS source falls back to cljs.X (mirror of cljs.analyzer/aliasable-clj-ns?)"
    (let [path (write-temp-file "(ns foo (:require [clojure.spec.alpha :as s]))" ".cljs")
          ctx (assoc base-ctx :cljs-ns->label {'cljs.spec.alpha "org_clojure_clojurescript"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (some #{"@deps//:org_clojure_clojurescript"} deps)
          "clojure.spec.alpha should auto-alias to cljs.spec.alpha"))))

(deftest ns-rules-cljs-clojure-prefix-no-alias-when-original-present
  (testing "clojure.X present on cljs side resolves directly, no rewrite"
    (let [path (write-temp-file "(ns foo (:require [clojure.set :as s]))" ".cljs")
          ctx (assoc base-ctx
                     :cljs-ns->label {'clojure.set "ns_org_clojure_clojurescript_clojure_set"
                                      'cljs.set    "should_not_resolve_to_this"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (some #{"@deps//:ns_org_clojure_clojurescript_clojure_set"} deps)
          "clojure.set is on cljs classpath, resolves directly")
      (is (not (some #{"@deps//:should_not_resolve_to_this"} deps))
          "must NOT fall through to cljs.set when clojure.set resolves"))))

(deftest ns-rules-clj-clojure-prefix-no-cljs-fallback
  (testing "auto-alias is CLJS-only; :clj platform never falls through to cljs.X"
    (let [path (write-temp-file "(ns foo (:require [clojure.spec.alpha :as s]))" ".clj")
          ctx (assoc base-ctx :cljs-ns->label {'cljs.spec.alpha "org_clojure_clojurescript"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (not (some #{"@deps//:org_clojure_clojurescript"} deps))
          ":clj platform must not pick up the cljs fallback"))))

;; ---------------------------------------------------------------------------
;; make-lazy-src-ns-resolver — top-level ns edge case
;; ---------------------------------------------------------------------------

(deftest src-ns-resolver-top-level-namespace
  (testing "regression: top-level (no-dot) namespace produces a valid Bazel label
            //src-path:leaf — previously emitted //src-path/:leaf which is invalid"
    (let [tmp (fs/create-temp-dir)
          src-dir (fs/path tmp "src")
          _ (fs/create-dirs src-dir)
          _ (spit (str (fs/path src-dir "myapp.clj")) "(ns myapp)")
          resolver (make-lazy-src-ns-resolver (str tmp) ["src"])
          label (resolver 'myapp)]
      (try
        (is (= "//src:myapp" label)
            "no parent segment; label must not contain //src/:myapp")
        (finally
          (fs/delete-tree tmp))))))

(deftest src-ns-resolver-nested-namespace
  (testing "nested namespace still produces //src/foo:bar shape"
    (let [tmp (fs/create-temp-dir)
          dir (fs/path tmp "src" "foo")
          _ (fs/create-dirs dir)
          _ (spit (str (fs/path dir "bar.clj")) "(ns foo.bar)")
          resolver (make-lazy-src-ns-resolver (str tmp) ["src"])]
      (try
        (is (= "//src/foo:bar" (resolver 'foo.bar)))
        (finally
          (fs/delete-tree tmp))))))

(deftest src-ns-resolver-misses-cache-nil
  (testing "an unresolvable ns is cached as nil — second lookup returns nil without re-probing"
    (let [tmp (fs/create-temp-dir)
          resolver (make-lazy-src-ns-resolver (str tmp) ["src"])]
      (try
        (is (nil? (resolver 'does.not.exist)))
        (is (nil? (resolver 'does.not.exist)) "second lookup returns nil from cache")
        (finally
          (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; load-or-build-cache — cache-key sensitivity to no-aot-set
;; ---------------------------------------------------------------------------

(deftest cache-key-changes-with-no-aot-set
  (testing "no-aot-set participates in the cache sha; changing it forces a rebuild"
    (let [tmp (fs/create-temp-dir)
          deps-build (fs/path tmp "BUILD.bazel")
          _ (spit (str deps-build)
                  "java_import(\n    name = \"foo\",\n    jars = [],\n)\n")
          ;; First build with empty no-aot-set.
          r1 (load-or-build-cache (str tmp) (str deps-build) #{})
          ;; Second build with a different no-aot-set — sha MUST differ.
          r2 (load-or-build-cache (str tmp) (str deps-build) #{'some.ns})]
      (try
        (is (not= (:sha256 r1) (:sha256 r2))
            "cache sha must include no-aot-set or stale results survive deps.edn changes")
        (finally
          (fs/delete-tree tmp))))))

(deftest load-or-build-cache-hit-path
  (testing "second call with same inputs hits the sha sentinel and reads transit blobs"
    (let [tmp (fs/create-temp-dir)
          deps-build (str (fs/path tmp "BUILD.bazel"))
          _ (spit deps-build "java_import(\n    name = \"foo\",\n    jars = [],\n)\n")]
      (try
        (let [first-result (load-or-build-cache (str tmp) deps-build #{})
              cache-dir (fs/path tmp "target" "gazelle_server_cache")]
          (is (fs/exists? (str (fs/path cache-dir "sha256"))))
          (is (fs/exists? (str (fs/path cache-dir "clj-ns.transit"))))
          ;; Second call: sha matches, transit blobs are re-read.
          (let [second-result (load-or-build-cache (str tmp) deps-build #{})]
            (is (= (:sha256 first-result) (:sha256 second-result)))
            (is (= (:clj-ns->label first-result) (:clj-ns->label second-result)))))
        (finally (fs/delete-tree tmp))))))

(deftest load-or-build-cache-corrupt-sha-rebuilds
  (testing "an unreadable sha file logs to *err* and triggers a rebuild rather than throwing"
    (let [tmp (fs/create-temp-dir)
          deps-build (str (fs/path tmp "BUILD.bazel"))
          _ (spit deps-build "java_import(\n    name = \"foo\",\n    jars = [],\n)\n")
          _ (load-or-build-cache (str tmp) deps-build #{})
          sha-file (str (fs/path tmp "target" "gazelle_server_cache" "sha256"))
          ;; Replace sha file content with garbage: legible but non-matching.
          _ (spit sha-file "deadbeef")
          result (load-or-build-cache (str tmp) deps-build #{})]
      (try
        (is (string? (:sha256 result)))
        (is (not= "deadbeef" (:sha256 result))
            "stale sha must not match the rebuild's computed sha")
        (finally (fs/delete-tree tmp))))))

(deftest exception-chain-strips-execution-exception
  (testing "pmap wraps worker errors in ExecutionException — strip the outer
            wrapper so the rendered chain starts at the actionable cause."
    (let [inner (ex-info "real cause" {})
          wrapped (java.util.concurrent.ExecutionException. "wrap" inner)
          chain (exception-chain wrapped)]
      (is (str/includes? chain "real cause"))
      (is (not (str/includes? chain "ExecutionException"))
          "outer ExecutionException must not appear in the rendered chain"))))

(deftest exception-chain-keeps-execution-exception-without-cause
  (testing "ExecutionException with no cause renders the wrapper itself
            rather than producing an empty string — and pins that the
            wrapper's message is preserved (a regression to class-only
            rendering would silently drop diagnostic context)."
    (let [bare (java.util.concurrent.ExecutionException. "bare-message" nil)
          chain (exception-chain bare)]
      (is (str/includes? chain "ExecutionException"))
      (is (str/includes? chain "bare-message")))))

(deftest fatal-error-detection
  (testing "fatal-error? must return false for ordinary errors so the
            top-level catch can convert them to error envelopes. The
            positive case (real VirtualMachineError instances) can't be
            constructed under bb's GraalVM-native image because the
            reflection metadata for those constructors is omitted; code
            review covers that direction. This test pins the negative
            case so a future refactor doesn't accidentally broaden the
            predicate."
    (is (not (fatal-error? (ex-info "ordinary" {}))))
    (is (not (fatal-error? (RuntimeException. "ordinary"))))
    (is (not (fatal-error? (Throwable. "bare"))))))

(deftest resolve-deps-build-override-missing-path
  (testing "GAZELLE_DEPS_BUILD pointing at a non-existent file throws ex-info
            rather than silently falling through; protects against typos."
    (let [fake-getenv (fn [k] (when (= k "GAZELLE_DEPS_BUILD") "/definitely/does/not/exist/BUILD.bazel"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"GAZELLE_DEPS_BUILD points at missing path"
                            (#'user/resolve-deps-build-override fake-getenv))))))

(deftest resolve-deps-build-override-existing-path
  (testing "GAZELLE_DEPS_BUILD pointing at an existing file returns that path."
    (let [tmp (fs/create-temp-dir)
          build-file (str (fs/path tmp "BUILD.bazel"))]
      (try
        (spit build-file "")
        (let [fake-getenv (fn [k] (when (= k "GAZELLE_DEPS_BUILD") build-file))]
          (is (= build-file (#'user/resolve-deps-build-override fake-getenv))))
        (finally (fs/delete-tree tmp))))))

(deftest resolve-deps-build-override-unset
  (testing "Unset GAZELLE_DEPS_BUILD returns nil so caller falls through to bazel info."
    (is (nil? (#'user/resolve-deps-build-override (constantly nil))))))

(deftest probe-bzlmod-deps-build-canonical
  (testing "probe finds @deps/BUILD.bazel under the canonical bzlmod name."
    (let [tmp (fs/create-temp-dir)
          dir (fs/path tmp "external" "rules_clojure++deps+deps")
          _ (fs/create-dirs dir)
          _ (spit (str (fs/path dir "BUILD.bazel")) "")]
      (try
        (is (= (str (fs/path dir "BUILD.bazel"))
               (#'user/probe-bzlmod-deps-build (str tmp))))
        (finally (fs/delete-tree tmp))))))

(deftest probe-bzlmod-deps-build-apparent
  (testing "probe falls back to the apparent 'deps' repo name when canonical is absent."
    (let [tmp (fs/create-temp-dir)
          dir (fs/path tmp "external" "deps")
          _ (fs/create-dirs dir)
          _ (spit (str (fs/path dir "BUILD.bazel")) "")]
      (try
        (is (= (str (fs/path dir "BUILD.bazel"))
               (#'user/probe-bzlmod-deps-build (str tmp))))
        (finally (fs/delete-tree tmp))))))

(deftest probe-bzlmod-deps-build-none
  (testing "probe returns nil when no @deps/BUILD.bazel exists under any candidate."
    (let [tmp (fs/create-temp-dir)
          _ (fs/create-dirs (fs/path tmp "external"))]
      (try
        (is (nil? (#'user/probe-bzlmod-deps-build (str tmp))))
        (finally (fs/delete-tree tmp))))))

(deftest aliases-colon-stripping
  (testing "handle-init accepts aliases with or without leading colon —
            both \":dev\" and \"dev\" must yield :dev so deps.edn :aliases
            lookup works either way."
    (let [tmp (fs/create-temp-dir)
          deps-edn-path (str (fs/path tmp "deps.edn"))
          ;; Minimal deps.edn — we only care that handle-init normalises
          ;; the alias kw without throwing, not that anything resolves.
          _ (spit deps-edn-path "{:paths [\"src\"] :aliases {:dev {:extra-paths [\"dev\"]}}}")]
      (try
        (with-redefs [find-deps-build (fn [_] (str (fs/path tmp "fake-deps.bazel")))
                      find-output-base (fn [_] (str tmp))
                      load-or-build-cache (fn [_ _ _]
                                            {:clj-ns->label {} :cljs-ns->label {}
                                             :class->label (delay {}) :sha256 "fake"})]
          (let [_ (spit (str (fs/path tmp "fake-deps.bazel")) "")
                with-colon (handle-init {:deps_edn_path deps-edn-path
                                         :deps_repo_tag "@deps"
                                         :aliases [":dev"]})
                without-colon (handle-init {:deps_edn_path deps-edn-path
                                            :deps_repo_tag "@deps"
                                            :aliases ["dev"]})]
            (is (= (-> with-colon :response :source_paths)
                   (-> without-colon :response :source_paths))
                "leading colon must be optional — both forms produce the same source_paths")
            (is (contains? (set (-> with-colon :response :source_paths)) "dev")
                ":dev's :extra-paths should be merged into source_paths")))
        (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; exception-chain
;; ---------------------------------------------------------------------------

(deftest exception-chain-single-error
  (let [e (ex-info "boom" {})]
    (is (str/includes? (exception-chain e) "boom"))))

(deftest exception-chain-walks-cause
  (testing "nested ex-info chain renders all class:msg links separated by ->"
    (let [inner (ex-info "inner-cause" {})
          outer (ex-info "outer-wrap" {} inner)
          chain (exception-chain outer)]
      (is (str/includes? chain "outer-wrap"))
      (is (str/includes? chain "inner-cause"))
      (is (str/includes? chain " -> ")))))

(deftest exception-chain-no-message
  (testing "exception with nil message still renders the class name"
    (let [e (Throwable.)]
      (is (str/includes? (exception-chain e) "Throwable")))))

;; ---------------------------------------------------------------------------
;; rule-spec->wire — key conversion
;; ---------------------------------------------------------------------------

(deftest rule-spec->wire-converts-keys-to-strings
  (let [spec {:type :clojure_library
              :attrs {:name "foo" :deps [":bar"] :aot ["foo.bar"]}}
        wire (#'user/rule-spec->wire spec)]
    (is (= "clojure_library" (:kind wire)))
    (is (= {"name" "foo" "deps" [":bar"] "aot" ["foo.bar"]} (:attrs wire)))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

;; Only run-and-exit when this file is invoked directly via `bb test.bb`,
;; not when load-file'd from another script or REPL — preserves the same
;; contract gazelle_server.bb itself uses for its (-main) call.
(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (t/run-tests 'user)]
    (System/exit (if (or (pos? fail) (pos? error)) 1 0))))
