(ns rules-clojure.gen-build-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [rules-clojure.gen-build :as gb]
            [rules-clojure.fs :as fs]))

(defn- make-temp-dir
  "Create a temp directory with a src/ subdirectory (matching basis :paths)."
  []
  (let [dir (.toFile (fs/new-temp-dir "gen-build-test"))]
    (.mkdirs (io/file dir "src" "example"))
    dir))

(defn- write-file [dir name content]
  (let [f (io/file dir "src" "example" name)]
    (spit f content)
    (fs/->path (.getAbsolutePath f))))

(defn- minimal-args
  "Build a minimal args map for ns-rules with the given dep-ns->label map."
  [dir dep-ns->label]
  (let [dir-path (fs/->path (.getAbsolutePath dir))]
    {:src-ns->label {}
     :dep-ns->label dep-ns->label
     :jar->lib {}
     :class->jar {}
     :deps-repo-tag "@deps"
     :deps-bazel {}
     :deps-edn-dir dir-path
     :basis {:paths ["src"]
             :classpath {}}}))

(defn- extract-deps
  "Given ns-rules output, extract the deps list from the first clojure_library rule."
  [rules-output]
  (:deps (:attrs (first rules-output))))

(deftest ns-rules-no-cross-platform-deps
  (testing "a .clj file's requires should not be resolved via the CLJS dep map"
    (let [dir (make-temp-dir)
          ;; Create .clj and .cljs files sharing a basename
          clj-path (write-file dir "api_test.clj"
                               "(ns example.api-test\n  (:require [reitit.core :as r]\n            [clojure.test :refer [deftest]]))")
          cljs-path (write-file dir "api_test.cljs"
                                "(ns example.api-test\n  (:require [reagent.core :as reagent]))")
          ;; dep-ns->label with different labels per platform
          dep-ns->label {:clj  {'reitit.core      "ns_metosin_reitit_core_reitit_core"
                                'clojure.test     "org_clojure_clojure"
                                'reagent.core     "ns_reagent_reagent_reagent_core"}
                         :cljs {'reitit.core      "metosin_reitit_core"
                                'reagent.core     "reagent_reagent"}}
          args (minimal-args dir dep-ns->label)
          result (gb/ns-rules args [clj-path cljs-path])
          deps (extract-deps result)]
      (try
        ;; Should include CLJ resolution of reitit.core (AOT label)
        (is (some #(= "@deps//:ns_metosin_reitit_core_reitit_core" %) deps)
            "CLJ require should resolve via CLJ dep map to AOT label")
        ;; Should include CLJS resolution of reagent.core (plain label)
        (is (some #(= "@deps//:reagent_reagent" %) deps)
            "CLJS require should resolve via CLJS dep map to plain label")
        ;; Should NOT include CLJS resolution of reitit.core (plain label)
        ;; This was the bug: .clj file crossed with :cljs platform
        (is (not (some #(= "@deps//:metosin_reitit_core" %) deps))
            "CLJ require must NOT be resolved via CLJS dep map")
        ;; Should NOT include CLJ resolution of reagent.core (AOT label)
        (is (not (some #(= "@deps//:ns_reagent_reagent_reagent_core" %) deps))
            "CLJS require must NOT be resolved via CLJ dep map")
        (finally
          (fs/rm-rf (.toPath dir)))))))

(deftest ns-rules-cljc-resolves-both-platforms
  (testing "a .cljc file's requires should be resolved via both CLJ and CLJS dep maps"
    (let [dir (make-temp-dir)
          cljc-path (write-file dir "shared.cljc"
                                "(ns example.shared\n  (:require [clojure.string :as str]))")
          dep-ns->label {:clj  {'clojure.string "ns_org_clojure_clojure_clojure_string"}
                         :cljs {'clojure.string "org_clojure_clojurescript"}}
          args (minimal-args dir dep-ns->label)
          result (gb/ns-rules args [cljc-path])
          deps (extract-deps result)]
      (try
        ;; .cljc should get deps from BOTH platforms
        (is (some #(= "@deps//:ns_org_clojure_clojure_clojure_string" %) deps)
            "cljc require should resolve via CLJ dep map")
        (is (some #(= "@deps//:org_clojure_clojurescript" %) deps)
            "cljc require should resolve via CLJS dep map")
        (finally
          (fs/rm-rf (.toPath dir)))))))

(defn- find-rule
  "Find a rule in ns-rules output by type keyword (e.g. :clojure_binary). Returns attrs map."
  [rules-output type-kw]
  (:attrs (some #(when (= type-kw (:type %)) %) rules-output)))

(deftest ns-rules-java-binary-from-metadata
  (testing "ns with :bazel/clojure_binary metadata emits a clojure_binary target"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "benchmark.clj"
                               (str "(ns example.benchmark\n"
                                    "  {:bazel/clojure_binary {:jvm_flags [\"-Djdk.attach.allowAttachSelf\"]}}\n"
                                    "  (:gen-class))"))
          args (-> (minimal-args dir {:clj {} :cljs {}})
                   (assoc :deps-bazel {:clojure_library {:jvm_flags ["-Dclojure.main.report=stderr"]}}))
          result (gb/ns-rules args [clj-path])
          attrs (find-rule result :clojure_binary)]
      (try
        (is (some? attrs) "should emit a clojure_binary target")
        (is (= "benchmark.bin" (:name attrs)) "target name should use .bin suffix")
        (is (= "clojure.main" (:main_class attrs)) "should default to clojure.main")
        (is (= ["-m" "example.benchmark"] (:args attrs)) "should pass -m with ns name")
        (is (= ["-Dclojure.main.report=stderr" "-Djdk.attach.allowAttachSelf"] (:jvm_flags attrs))
            "should prepend base jvm flags and append ns-specific")
        (is (some #{":benchmark"} (:runtime_deps attrs)) "should depend on the library target")
        (finally
          (fs/rm-rf (.toPath dir))))))

  (testing "ns without :bazel/clojure_binary metadata does not emit a binary target"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "core.clj"
                               "(ns example.core\n  (:gen-class))")
          args (minimal-args dir {:clj {} :cljs {}})
          result (gb/ns-rules args [clj-path])
          attrs (find-rule result :clojure_binary)]
      (try
        (is (nil? attrs) "should not emit a clojure_binary without metadata")
        (finally
          (fs/rm-rf (.toPath dir))))))

  (testing "metadata can override main_class"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "server.clj"
                               (str "(ns example.server\n"
                                    "  {:bazel/clojure_binary {:main_class \"example.server\"}}\n"
                                    "  (:gen-class))"))
          args (minimal-args dir {:clj {} :cljs {}})
          result (gb/ns-rules args [clj-path])
          attrs (find-rule result :clojure_binary)]
      (try
        (is (= "example.server" (:main_class attrs)) "should use overridden main_class")
        (finally
          (fs/rm-rf (.toPath dir)))))))

(deftest ns-rules-partial-test-meta-no-nil-keys
  (testing "test metadata with only :timeout should not produce nil :size or :tags"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "post_test.clj"
                               "(ns example.post-test\n  {:bazel/clojure_test {:timeout :long}}\n  (:require [clojure.test :refer [deftest]]))")
          args (minimal-args dir {:clj {'clojure.test "org_clojure_clojure"} :cljs {}})
          result (gb/ns-rules args [clj-path])
          attrs (find-rule result :clojure_test)]
      (try
        (is (some? attrs) "should emit a clojure_test target")
        (is (= "long" (:timeout attrs)) "timeout should be converted to string")
        (is (not (contains? attrs :size)) "absent :size should not appear in attrs")
        (is (not (contains? attrs :tags)) "absent :tags should not appear in attrs")
        (finally
          (fs/rm-rf (.toPath dir))))))

  (testing "test metadata with only :size should not produce nil :timeout or :tags"
    (let [dir (make-temp-dir)
          clj-path (write-file dir "large_test.clj"
                               "(ns example.large-test\n  {:bazel/clojure_test {:size :large}}\n  (:require [clojure.test :refer [deftest]]))")
          args (minimal-args dir {:clj {'clojure.test "org_clojure_clojure"} :cljs {}})
          result (gb/ns-rules args [clj-path])
          attrs (find-rule result :clojure_test)]
      (try
        (is (= "large" (:size attrs)) "size should be converted to string")
        (is (not (contains? attrs :timeout)) "absent :timeout should not appear in attrs")
        (is (not (contains? attrs :tags)) "absent :tags should not appear in attrs")
        (finally
          (fs/rm-rf (.toPath dir)))))))
