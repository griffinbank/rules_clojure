(ns rules-clojure.persistent-classloader-test
  (:require [clojure.test :refer :all]
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

(defn m2-path [p]
  (let [root (str (System/getProperty "user.home") "/.m2/repository/")]
    (str root p)))

(defn bazel-path [p])

(deftest correct-parent
  (let [p (.getParent (ClassLoader/getSystemClassLoader))]
    (is (= p (.getParent (persistentClassLoader. (into-array URL []) p))))))

(deftest can-load-clojure
  (let [cl (pcl/new-classloader (map m2-path clojure-deps))]
    (.loadClass cl "clojure.lang.RT")))

(deftest can-reuse
  (let [strategy (pcl/caching-clean)
        classpath (map m2-path clojure-deps)
        cl1 (pcl/get-classloader strategy classpath)
        _ (pcl/return-classloader strategy ['user] classpath cl1)
        cl2 (pcl/get-classloader strategy classpath)]
    (is (= cl1 cl2))))
