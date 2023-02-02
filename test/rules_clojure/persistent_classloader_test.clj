(ns rules-clojure.persistent-classloader-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.reflect :as reflect]
            [rules-clojure.fs :as fs]
            [rules-clojure.util :as util]
            [rules-clojure.persistent-classloader :as pcl]
            [rules-clojure.persistentClassLoader]
            [rules-clojure.test-utils :as test-utils])
  (:import java.lang.ClassLoader
           java.net.URL
           rules_clojure.persistentClassLoader))

(deftest it-loads
  (is (instance? ClassLoader (pcl/new-classloader- []))))

(deftest correct-parent
  (let [p (.getParent (ClassLoader/getSystemClassLoader))]
    (is (= p (.getParent (persistentClassLoader. (into-array URL []) p))))))

(deftest can-load-clojure-util-classpath
  (let [cl (pcl/new-classloader- (test-utils/runfiles-jars "CLOJURE_JARS"))]
    (.loadClass cl "clojure.lang.RT")))

(deftest classloader-isolation
  (let [worker-version *clojure-version*
        classpath-old (test-utils/runfiles-jars "CLOJURE_OLD")]
    (assert (seq classpath-old))
    (doseq [p classpath-old]
      (assert (-> p io/file .exists) p))
    (is (= 11 (:minor worker-version)))
    (pcl/with-classloader (pcl/slow-naive) {:classpath classpath-old}
      (fn [cl]
        (is (= 8 (util/shim-eval cl "(get *clojure-version* :minor)")))))))

(deftest can-load-specs
  (let [worker-version *clojure-version*
        classpath (test-utils/runfiles-jars "CLOJURE_JARS")]
    (is (= 11 (:minor worker-version)))
    (let [strategy (pcl/slow-naive)]
      (pcl/with-classloader strategy {:classpath classpath}
        (fn [cl]
          (is (util/shim-eval cl "(do (require 'clojure.spec.alpha) (clojure.spec.alpha/valid? integer? 3))")))))))

(deftest caching-clean-thread-local-can-reuse
  (let [strategy (pcl/caching-clean-digest-thread-local)
        classpath (test-utils/runfiles-jars "CLOJURE_JARS")
        _ (assert (seq classpath))
        input-map (->> classpath
                       (map (fn [p]
                              [p ""]))
                       (into {}))
        cl1 (atom nil)
        _ (pcl/with-classloader strategy {:classpath classpath
                                          :input-map input-map} (fn [cl] (reset! cl1 cl)))
        cl2 (atom nil)
        _ (pcl/with-classloader strategy {:classpath classpath
                                          :input-map input-map} (fn [cl] (reset! cl2 cl)))]
    (is (= (.getParent @cl1) (.getParent @cl2)))))

(deftest digests-changing-invalidate
  (let [strategy (pcl/caching-clean-digest-thread-local)
        classpath (test-utils/runfiles-jars "CLOJURE_JARS")
        input-map (->> classpath
                       (map (fn [p]
                              [p ""]))
                       (into {}))

        cl1 (atom nil)
        _ (pcl/with-classloader strategy {:classpath classpath
                                          :input-map input-map} (fn [cl] (reset! cl1 cl)))
        cl2 (atom nil)
        _ (pcl/with-classloader strategy {:classpath classpath
                                          :input-map (assoc input-map (first classpath) "new")} (fn [cl] (reset! cl2 cl)))]
    (is (not= (.getParent @cl1) (.getParent @cl2)))))
