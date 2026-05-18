(ns rules-clojure.gazelle-server-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [rules-clojure.fs :as fs]
            [rules-clojure.gazelle-server :as gs])
  (:import (java.io BufferedReader BufferedWriter File StringReader StringWriter)))

(defn- parse-state-for
  "Build a handle-parse state with :basis / :deps-edn-dir wired to `dir`
  so gen-build/strip-path (called from ns-rules) finds a match. Empty
  string in :paths joins to deps-edn-dir itself — Java Path startsWith
  is element-wise, so :paths [\".\"] would NOT match abs files under
  deps-edn-dir."
  [^File dir]
  {:jar->lib      {}
   :class->jar    {}
   :deps-repo-tag "@deps"
   :dep-ns->label {}
   :deps-bazel    {}
   :basis         {:paths [""]}
   :deps-edn-dir  (fs/->path (.getAbsolutePath dir))})

(defmacro with-temp-dir
  "Bind `dir-sym` to a fresh `File` temp directory for `body`. Recursively
   deletes the directory on exit."
  [[dir-sym prefix] & body]
  `(let [path# (fs/new-temp-dir ~prefix)
         ~(vary-meta dir-sym assoc :tag `File) (.toFile path#)]
     (try ~@body
          (finally (fs/rm-rf path#)))))

(defn- write-clj!
  "Write a .clj file into dir with the given filename and content string."
  [^File dir filename content]
  (let [f (File. dir filename)]
    (spit f content)
    f))

(deftest read-request-parses-json-line
  (let [input "{\"type\":\"init\",\"count\":42}\n"
        reader (BufferedReader. (StringReader. input))
        result (gs/read-request reader)]
    (is (= {:type "init" :count 42} result))
    (is (keyword? (first (keys result))))))

(deftest read-request-returns-nil-on-eof
  (let [reader (BufferedReader. (StringReader. ""))]
    (is (nil? (gs/read-request reader)))))

(deftest write-response-produces-json-line
  (let [sw (StringWriter.)
        writer (BufferedWriter. sw)]
    (gs/write-response writer {:type "init" :data [1 2 3]})
    (let [output (.toString sw)
          parsed (json/read-str (str/trim output) :key-fn keyword)]
      (is (str/ends-with? output "\n"))
      (is (= "init" (:type parsed)))
      (is (= [1 2 3] (:data parsed))))))

(deftest write-then-read-roundtrips-multiple-messages
  (testing "writer/reader pair can interleave multiple lines without loss"
    (let [sw (StringWriter.)
          writer (BufferedWriter. sw)]
      (gs/write-response writer {:type "init" :a 1})
      (gs/write-response writer {:type "parse" :b 2})
      (let [reader (BufferedReader. (StringReader. (.toString sw)))]
        (is (= {:type "init" :a 1} (gs/read-request reader)))
        (is (= {:type "parse" :b 2} (gs/read-request reader)))
        (is (nil? (gs/read-request reader)))))))

(deftest strip-leading-colon-handles-both-prefixes
  (testing "aliases may arrive with or without a leading colon. The helper
  strips a single leading colon so `(keyword (strip-leading-colon \":foo\"))`
  produces :foo and not the malformed :' :foo'."
    (is (= "foo"  (#'gs/strip-leading-colon "foo")))
    (is (= "bar"  (#'gs/strip-leading-colon ":bar")))
    (is (= "test" (#'gs/strip-leading-colon ":test")))
    (is (= ""     (#'gs/strip-leading-colon "")))))

;; init-state's full pipeline is exercised by gazelle/clojureparser/parser_test.go
;; (TestInitRoundTrip) — it boots the real subprocess against a fixture deps.edn
;; once the deploy jar and its Maven dependencies are available.

(deftest handle-parse-clj-file
  (testing "parse handler extracts namespace info from a .clj file"
    (with-temp-dir [dir "gazelle-parse-test-"]
      (write-clj! dir "core.clj"
                  "(ns my.core (:require [clojure.string :as str] [clojure.set]))")
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir) :files ["core.clj"]})]
        (is (= "parse" (:type resp)))
        (is (= 1 (count (:namespaces resp))))
        (let [ns-info (first (:namespaces resp))]
          (is (= "my.core" (:ns ns-info)))
          (is (= "core.clj" (:file ns-info)))
          (is (= ["clj"] (:platforms ns-info)))
          (is (contains? (:requires ns-info) "clj"))
          (is (contains? (set (get-in ns-info [:requires "clj"])) "clojure.string"))
          (is (contains? (set (get-in ns-info [:requires "clj"])) "clojure.set")))))))

(deftest handle-parse-cljc-file
  (testing "parse handler reports both platforms for .cljc"
    (with-temp-dir [dir "gazelle-parse-cljc-"]
      (write-clj! dir "util.cljc" "(ns my.util)")
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir) :files ["util.cljc"]})
            ns-info (first (:namespaces resp))]
        (is (= 1 (count (:namespaces resp))))
        (is (= "my.util" (:ns ns-info)))
        (is (= ["clj" "cljs"] (:platforms ns-info)))))))

(deftest handle-parse-js-files
  (testing "parse handler returns minimal entries for .js files"
    (with-temp-dir [dir "gazelle-parse-js-"]
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir) :files ["app.js" "lib.js"]})]
        (is (= "parse" (:type resp)))
        (is (= 2 (count (:namespaces resp))))
        (is (every? #(= ["js"] (:platforms %)) (:namespaces resp)))
        (is (= #{"app.js" "lib.js"} (set (map :file (:namespaces resp)))))))))

(deftest handle-parse-mixed-files
  (testing "parse handler handles clj and js files together"
    (with-temp-dir [dir "gazelle-parse-mixed-"]
      (write-clj! dir "core.clj" "(ns mixed.core)")
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir) :files ["core.clj" "bundle.js"]})
            by-file (into {} (map (juxt :file identity) (:namespaces resp)))]
        (is (= 2 (count (:namespaces resp))))
        (is (= "mixed.core" (:ns (get by-file "core.clj"))))
        (is (= ["clj"] (:platforms (get by-file "core.clj"))))
        (is (= ["js"] (:platforms (get by-file "bundle.js"))))))))

;; Per-namespace ns_meta is no longer a top-level wire field — gen-build/ns-rules
;; merges it into the clojure_library rule's attrs (e.g. ns_library_meta keys
;; like :aot end up in :attrs "aot"). gen_build_test covers that directly.

;; Per-namespace import_deps are merged into the clojure_library rule's :deps
;; by gen-build/ns-rules using state[:class->jar / :jar->lib / :deps-repo-tag].
;; The exact label format (e.g. "@deps//:_maven___java_runtime") is gen-build's
;; concern and is tested at that layer, not on the wire.

(deftest handle-parse-returns-error-entry-on-parse-failure
  (testing "syntactically broken .clj produces a tagged error rather than silent []"
    (with-temp-dir [dir "gazelle-parse-broken-"]
      (write-clj! dir "broken.clj" "(ns my.broken (:require [foo (")  ; unbalanced
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir) :files ["broken.clj"]})
            entries (:namespaces resp)]
        (is (= 1 (count entries)))
        (is (some? (:error (first entries)))
            "parse failure must surface as :error so the Go side can abort")))))

(deftest handle-parse-no-ns-form-returns-empty
  (testing "resource-only .clj (no ns form) yields no namespace entry"
    (with-temp-dir [dir "gazelle-parse-no-ns-"]
      (write-clj! dir "data.clj" "{:reader 'foo}")
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir) :files ["data.clj"]})]
        (is (zero? (count (:namespaces resp))))))))

(deftest handle-request-rejects-parse-before-init
  (testing "parse before init returns an error envelope, not silent empty"
    (let [state (atom nil)
          response (gs/handle-request state {:type "parse" :dir "/" :files []})]
      (is (= "error" (:type response)))
      (is (str/includes? (:message response) "init")))))

(deftest handle-request-rejects-unknown-type
  (let [state (atom nil)
        response (gs/handle-request state {:type "wat"})]
    (is (= "error" (:type response)))
    (is (str/includes? (:message response) "unknown"))))

(deftest handle-parse-rollup-rules-clj-only
  (testing "rollup_rules emits __clj_lib with the local rule name (not nil)
  when the package has clojure_library content. Regression for the wire-
  shape bug where lib-names was computed against keyword keys on a
  stringified-attr map and returned [nil]."
    (with-temp-dir [dir "gazelle-rollup-clj-"]
      (write-clj! dir "core.clj" "(ns my.core)")
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir) :files ["core.clj"]})
            rollups (:rollup_rules resp)
            by-name (into {} (map (juxt #(get-in % [:attrs "name"]) identity) rollups))]
        (is (= 2 (count rollups))
            "__clj_lib + __clj_files emitted when local clojure_library exists")
        (is (= ["clojure_library" "filegroup"] (sort (map :kind rollups))))
        (let [lib (get by-name "__clj_lib")]
          (is (= "clojure_library" (:kind lib)))
          (is (= [":core"] (get-in lib [:attrs "deps"]))
              "__clj_lib deps reference the local rule by basename (no extension), not nil"))
        (let [files (get by-name "__clj_files")]
          (is (= "filegroup" (:kind files)))
          (is (= ["core.clj"] (get-in files [:attrs "srcs"]))))))))

(deftest handle-parse-rollup-rules-with-subdirs
  (testing "rollup_rules includes subdir labels passed via clojure_subdir_paths
  even when the package has no local Clojure files of its own."
    (with-temp-dir [dir "gazelle-rollup-subdirs-"]
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir)
                                   :files []
                                   :clojure_subdir_paths ["src/foo"]})
            rollups (:rollup_rules resp)
            by-name (into {} (map (juxt #(get-in % [:attrs "name"]) identity) rollups))]
        (is (= 2 (count rollups))
            "__clj_lib + __clj_files emitted from subdirs alone")
        (is (= ["//src/foo:__clj_lib"] (get-in by-name ["__clj_lib" :attrs "deps"])))
        (is (= ["//src/foo:__clj_files"] (get-in by-name ["__clj_files" :attrs "data"])))))))

(deftest handle-parse-rollup-rules-empty
  (testing "rollup_rules is empty when no local content and no subdirs."
    (with-temp-dir [dir "gazelle-rollup-empty-"]
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir) :files []})]
        (is (= [] (:rollup_rules resp)))))))

(deftest handle-parse-js-only-rules-populated
  (testing "JS-only groups still emit a :rules vector via ns-rules so the
  Go side can build a java_library."
    (with-temp-dir [dir "gazelle-js-rules-"]
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir) :files ["lib.js"]})
            ns-info (first (:namespaces resp))]
        (is (= 1 (count (:namespaces resp))))
        (is (= ["js"] (:platforms ns-info)))
        (is (seq (:rules ns-info)) "JS-only entry must have a :rules vector")
        (is (= "java_library" (-> ns-info :rules first :kind)))))))

(deftest handle-parse-cljs-with-resource-only-clj-partner
  (testing "Resource-only .clj paired with .cljs that declares ns: surface the
  cljs ns instead of silently dropping the rule because primary (clj) had no
  ns form. Regression for the resource-only-drops-cljs bug."
    (with-temp-dir [dir "gazelle-cljs-partner-"]
      (write-clj! dir "shared.clj"  "{:reader 'foo}")     ; resource-only
      (write-clj! dir "shared.cljs" "(ns my.shared)")
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir) :files ["shared.clj" "shared.cljs"]})
            ns-info (first (:namespaces resp))]
        (is (= 1 (count (:namespaces resp))))
        (is (= "my.shared" (:ns ns-info)))
        (is (contains? (set (:platforms ns-info)) "cljs"))))))

(deftest handle-parse-namespaces-deterministic-order
  (testing "Namespaces emitted in sorted-by-basename order so BUILD diffs are
  stable across runs."
    (with-temp-dir [dir "gazelle-order-"]
      (write-clj! dir "z_last.clj"  "(ns my.z)")
      (write-clj! dir "a_first.clj" "(ns my.a)")
      (write-clj! dir "m_mid.clj"   "(ns my.m)")
      (let [resp (gs/handle-parse (parse-state-for dir)
                                  {:dir (str dir)
                                   :files ["z_last.clj" "a_first.clj" "m_mid.clj"]})]
        (is (= ["a_first.clj" "m_mid.clj" "z_last.clj"]
               (mapv :file (:namespaces resp))))))))

(deftest handle-parse-rejects-malformed-request
  (testing "handle-parse validates the request shape and throws ex-info on
  malformed inputs instead of crashing with cryptic ClassCastExceptions."
    (let [state (parse-state-for (.toFile (fs/new-temp-dir "gazelle-malformed-")))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (gs/handle-parse state {:dir 42 :files []})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (gs/handle-parse state {:dir "/tmp" :files "not-a-vec"}))))))
