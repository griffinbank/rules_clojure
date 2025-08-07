(ns rules-clojure.persistent-classloader-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [rules-clojure.persistent-classloader :as pcl]
            [rules-clojure.persistentClassLoader]
            [rules-clojure.util :as util]
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
    (is (= 12 (:minor worker-version)))
    (let [cl (pcl/build (pcl/slow-naive) {:classpath classpath-old})]
      (is (= 8 (util/shim-eval cl "(get *clojure-version* :minor)"))))))

(deftest can-load-specs
  (let [worker-version *clojure-version*
        classpath (test-utils/runfiles-jars "CLOJURE_JARS")]
    (is (= 12 (:minor worker-version)))
    (let [strategy (pcl/slow-naive)
          cl (pcl/build strategy {:classpath classpath})]
      (is (util/shim-eval cl "(do (require 'clojure.spec.alpha) (clojure.spec.alpha/valid? integer? 3))")))))

(deftest dir-shas
  (let [shas (pcl/dir-shas "src")]
    (is (seq shas))
    (is (pcl/shas? shas))))

(deftest jar-shas
  (let [shas (pcl/jar-shas (first (test-utils/runfiles-jars "CLOJURE_JARS")))]
    (is (seq shas))
    (is (pcl/shas? shas))))

(deftest compatibility
  (let [cp-new (test-utils/runfiles-jars "CLOJURE_JARS")
        cp-old (test-utils/runfiles-jars "CLOJURE_OLD")]
    (is (not (pcl/compatible-shas? cp-old cp-new)))))
