#!/usr/bin/env bb

;; Unit tests for src/rules_clojure/gazelle_server.bb. Runs under bb, so the
;; build target just shells out via `bb gazelle_server_bb_test.bb`. Returns
;; non-zero on test failure so a wrapping sh_test fails the bazel target.

;; clojure.java.io is required explicitly because it's not pulled in by
;; gazelle_server.bb's own require (loaded below via load-file).
;; babashka.fs / cheshire.core / clojure.string / clojure.edn come from
;; load-file at runtime; kondo doesn't trace that path, but at runtime
;; the aliases are present.
(require '[clojure.java.io :as io]
         '[clojure.java.shell :as sh]
         '[clojure.test :as t :refer [deftest is testing]])

(import '[java.io BufferedReader BufferedWriter StringReader StringWriter])

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

;; gazelle_server.bb declares (ns rules-clojure.gazelle-server ...), so its
;; defns live in that ns. Refer publics into the test ns so existing test
;; bodies can call `parse-ns-form` / `handle-init` / etc. without prefix.
;; Private vars stay accessible via #'rules-clojure.gazelle-server/<name>.
(clojure.core/refer 'rules-clojure.gazelle-server)

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
  (testing "(ns foo {:k :v}) (no docstring, reads {:k :v})"
    (is (= {:k :v} (get-ns-meta '(ns foo {:k :v})))))
  (testing "(ns foo) (no meta, returns nil)"
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
  (testing "all aot entries in a clojure_library map to the same wrapper label"
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
              "second AOT ns maps to wrapper label")
          (is (= "ns_multi_aot" (clj-ns->label 'foo.c))
              "third AOT ns maps to wrapper label"))
        (finally (fs/delete-tree tmp))))))

(deftest parse-deps-build-block-with-trailing-content
  (testing "back-to-back well-formed blocks parse cleanly (buildifier-canonical shape only; close-paren with trailing content not exercised)"
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
  (testing "parses block-shaped @deps/BUILD.bazel rules (walks open-paren -> close-paren spans, not a single-line match)"
    (let [tmp (fs/create-temp-dir)
          build-file (str (fs/path tmp "BUILD.bazel"))]
      (try
        ;; Real jar with the AOT'd nses so scan-jar lifts them into the
        ;; ns->label index and the AOT rewrite can be observed end-to-end.
        (spit build-file
              (str "java_import(\n"
                   "    name = \"cheshire_cheshire\",\n"
                   "    jars = [\"cheshire.jar\"],\n"
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
        (write-jar-with-clj-entries (fs/path tmp "cheshire.jar")
                                    '[cheshire.core cheshire.factory cheshire.parse])
        (let [{:keys [clj-ns->label cljs-ns->label sha256] :as result}
              (parse-deps-build build-file #{})]
          (is (= #{:clj-ns->label :cljs-ns->label :class->label :sha256}
                 (set (keys result))))
          (is (= 64 (count sha256)) "sha256 is 32 bytes hex")
          (is (= "ns_cheshire_cheshire_cheshire_core" (clj-ns->label 'cheshire.core))
              "AOT'd ns resolves to its wrapper library label")
          (is (= "ns_cheshire_cheshire_cheshire_factory" (clj-ns->label 'cheshire.factory))
              "second AOT'd ns resolves to its own wrapper library")
          (is (= "cheshire_cheshire" (clj-ns->label 'cheshire.parse))
              "non-AOT'd ns falls back to the java_import jar label")
          (is (empty? cljs-ns->label) "no .cljs/.cljc entries in fixture jar"))
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
  (testing "shared fixtures pin the cross-process contract between bb-side and rules-clojure.gen-build/rollup-rules"
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
;; Wire protocol (handle-init / handle-parse)
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
;; ns-rules (rule construction)
;; ---------------------------------------------------------------------------

(def base-ctx
  {:cache {:clj-ns->label {}
           :cljs-ns->label {}
           :class->label (delay {})
           :src-ns-resolver (constantly nil)}
   :config {:deps-repo-tag "@deps"
            :clojure-library-config {}
            :clojure-test-config {}}})

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
          "tests must not be AOT-compiled (would compile the test runner into the jar)"))))

(deftest ns-rules-deps-resolution-clj-platform
  (testing "deps from a .clj file resolve against clj-ns->label"
    (let [path (write-temp-file "(ns foo (:require [my.dep :as d]))" ".clj")
          ctx (assoc-in base-ctx [:cache :clj-ns->label] {'my.dep "my_dep_label"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (some #{"@deps//:my_dep_label"} deps)))))

(deftest ns-rules-cljc-resolves-both-platforms
  (testing "a .cljc file's per-platform requires resolve against both maps"
    (let [path (write-temp-file
                "(ns foo (:require #?(:clj [clj.only :as c] :cljs [cljs.only :as c])))"
                ".cljc")
          ctx (-> base-ctx
                  (assoc-in [:cache :clj-ns->label] {'clj.only "clj_label"})
                  (assoc-in [:cache :cljs-ns->label] {'cljs.only "cljs_label"}))
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (some #{"@deps//:clj_label"} deps))
      (is (some #{"@deps//:cljs_label"} deps)))))

(deftest ns-rules-honours-deps-repo-tag
  (testing "deps-repo-tag is used as label prefix instead of hardcoded '@deps//:'"
    (let [path (write-temp-file "(ns foo (:require [my.dep :as d]))" ".clj")
          ctx (-> base-ctx
                  (assoc-in [:config :deps-repo-tag] "@my_custom_deps")
                  (assoc-in [:cache :clj-ns->label] {'my.dep "my_dep_label"}))
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (some #{"@my_custom_deps//:my_dep_label"} deps))
      (is (some #{"@my_custom_deps//:org_clojure_clojure"} deps)))))

(deftest ns-rules-cljs-clojure-prefix-auto-aliases-to-cljs
  (testing "clojure.X with no CLJS source falls back to cljs.X (mirror of cljs.analyzer/aliasable-clj-ns?)"
    (let [path (write-temp-file "(ns foo (:require [clojure.spec.alpha :as s]))" ".cljs")
          ctx (assoc-in base-ctx [:cache :cljs-ns->label] {'cljs.spec.alpha "org_clojure_clojurescript"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (some #{"@deps//:org_clojure_clojurescript"} deps)
          "clojure.spec.alpha should auto-alias to cljs.spec.alpha"))))

(deftest ns-rules-cljs-clojure-prefix-no-alias-when-original-present
  (testing "clojure.X present on cljs side resolves directly, no rewrite"
    (let [path (write-temp-file "(ns foo (:require [clojure.set :as s]))" ".cljs")
          ctx (assoc-in base-ctx [:cache :cljs-ns->label]
                        {'clojure.set "ns_org_clojure_clojurescript_clojure_set"
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
          ctx (assoc-in base-ctx [:cache :cljs-ns->label] {'cljs.spec.alpha "org_clojure_clojurescript"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          deps (-> rules first :attrs :deps)]
      (is (not (some #{"@deps//:org_clojure_clojurescript"} deps))
          ":clj platform must not pick up the cljs fallback"))))

;; ---------------------------------------------------------------------------
;; make-lazy-src-ns-resolver (top-level ns edge case)
;; ---------------------------------------------------------------------------

(deftest src-ns-resolver-top-level-namespace
  (testing "top-level (no-dot) namespace produces //src-path:leaf (no double-slash before the colon)"
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
  (testing "an unresolvable ns is cached as nil; second lookup returns nil without re-probing"
    (let [tmp (fs/create-temp-dir)
          resolver (make-lazy-src-ns-resolver (str tmp) ["src"])]
      (try
        (is (nil? (resolver 'does.not.exist)))
        (is (nil? (resolver 'does.not.exist)) "second lookup returns nil from cache")
        (finally
          (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; load-or-build-cache (cache-key sensitivity)
;; ---------------------------------------------------------------------------

(deftest cache-key-changes-with-no-aot-set
  (testing "no-aot-set participates in the cache sha; changing it forces a rebuild"
    (let [tmp (fs/create-temp-dir)
          deps-build (fs/path tmp "BUILD.bazel")
          _ (spit (str deps-build)
                  "java_import(\n    name = \"foo\",\n    jars = [],\n)\n")
          ;; First build with empty no-aot-set.
          r1 (load-or-build-cache (str tmp) (str deps-build) #{})
          ;; Second build with a different no-aot-set; sha MUST differ.
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
  (testing "ExecutionException wrapper (from pmap) is stripped so the chain starts at the actionable cause"
    (let [inner (ex-info "real cause" {})
          wrapped (java.util.concurrent.ExecutionException. "wrap" inner)
          chain (exception-chain wrapped)]
      (is (str/includes? chain "real cause"))
      (is (not (str/includes? chain "ExecutionException"))
          "outer ExecutionException must not appear in the rendered chain"))))

(deftest exception-chain-keeps-execution-exception-without-cause
  (testing "ExecutionException with no cause renders the wrapper itself (class + message), not an empty string"
    (let [bare (java.util.concurrent.ExecutionException. "bare-message" nil)
          chain (exception-chain bare)]
      (is (str/includes? chain "ExecutionException"))
      (is (str/includes? chain "bare-message")))))

(deftest fatal-error-detection-ordinary-errors-are-not-fatal
  ;; positive case can't be exercised under bb's GraalVM native-image
  ;; (no reflection metadata for VirtualMachineError constructors).
  (is (not (fatal-error? (ex-info "ordinary" {}))))
  (is (not (fatal-error? (RuntimeException. "ordinary"))))
  (is (not (fatal-error? (Throwable. "bare")))))

(deftest resolve-deps-build-override-missing-path
  (testing "GAZELLE_DEPS_BUILD pointing at a non-existent file throws ex-info (no silent fallthrough)"
    (let [fake-getenv (fn [k] (when (= k "GAZELLE_DEPS_BUILD") "/definitely/does/not/exist/BUILD.bazel"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"GAZELLE_DEPS_BUILD points at missing path"
                            (#'rules-clojure.gazelle-server/resolve-deps-build-override fake-getenv))))))

(deftest resolve-deps-build-override-existing-path
  (testing "GAZELLE_DEPS_BUILD pointing at an existing file returns that path."
    (let [tmp (fs/create-temp-dir)
          build-file (str (fs/path tmp "BUILD.bazel"))]
      (try
        (spit build-file "")
        (let [fake-getenv (fn [k] (when (= k "GAZELLE_DEPS_BUILD") build-file))]
          (is (= build-file (#'rules-clojure.gazelle-server/resolve-deps-build-override fake-getenv))))
        (finally (fs/delete-tree tmp))))))

(deftest resolve-deps-build-override-unset
  (testing "Unset GAZELLE_DEPS_BUILD returns nil so caller falls through to bazel info."
    (is (nil? (#'rules-clojure.gazelle-server/resolve-deps-build-override (constantly nil))))))

(deftest probe-bzlmod-deps-build-canonical
  (testing "probe finds @deps/BUILD.bazel under the canonical bzlmod name."
    (let [tmp (fs/create-temp-dir)
          dir (fs/path tmp "external" "rules_clojure++deps+deps")
          _ (fs/create-dirs dir)
          _ (spit (str (fs/path dir "BUILD.bazel")) "")]
      (try
        (is (= (str (fs/path dir "BUILD.bazel"))
               (#'rules-clojure.gazelle-server/probe-bzlmod-deps-build (str tmp))))
        (finally (fs/delete-tree tmp))))))

(deftest probe-bzlmod-deps-build-apparent
  (testing "probe falls back to the apparent 'deps' repo name when canonical is absent."
    (let [tmp (fs/create-temp-dir)
          dir (fs/path tmp "external" "deps")
          _ (fs/create-dirs dir)
          _ (spit (str (fs/path dir "BUILD.bazel")) "")]
      (try
        (is (= (str (fs/path dir "BUILD.bazel"))
               (#'rules-clojure.gazelle-server/probe-bzlmod-deps-build (str tmp))))
        (finally (fs/delete-tree tmp))))))

(deftest probe-bzlmod-deps-build-none
  (testing "probe returns nil when no @deps/BUILD.bazel exists under any candidate."
    (let [tmp (fs/create-temp-dir)
          _ (fs/create-dirs (fs/path tmp "external"))]
      (try
        (is (nil? (#'rules-clojure.gazelle-server/probe-bzlmod-deps-build (str tmp))))
        (finally (fs/delete-tree tmp))))))

(deftest aliases-colon-stripping
  (testing "handle-init normalises aliases with or without leading colon (\":dev\" and \"dev\" both yield :dev)"
    (let [tmp (fs/create-temp-dir)
          deps-edn-path (str (fs/path tmp "deps.edn"))
          ;; Minimal deps.edn; we only care that handle-init normalises
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
                "leading colon must be optional (both forms produce the same source_paths)")
            (is (contains? (set (-> with-colon :response :source_paths)) "dev")
                ":dev's :extra-paths should be merged into source_paths")
            (testing "init response shape invariants"
              (let [r (:response with-colon)]
                (is (= "init" (:type r)))
                (is (vector? (:ignore_paths r)) ":ignore_paths must be a vector even when empty (Go side treats nil specially)")
                (is (vector? (:source_paths r)))
                (is (= #{"clj" "cljs"} (set (keys (:dep_ns_labels r))))
                    ":dep_ns_labels must carry both platform keys (Go side asserts these are present)")
                (is (every? (complement #{:no-aot :ignore}) (keys (:deps_bazel r)))
                    ":deps_bazel must not include :no-aot / :ignore (those are internal to bb)")))))
        (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; find-output-base cache invalidation
;; ---------------------------------------------------------------------------

(deftest find-output-base-cache-invalidates-when-external-missing
  (testing "cache file pointing at a dir that exists but has no external/ subdir is treated as stale"
    (let [tmp (fs/create-temp-dir)
          stale (str (fs/create-temp-dir))    ; exists, but no external/ inside
          cache-dir (fs/path tmp "target" "gazelle_server_cache")
          _ (fs/create-dirs cache-dir)
          _ (spit (str (fs/path cache-dir "output_base")) stale)
          bazel-out (str (fs/create-temp-dir))
          ;; Make the resolved (non-cached) output_base valid: has external/
          _ (fs/create-dirs (fs/path bazel-out "external"))
          sh-calls (atom 0)]
      (try
        (with-redefs [sh/sh (fn [& _] (swap! sh-calls inc) {:out bazel-out :err "" :exit 0})]
          (is (= bazel-out (find-output-base (str tmp)))
              "stale cache (no external/) must be discarded and bazel info reconsulted")
          (is (= 1 @sh-calls) "bazel info should fire exactly once on stale cache"))
        (finally
          (fs/delete-tree tmp)
          (fs/delete-tree stale)
          (fs/delete-tree bazel-out))))))

(deftest find-output-base-empty-stdout-returns-nil
  (testing "bazel info exits 0 with empty stdout: function returns nil rather than the truthy empty string"
    (let [tmp (fs/create-temp-dir)]
      (try
        (with-redefs [sh/sh (fn [& _] {:out "" :err "" :exit 0})]
          (is (nil? (find-output-base (str tmp)))
              "empty stdout must NOT be cached and must NOT return \"\" (truthy in Clojure)"))
        (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; load-or-build-cache corrupt-transit rebuild
;; ---------------------------------------------------------------------------

(deftest load-or-build-cache-corrupt-transit-rebuilds
  (testing "matching sha but corrupt transit blob: rebuild fires (don't permanently fail on a half-written cache)"
    (let [tmp (fs/create-temp-dir)
          deps-build (str (fs/path tmp "BUILD.bazel"))
          _ (spit deps-build "java_import(\n    name = \"foo\",\n    jars = [],\n)\n")
          ;; Prime the cache.
          first-result (load-or-build-cache (str tmp) deps-build #{})
          cache-dir (fs/path tmp "target" "gazelle_server_cache")
          clj-ns (str (fs/path cache-dir "clj-ns.transit"))
          _ (spit clj-ns "garbage-not-transit")]   ; corrupt while keeping sha valid
      (try
        (let [second-result (load-or-build-cache (str tmp) deps-build #{})]
          (is (= (:sha256 first-result) (:sha256 second-result))
              "post-rebuild sha must match the previously-good sha")
          (is (= {} (:clj-ns->label second-result))
              "rebuilt clj-ns->label is fresh, not the garbage we wrote"))
        (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; scan-jar .cljc / .cljs in-jar ns lifting
;; ---------------------------------------------------------------------------

(defn- write-jar-with-entries
  "Write a jar containing the given {entry-name content} pairs. Used to
  exercise scan-jar's parsing branches with synthetic .cljc / .cljs."
  [jar-path entries]
  (let [^java.io.FileOutputStream fos (java.io.FileOutputStream. (str jar-path))
        ^java.util.jar.JarOutputStream jos (java.util.jar.JarOutputStream. fos)]
    (try
      (doseq [[name content] entries]
        (.putNextEntry jos (java.util.jar.JarEntry. ^String name))
        (.write jos ^bytes (.getBytes ^String content "UTF-8"))
        (.closeEntry jos))
      (finally (.close jos)))))

(deftest scan-jar-cljc-uses-in-jar-ns-form
  (testing ".cljc entries: clj-ns is path-derived; cljs-ns reads in-jar ns form (may disagree with path)"
    (let [tmp (fs/create-temp-dir)
          jar (str (fs/path tmp "x.jar"))]
      (try
        (write-jar-with-entries jar [["my/lib.cljc" "(ns my.lib)\n"]])
        (let [{:keys [clj-nses cljs-nses]} (#'rules-clojure.gazelle-server/scan-jar jar "label-x")]
          (is (= {'my.lib "label-x"} clj-nses) "path-derived clj-ns")
          (is (= {'my.lib "label-x"} cljs-nses) "parsed in-jar ns for cljs"))
        (finally (fs/delete-tree tmp))))))

(deftest scan-jar-cljs-entries-parse-ns
  (testing ".cljs entries register only as cljs-ns (parsed from in-jar (ns ...) form)"
    (let [tmp (fs/create-temp-dir)
          jar (str (fs/path tmp "x.jar"))]
      (try
        (write-jar-with-entries jar [["my/lib.cljs" "(ns my.lib.cljs-only)\n"]])
        (let [{:keys [clj-nses cljs-nses]} (#'rules-clojure.gazelle-server/scan-jar jar "label-x")]
          (is (empty? clj-nses))
          (is (= {'my.lib.cljs-only "label-x"} cljs-nses)))
        (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; parse-group all-fail returns nil to preserve pre-existing rules
;; ---------------------------------------------------------------------------

(deftest parse-group-all-fail-returns-nil
  (testing "every .clj in the group fails to parse: return nil so Gazelle leaves pre-existing rules untouched"
    (let [tmp (fs/create-temp-dir)
          path (str (fs/path tmp "broken.clj"))]
      (try
        (spit path "(ns")    ; unterminated ns form, parse-ns-form returns nil
        (let [result (#'rules-clojure.gazelle-server/parse-group base-ctx [path] "src")]
          (is (nil? result) "all-fail group must return nil"))
        (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; handle-parse source-path tiebreaker
;; ---------------------------------------------------------------------------

(deftest handle-parse-picks-longest-matching-source-path
  (testing "a dir under both src and src/cljs resolves to src/cljs (longest-match wins)"
    (let [tmp (fs/create-temp-dir)
          state {:paths {:root (str tmp) :source-paths ["src" "src/cljs"]}
                 :cache {:clj-ns->label {} :cljs-ns->label {}
                         :class->label (delay {}) :src-ns-resolver (constantly nil)}
                 :config {:deps-repo-tag "@deps"
                          :clojure-library-config {}
                          :clojure-test-config {}}}
          dir-rel "src/cljs/foo"
          src-dir (fs/path tmp dir-rel)
          _ (fs/create-dirs src-dir)
          _ (spit (str (fs/path src-dir "bar.clj")) "(ns foo.bar)")
          resp (handle-parse state {:dir dir-rel :files ["bar.clj"]})
          rules (:rules (first (:namespaces resp)))
          ;; handle-parse wires rules via rule-spec->wire, so :type → :kind
          ;; and attr keys are strings.
          lib-attrs (some #(when (= "clojure_library" (:kind %)) (:attrs %)) rules)]
      (try
        (is (= "src/cljs" (get lib-attrs "resource_strip_prefix"))
            "longest source-path prefix must be chosen as resource_strip_prefix")
        (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; -main request loop (newline-JSON in/out + error envelopes)
;; ---------------------------------------------------------------------------

(defn- drive-main
  "Run -main's loop logic against in-memory reader/writer. Returns the
  written lines (each is a parsed JSON map)."
  [input-lines]
  (let [reader (BufferedReader. (java.io.StringReader. (str (str/join "\n" input-lines) "\n")))
        sw (java.io.StringWriter.)
        writer (BufferedWriter. sw)
        !state (atom nil)]
    ;; Mirror -main's body inline so we don't shell out a fresh bb process.
    (loop []
      (let [request (read-request reader)]
        (when (some? request)
          (let [response
                (cond
                  (:_malformed request)
                  {:type "error"
                   :message (str "malformed JSON request: " (:_message request))}
                  :else
                  (handle-request !state request))]
            (write-response writer response)
            (recur)))))
    (mapv #(cheshire.core/parse-string % true)
          (filter seq (str/split (.toString sw) #"\n")))))

(deftest main-loop-malformed-json-emits-error-envelope
  (testing "malformed JSON yields {type:error, message: ...malformed JSON...}; loop continues to next line"
    (let [responses (drive-main ["not json"
                                 "{\"type\":\"wat\"}"])]
      (is (= 2 (count responses)) "one response per input line")
      (is (= "error" (-> responses (nth 0) :type)))
      (is (str/includes? (-> responses (nth 0) :message) "malformed JSON request"))
      (is (= "error" (-> responses (nth 1) :type)))
      (is (str/includes? (-> responses (nth 1) :message) "unknown request type")))))

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
;; rule-spec->wire (key conversion)
;; ---------------------------------------------------------------------------

(deftest rule-spec->wire-converts-keys-to-strings
  (let [spec {:type :clojure_library
              :attrs {:name "foo" :deps [":bar"] :aot ["foo.bar"]}}
        wire (#'rules-clojure.gazelle-server/rule-spec->wire spec)]
    (is (= "clojure_library" (:kind wire)))
    (is (= {"name" "foo" "deps" [":bar"] "aot" ["foo.bar"]} (:attrs wire)))))

(deftest ns-rules-tolerates-scalar-override-in-library-config
  (testing "deps.edn `:bazel :clojure_library {:resource_strip_prefix \"x\"}` does not crash"
    (let [path (write-temp-file "(ns foo.bar)" ".clj")
          ctx (assoc-in base-ctx [:config :clojure-library-config]
                        {:resource_strip_prefix "custom-prefix"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "src")
          lib (some #(when (= :clojure_library (:type %)) %) rules)]
      (is (= "custom-prefix" (-> lib :attrs :resource_strip_prefix))
          "user-supplied scalar overrides the base map's value (last-write-wins)"))))

(deftest ns-rules-tolerates-scalar-override-in-ns-meta
  (testing "ns-meta `:bazel/clojure_test {:size \"large\"}` does not crash when clojure-test-config also sets :size"
    (let [path (write-temp-file
                "(ns foo.bar-test {:bazel/clojure_test {:size \"large\"}} (:require [clojure.test]))"
                "_test.clj")
          ctx (assoc-in base-ctx [:config :clojure-test-config] {:size "small"})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "test")
          test-rule (some #(when (= :clojure_test (:type %)) %) rules)]
      (is (= "large" (-> test-rule :attrs :size))
          "ns-meta wins over deps.edn clojure-test-config for scalar attrs"))))

(deftest ns-rules-vector-attrs-still-accumulate
  (testing ":jvm_flags from clojure-test-config + ns-meta concatenate (vector attrs keep into-semantics)"
    (let [path (write-temp-file
                "(ns foo.bar-test {:bazel/clojure_test {:jvm_flags [\"-Xmx2g\"]}} (:require [clojure.test]))"
                "_test.clj")
          ctx (assoc-in base-ctx [:config :clojure-test-config] {:jvm_flags ["-Xss512k"]})
          parsed [(parse-file path)]
          rules (ns-rules ctx parsed [path] "test")
          test-rule (some #(when (= :clojure_test (:type %)) %) rules)]
      (is (= ["-Xss512k" "-Xmx2g"] (-> test-rule :attrs :jvm_flags))
          "vector attrs from config + ns-meta concatenate"))))

(deftest ns-rules-cljc-does-not-double-vector-ns-meta
  (testing ".cljc parses once per platform; cross-decl combine must not double vector ns-meta"
    (let [path (write-temp-file
                "(ns foo.bar {:bazel/clojure_library {:tags [\"slow\"]}})"
                ".cljc")
          parsed [(parse-file path)]
          rules (ns-rules base-ctx parsed [path] "src")
          lib (some #(when (= :clojure_library (:type %)) %) rules)]
      (is (= ["slow"] (-> lib :attrs :tags))))))

(deftest probe-bzlmod-deps-build-tilde-separator
  (testing "bazel >=8 / local_path_override `~~` separator is probed"
    (let [tmp (fs/create-temp-dir)
          dir (fs/path tmp "external" "rules_clojure~~deps~deps")
          _ (fs/create-dirs dir)
          _ (spit (str (fs/path dir "BUILD.bazel")) "")]
      (try
        (is (= (str (fs/path dir "BUILD.bazel"))
               (#'rules-clojure.gazelle-server/probe-bzlmod-deps-build (str tmp))))
        (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

;; Only run-and-exit when this file is invoked directly via `bb test.bb`;
;; not when load-file'd from another script or REPL (preserves the same
;; contract gazelle_server.bb itself uses for its (-main) call).
(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (t/run-tests 'user)]
    (System/exit (if (or (pos? fail) (pos? error)) 1 0))))
