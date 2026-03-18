(ns rules-clojure.gen-build-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [rules-clojure.gen-build :as gb]
            [rules-clojure.fs :as fs]))

(defn- make-temp-dir
  "Create a temp directory with a src/ subdirectory (matching basis :paths)."
  []
  (let [dir (java.io.File/createTempFile "gen-build-test" "")]
    (.delete dir)
    (.mkdirs dir)
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
          (.delete (io/file dir "src" "example" "api_test.clj"))
          (.delete (io/file dir "src" "example" "api_test.cljs"))
          (.delete (io/file dir "src" "example"))
          (.delete (io/file dir "src"))
          (.delete dir))))))

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
          (.delete (io/file dir "src" "example" "shared.cljc"))
          (.delete (io/file dir "src" "example"))
          (.delete (io/file dir "src"))
          (.delete dir))))))
