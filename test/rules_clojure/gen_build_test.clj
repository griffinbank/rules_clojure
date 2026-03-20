(ns rules-clojure.gen-build-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [rules-clojure.gen-build :as gen-build]
            [rules-clojure.fs :as fs]))

;; ---- platform-resolution tests (ns-rules) ----

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
  "Given ns-rules output (list of Bazel strings), extract the deps list
   from the first clojure_library rule."
  [rules-output]
  (when-let [lib-str (first rules-output)]
    (->> (re-seq #"\"([^\"]+)\"" lib-str)
         (map second)
         (filter #(or (.startsWith % "@deps") (.startsWith % "//")))
         vec)))

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
          result (gen-build/ns-rules args [clj-path cljs-path])
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
          result (gen-build/ns-rules args [cljc-path])
          deps (extract-deps result)]
      (try
        ;; .cljc should get deps from BOTH platforms
        (is (some #(= "@deps//:ns_org_clojure_clojure_clojure_string" %) deps)
            "cljc require should resolve via CLJ dep map")
        (is (some #(= "@deps//:org_clojure_clojurescript" %) deps)
            "cljc require should resolve via CLJS dep map")
        (finally
          (fs/rm-rf (.toPath dir)))))))

;; ---- formatting tests (emit-bazel) ----

(deftest test-emit-bazel-vector-inline
  (testing "empty vector"
    (is (= "[]" (gen-build/emit-bazel []))))
  (testing "single element"
    (is (= "[\"a\"]" (gen-build/emit-bazel ["a"]))))
  (testing "single element stays inline"
    (is (= "[\"long-element-name\"]" (gen-build/emit-bazel ["long-element-name"])))))

(deftest test-emit-bazel-function-call-inline
  (testing "positional args only"
    (is (= "load(\"@rules_clojure//:rules.bzl\", \"clojure_library\")"
           (gen-build/emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_library")))))
  (testing "single kwarg stays inline"
    (is (= "package(default_visibility = [\"//visibility:public\"])"
           (gen-build/emit-bazel (list 'package (gen-build/kwargs {:default_visibility ["//visibility:public"]})))))))

(deftest test-emit-bazel-function-call-multiline
  (testing "multiple kwargs go multiline with sorted attrs"
    (let [result (gen-build/emit-bazel
                   (list 'clojure_library
                         (gen-build/kwargs {:name "build"
                                           :deps ["//resources:data_readers"
                                                  "@deps//:org_clojure_clojure"]
                                           :srcs ["build.clj"]
                                           :aot ["build"]})))]
      (is (= (str "clojure_library(\n"
                   "    name = \"build\",\n"
                   "    srcs = [\"build.clj\"],\n"
                   "    aot = [\"build\"],\n"
                   "    deps = [\n"
                   "        \"//resources:data_readers\",\n"
                   "        \"@deps//:org_clojure_clojure\",\n"
                   "    ],\n"
                   ")")
             result)))))

(deftest test-attr-sorting
  (testing "name first, then by priority, then alphabetical"
    (let [result (gen-build/emit-bazel
                   (list 'clojure_library
                         (gen-build/kwargs {:deps ["a"] :name "x" :aot ["b"] :srcs ["c"] :runtime_deps ["d"]})))]
      (is (str/starts-with? result "clojure_library(\n    name = \"x\",\n    srcs = [\"c\"],"))
      (is (str/includes? result "    aot = [\"b\"],"))
      (is (str/includes? result "    runtime_deps = [\"d\"],\n    deps = [\"a\"],")))))

(deftest test-list-sorting
  (testing "deps are sorted"
    (let [result (gen-build/emit-bazel
                   (list 'clojure_library
                         (gen-build/kwargs {:name "x"
                                           :deps ["@deps//:zzz" ":aaa" "//pkg:lib"]})))]
      (is (str/includes? result "        \":aaa\",\n        \"//pkg:lib\",\n        \"@deps//:zzz\","))))
  (testing "aot is NOT sorted (not in sortable set)"
    (let [result (gen-build/emit-bazel
                   (list 'clojure_library
                         (gen-build/kwargs {:name "x"
                                           :aot ["z-ns" "a-ns"]})))]
      (is (str/includes? result "        \"z-ns\",\n        \"a-ns\",")))))

(deftest test-buildifier-round-trip
  (testing "generated output is buildifier-stable"
    (let [buildifier-path (some-> (shell/sh "which" "buildifier") :out str/trim)]
      (is (not (str/blank? buildifier-path)) "buildifier must be on PATH for this test")
      (when-not (str/blank? buildifier-path)
        (let [content (str "\"\"\"\nTest file.\n\"\"\"\n\n"
                           (gen-build/emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_library"))
                           "\n\n"
                           (gen-build/emit-bazel (list 'package (gen-build/kwargs {:default_visibility ["//visibility:public"]})))
                           "\n\n"
                           (gen-build/emit-bazel
                             (list 'clojure_library
                                   (gen-build/kwargs {:name "test"
                                                      :deps ["@deps//:zzz" ":aaa"]
                                                      :srcs ["z.clj" "a.clj"]
                                                      :aot ["z-ns" "a-ns"]
                                                      :runtime_deps ["@deps//:bbb"]})))
                           "\n")
              result (shell/sh buildifier-path "--type=build" :in content)]
          (is (zero? (:exit result)) (str "buildifier failed: " (:err result)))
          (is (= content (:out result))
              (str "buildifier produced diff. Expected:\n" content "\nGot:\n" (:out result))))))))
