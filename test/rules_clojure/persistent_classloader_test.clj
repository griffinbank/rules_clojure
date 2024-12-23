(ns rules-clojure.persistent-classloader-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.reflect :as reflect]
            [rules-clojure.fs :as fs]
            [rules-clojure.util :as util]
            [rules-clojure.jar :as jar]
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

(deftest caching-reuse
  (let [strategy (pcl/caching-threadsafe)
        classpath (test-utils/runfiles-jars "COMPILE_CLASSPATH")
        _ (assert (seq classpath))
        input-map (->> classpath
                       (map (fn [p]
                              [p ""]))
                       (into {}))
        req {:classpath classpath
             :input-map input-map
             :output-jar "caching-reuse-1.jar"
             :aot-nses ["rules-clojure.fs"]
             :classes-dir "target"}

        cl1 (atom nil)

        _ (fs/create-directories (fs/->path "target"))
        _ (fs/create-directories (fs/->path "target2"))

        _ (println "pclt/caching-reuse $compile_classpath" (interpose "\n" (test-utils/runfiles-jars "COMPILE_CLASSPATH")))
        script (jar/get-compilation-script-json req)
        _ (pcl/with-classloader strategy req
            (fn [cl]
              (try
                (assert cl)
                (let [ret (util/shim-eval cl script)]
                  (when (seq ret)
                    (println ret)))
                (reset! cl1 cl)
                (catch Throwable t
                  (throw (ex-info "exception while compiling" {:request req
                                                               :script script} t))))))
        cl2 (atom nil)
        req2 {:classpath classpath
              :input-map input-map
              :aot-nses ["rules-clojure.jar"]
              :output-jar "caching-reuse-2.jar"
              :classes-dir "target2"}
        script2 (jar/get-compilation-script-json req2)
        _ (pcl/with-classloader strategy req2
            (fn [cl]
              (try
                (assert cl)
                (let [ret (util/shim-eval cl script2)]
                  (when (seq ret)
                    (println ret)))
                (reset! cl2 cl)
                (catch Throwable t
                  (throw (ex-info "exception while compiling" {:request req
                                                               :script script2} t))))))]
    ;; the classloader is reused because it was already loaded
    (is (= @cl1 @cl2))))

(deftest caching-invalidate
  (let [strategy (pcl/caching-threadsafe)
        classpath (test-utils/runfiles-jars "COMPILE_CLASSPATH")
        _ (assert (seq classpath))
        input-map (->> classpath
                       (map (fn [p]
                              [p ""]))
                       (into {}))
        req {:classpath classpath
             :input-map input-map
             :output-jar "caching-invalidate-1.jar"
             :aot-nses ["rules-clojure.jar"]
             :classes-dir "target"}

        cl1 (atom nil)

        _ (fs/create-directories (fs/->path "target"))
        _ (fs/create-directories (fs/->path "target2"))

        script (jar/get-compilation-script-json req)
        _ (pcl/with-classloader strategy req
            (fn [cl]
              (try
                (assert cl)
                (let [ret (util/shim-eval cl script)]
                  (when (seq ret)
                    (println ret)))
                (reset! cl1 cl)
                (catch Throwable t
                  (throw (ex-info "exception while compiling" {:request req
                                                               :script script} t))))))
        cl2 (atom nil)
        req2 {:classpath classpath
              :input-map input-map
              :aot-nses ["rules-clojure.fs"]
              :output-jar "caching-invalidate-2.jar"
              :classes-dir "target2"}
        _ (pcl/with-classloader strategy req2
            (fn [cl]
              (try
                (assert cl)
                (let [ret (util/shim-eval cl script)]
                  (when (seq ret)
                    (println ret)))
                (reset! cl2 cl)
                (catch Throwable t
                  (throw (ex-info "exception while compiling" {:request req
                                                               :script script} t))))))]
    ;; the classloader is NOT reused because it was already loaded
    (is (not= @cl1 @cl2))))
