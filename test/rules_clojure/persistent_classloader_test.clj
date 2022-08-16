(ns rules-clojure.persistent-classloader-test
  (:require [clojure.test :refer :all]
            [rules-clojure.persistent-classloader :as pcl :refer [new-classloader]])
  (:import java.lang.ClassLoader
           java.net.URL))

(deftest it-loads
  (is (instance? ClassLoader (new-classloader []))))

(def clojure-deps ["/org/clojure/clojure/1.11.1/clojure-1.11.1.jar"
                   "/org/clojure/spec.alpha/0.3.218/spec.alpha-0.3.218.jar"
                   "/org/clojure/core.specs.alpha/0.2.62/core.specs.alpha-0.2.62.jar"])

(def shimdandy-deps ["/org/projectodd/shimdandy/shimdandy-api/1.2.1/shimdandy-api-1.2.1.jar"
                     "/org/projectodd/shimdandy/shimdandy-impl/1.2.1/shimdandy-impl-1.2.1.jar"])

(defn m2-path [p]
  (let [root (str (System/getProperty "user.home") "/.m2/repository")]
    (str root p)))

(defn bazel-path [p])

(deftest can-load-clojure
  (let [cl (new-classloader (map m2-path clojure-deps))]
    (.loadClass cl "clojure.lang.RT")))

(deftest can-reuse
  (reset! pcl/classloader-cache {})
  (new-classloader (map m2-path clojure-deps))
  (is (= 1 (count @pcl/classloader-cache)))
  (let [cl2 (new-classloader (map m2-path (concat clojure-deps shimdandy-deps)))]
    (.loadClass cl2 "org.projectodd.shimdandy.impl.ClojureRuntimeShimImpl")
    (is (= 1 (count @pcl/classloader-cache)) (keys @pcl/classloader-cache))))
