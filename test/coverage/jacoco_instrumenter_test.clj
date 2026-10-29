(ns coverage.jacoco-instrumenter-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [rules-clojure.persistent-classloader :as pcl]
            [rules-clojure.test-utils :as test-utils])
  (:import [java.io ByteArrayOutputStream PrintStream]
           [java.lang.ref WeakReference]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipEntry ZipFile ZipOutputStream]
           [rules_clojure.coverage JacocoInstrumenter]))

(defn- temp-file [name]
  (-> (Files/createTempDirectory "jacoco-instrumenter-test" (make-array FileAttribute 0))
      (.resolve ^String name)
      str))

(defn- zip-entries
  "Map of entry name to {:bytes, :millis}."
  [path]
  (with-open [zf (ZipFile. (io/file path))]
    (->> (enumeration-seq (.entries zf))
         (map (fn [^ZipEntry e]
                [(.getName e) {:bytes (.readAllBytes (.getInputStream zf e))
                               :millis (some-> (.getLastModifiedTime e) .toMillis)}]))
         (into {}))))

(defn- write-jar [path entries]
  (with-open [out (ZipOutputStream. (io/output-stream path))]
    (doseq [[name ^bytes bytes] entries]
      (.putNextEntry out (ZipEntry. ^String name))
      (.write out bytes)
      (.closeEntry out))))

(defn- instrument! [in-jar & srcs]
  (let [out-jar (temp-file "out.jar")
        log (ByteArrayOutputStream.)]
    (JacocoInstrumenter/instrument (into [in-jar out-jar] srcs) (PrintStream. log true))
    {:out-jar out-jar
     :log (str log)}))

(defn- foo-jar []
  (first (test-utils/runfiles-jars "FOO_CORE_JAR")))

(deftest writes-the-java-library-coverage-jar-shape
  (let [in (zip-entries (foo-jar))
        {:keys [out-jar]} (instrument! (foo-jar) "test/coverage/foo/core.clj")
        out (zip-entries out-jar)
        classes (filter #(str/ends-with? % ".class") (keys in))]
    (assert (seq classes))
    (testing "each class is instrumented, and the original is kept as .class.uninstrumented"
      (doseq [c classes]
        (is (= (seq (:bytes (in c))) (seq (:bytes (out (str c ".uninstrumented"))))) c)
        (is (not= (seq (:bytes (in c))) (seq (:bytes (out c)))) c)))
    (testing "other entries are copied unchanged"
      (doseq [[name {:keys [bytes]}] in
              :when (not (str/ends-with? name ".class"))]
        (is (= (seq bytes) (seq (:bytes (out name)))) name)))
    (testing "entry mtimes are kept, so RT.load still prefers __init.class over a newer .clj"
      (doseq [[name {:keys [millis]}] in]
        (is (= millis (:millis (out name))) name)))
    (testing "the paths entry lists the sources"
      (let [paths (filter #(str/ends-with? % "-paths-for-coverage.txt") (keys out))]
        (is (= 1 (count paths)))
        (is (= "test/coverage/foo/core.clj\n" (String. ^bytes (:bytes (out (first paths))) "UTF-8")))))))

(deftest keeps-classes-it-cannot-instrument
  (let [in-jar (temp-file "bad.jar")
        not-a-class (.getBytes "not a class" "UTF-8")
        _ (write-jar in-jar {"foo/bad.class" not-a-class})
        {:keys [out-jar log]} (instrument! in-jar)
        out (zip-entries out-jar)]
    (is (= (seq not-a-class) (seq (:bytes (out "foo/bad.class")))))
    (is (not (contains? out "foo/bad.class.uninstrumented")))
    (is (str/includes? log "skip foo/bad.class"))))

(deftest expands-the-args-file
  (let [args-file (temp-file "args.txt")]
    (spit args-file "a.jar\nb.jar\nsrc/x.clj\n")
    (is (= ["a.jar" "b.jar" "src/x.clj"]
           (vec (JacocoInstrumenter/expandArgsFile (into-array String [(str "@" args-file)])))))))

(defn- gc-until-cleared [refs]
  (loop [n 0]
    (System/gc)
    (let [alive (count (filter #(.get ^WeakReference %) refs))]
      (if (or (zero? alive) (= n 20))
        alive
        (do (Thread/sleep 100)
            (recur (inc n)))))))

(deftest compile-classloaders-that-load-instrumented-classes-can-be-gcd
  ;; The JaCoCo runtime registers a JVM shutdown hook that holds its
  ;; classloader. With a shared runtime parent, that hook does not hold
  ;; the compile classloaders.
  (let [{:keys [out-jar]} (instrument! (foo-jar) "test/coverage/foo/core.clj")
        classpath (into [out-jar] (map str (test-utils/runfiles-jars "CLOJURE_JARS")))
        parent (pcl/jacoco-runtime-loader (mapv str (test-utils/runfiles-jars "JACOCO_JARS")))
        thread (Thread/currentThread)
        context (.getContextClassLoader thread)
        refs (doall
              (for [_ (range 3)]
                (let [cl (pcl/new-classloader- classpath parent)]
                  (.setContextClassLoader thread cl)
                  (try
                    (Class/forName "coverage.foo.core__init" true cl)
                    (finally
                      (.setContextClassLoader thread context)))
                  (WeakReference. cl))))]
    (is (zero? (gc-until-cleared refs)))))
