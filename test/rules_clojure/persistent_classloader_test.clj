(ns rules-clojure.persistent-classloader-test
  (:require [clojure.test :refer :all]
            [rules-clojure.util :as util]
            [rules-clojure.persistent-classloader :as pcl]
            [rules-clojure.persistentClassLoader])
  (:import java.lang.ClassLoader
           java.net.URL
           rules_clojure.persistentClassLoader))

(deftest it-loads
  (is (instance? ClassLoader (pcl/new-classloader- []))))

(def clojure-deps ["org/clojure/clojure/1.11.1/clojure-1.11.1.jar"
                   "org/clojure/spec.alpha/0.3.218/spec.alpha-0.3.218.jar"
                   "org/clojure/core.specs.alpha/0.2.62/core.specs.alpha-0.2.62.jar"])

(def shimdandy-deps ["org/projectodd/shimdandy/shimdandy-impl/1.2.1/shimdandy-impl-1.2.1.jar"])

(def test-check ["org/clojure/test.check/1.1.1/test.check-1.1.1.jar"])

(defn libcompile-path []
  (or
   (->> (util/classpath)
        (filter (fn [p] (= "src" p)))
        first)
   (->> (util/classpath)
        (filter (fn [p] (re-find #"rules_clojure/libcompile.jar" p)))
        first)))

(defn m2-path [p]
  (let [root (str (System/getProperty "user.home") "/.m2/repository/")]
    (str root p)))

(defn bazel-path [p])

(deftest correct-parent
  (let [p (.getParent (ClassLoader/getSystemClassLoader))]
    (is (= p (.getParent (persistentClassLoader. (into-array URL []) p))))))

(deftest can-load-clojure
  (let [cl (pcl/new-classloader- (map m2-path clojure-deps))]
    (.loadClass cl "clojure.lang.RT")))

(deftest can-reuse
  (let [strategy (pcl/caching-clean-GAV-thread-local)
        classpath (-> (map m2-path clojure-deps)
                      (set)
                      ;; needs rules-clojure.compile to determine whether to reuse
                      (conj (libcompile-path)))
        cl1 (atom nil)
        _ (pcl/with-classloader strategy {:classpath classpath} (fn [cl] (reset! cl1 cl)))
        cl2 (atom nil)
        _ (pcl/with-classloader strategy {:classpath classpath} (fn [cl] (reset! cl2 cl)))]
    (is (= (.getParent @cl1) (.getParent @cl2)))))
