(ns rules-clojure.gen-build-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [rules-clojure.gen-build :as gb]
            [rules-clojure.fs :as fs]
            [clojure.string :as str]))

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
  "Build a minimal args map for gen-dir with the given dep-ns->label map."
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

(deftest gen-dir-includes-clojure-test-load-when-tests-present
  (testing "load statement should include clojure_test when test files exist"
    (let [dir (make-temp-dir)
          _ (write-file dir "core.clj" "(ns example.core)")
          _ (write-file dir "core_test.clj" "(ns example.core-test (:require [clojure.test :refer [deftest]]))")
          args (minimal-args dir {:clj {} :cljs {}})
          src-dir (fs/->path (.getAbsolutePath dir) "src" "example")]
      (try
        (gb/gen-dir args src-dir)
        (let [content (slurp (io/file (.toFile src-dir) "BUILD.bazel"))]
          (is (str/includes? content "\"clojure_library\"")
              "should load clojure_library")
          (is (str/includes? content "\"clojure_test\"")
              "should load clojure_test when test files exist"))
        (finally
          (fs/rm-rf (.toPath dir)))))))

(deftest gen-dir-omits-unused-clojure-test-load
  (testing "load statement should not include clojure_test when there are no test files"
    (let [dir (make-temp-dir)
          _ (write-file dir "core.clj" "(ns example.core)")
          args (minimal-args dir {:clj {} :cljs {}})
          src-dir (fs/->path (.getAbsolutePath dir) "src" "example")]
      (try
        (gb/gen-dir args src-dir)
        (let [content (slurp (io/file (.toFile src-dir) "BUILD.bazel"))]
          (is (str/includes? content "\"clojure_library\"")
              "should load clojure_library")
          (is (not (str/includes? content "\"clojure_test\""))
              "should not load clojure_test when no test files exist"))
        (finally
          (fs/rm-rf (.toPath dir)))))))

(deftest gen-dir-skips-load-for-empty-dirs
  (testing "load statement should not be emitted when directory has no clj files"
    (let [dir (make-temp-dir)
          args (minimal-args dir {:clj {} :cljs {}})
          src-dir (fs/->path (.getAbsolutePath dir) "src" "example")]
      (try
        (gb/gen-dir args src-dir)
        (let [content (slurp (io/file (.toFile src-dir) "BUILD.bazel"))]
          (is (not (str/includes? content "load("))
              "should not emit load statement for empty directories"))
        (finally
          (fs/rm-rf (.toPath dir)))))))
