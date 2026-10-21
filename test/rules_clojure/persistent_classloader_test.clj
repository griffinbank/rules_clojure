(ns rules-clojure.persistent-classloader-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [rules-clojure.fs :as fs]
            [rules-clojure.persistent-classloader :as pcl]
            [rules-clojure.persistentClassLoader]
            [rules-clojure.util :as util]
            [rules-clojure.test-utils :as test-utils])
  (:import java.lang.ClassLoader
           java.net.URL
           rules_clojure.persistentClassLoader
           java.lang.ref.WeakReference))

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
  (let [shas (pcl/jar-shas (first (test-utils/runfiles-jars "CLOJURE_JARS")) nil)]
    (is (seq shas))
    (is (pcl/shas? shas))
    (is (some (fn [[k _hash]]
                (re-find #".class$" (str k))) shas))))

(deftest compatibility
  (let [cp-new (test-utils/runfiles-jars "CLOJURE_JARS")
        cp-old (test-utils/runfiles-jars "CLOJURE_OLD")
        cp->map (fn [cp]
                  (->> cp
                       (map (fn [p]
                              (pcl/shas p "")))
                       (apply merge)))]

    (is (not (pcl/compatible-maps? (cp->map cp-old) (cp->map cp-new))))))

(deftest can-compile-and-GC
  (let [*cl (atom nil)]
    (let [compile-path (fs/new-temp-dir "classes")
          classpath (conj (test-utils/runfiles-jars "COMPILE_CLASSPATH") (str compile-path))
          _ (println "can GC cp:" classpath)
          cl (pcl/new-classloader- classpath)]
      (is (not (seq (fs/ls compile-path))))
      (reset! *cl (WeakReference. cl))

      (util/with-context-classloader cl
        (util/shim-require cl 'rules-clojure.compile)
        (util/shim-invoke cl "rules-clojure.compile" "compile!" (str compile-path) (into-array String ["example.core"]) *out*))

      (is (seq (fs/ls compile-path))))
    (dotimes [_ 5]
      (System/gc)
      (Thread/sleep 100))
    (is (nil? (WeakReference/.get @*cl)))))

(deftest patches-clojure-for-deterministic-ids
  (let [jars (test-utils/runfiles-jars "CLOJURE_JARS")
        cl (pcl/new-classloader- jars)
        ;; initialize RT with the context classloader set, so it loads clojure.core from `cl`
        _ (util/shim-init cl)
        rt (.loadClass cl "clojure.lang.RT")
        ^ThreadLocal override (.get (.getField rt "idOverride") nil)
        lb (.loadClass cl "clojure.lang.Compiler$LocalBinding")]
    (is (some #(= "hashCode" (.getName ^java.lang.reflect.Method %)) (.getDeclaredMethods lb)))
    (is (= (.getLocation (.getCodeSource (.getProtectionDomain rt)))
           (.toURL (io/file (first (filter #(re-find #"clojure-1[^/]*\.jar$" %) jars)))))
        "patched RT reports the clojure jar as its location")
    (testing "nextID uses the override when set, the global counter otherwise"
      (let [next-id #(.invoke (.getMethod rt "nextID" (into-array Class [])) nil (object-array 0))]
        (.set override (java.util.concurrent.atomic.AtomicInteger. 1000))
        (try
          (is (= [1000 1001] [(next-id) (next-id)]))
          (finally
            (.set override nil)))
        (is (< (next-id) 1000))))))

(defn- compile-classes
  "Compile `ns` in a fresh classloader, after burning `burn` ids from the
  global counter. Returns a map of class file path to bytes"
  [ns burn]
  (let [compile-path (fs/new-temp-dir "classes")
        classpath (conj (test-utils/runfiles-jars "COMPILE_CLASSPATH") (str compile-path))
        cl (pcl/new-classloader- classpath)]
    (util/with-context-classloader cl
      (util/shim-eval cl (str "(dotimes [_ " burn "] (gensym))"))
      (util/shim-require cl 'rules-clojure.compile)
      (util/shim-invoke cl "rules-clojure.compile" "compile!" (str compile-path) (into-array String [(str ns)]) *out*))
    (->> (fs/ls-r compile-path)
         (filter (fn [^java.nio.file.Path p]
                   (.endsWith (str p) ".class")))
         (map (fn [^java.nio.file.Path p]
                [(str (.relativize compile-path p)) (vec (java.nio.file.Files/readAllBytes p))]))
         (into {}))))

(deftest compiles-are-deterministic
  (let [a (compile-classes 'rules-clojure.deterministic-fixture 0)
        b (compile-classes 'rules-clojure.deterministic-fixture 7777)]
    (is (seq a))
    (is (= (sort (keys a)) (sort (keys b))))
    (is (= a b))))
