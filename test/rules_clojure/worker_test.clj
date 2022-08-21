(ns rules-clojure.worker-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.deps.alpha :as deps]
            [clojure.test :refer :all]
            [rules-clojure.worker :as worker]
            [rules-clojure.fs :as fs])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStreamReader PipedOutputStream PipedInputStream OutputStreamWriter]
           [clojure.lang LineNumberingPushbackReader]))

(def basic-req {:classes-dir "classes"
                :output-jar "test.jar"
                :srcs ["rules_clojure/compile.jar"]
                :src-dir ""
                :classpath (map str (clojure.java.classpath/classpath))
                :aot-nses ["rules-clojure.compile"]})

(defn with-temp-output [req]
  (assoc req :output-jar (str "/tmp/" "rules-clojure-test" (System/currentTimeMillis) ".jar")))

(deftest ephemeral
  (let [arg-path (fs/new-temp-file "/tmp" "args" ".txt")
        req (-> basic-req with-temp-output)
        output-jar-path (-> req :output-jar fs/->path)]
    (is (not (fs/exists? output-jar-path)))
    (spit (-> arg-path fs/path->file) (json/write-str req))
    (is (= req (-> arg-path fs/path->file slurp (json/read-str :key-fn keyword))))
    (worker/-main (str "@" arg-path))
    (is (fs/exists? output-jar-path))))

(defn old-clojure-req []
  (let [old-coords '{:deps {org.clojure/clojure {:mvn/version "1.8.0"}}}
        basis (deps/create-basis {:project old-coords :user nil})]
    (assoc basic-req :classpath (->> basis :classpath-roots))))

(deftest classloader-isolation
  (let [worker-version *clojure-version*
        req (old-clojure-req)]
    (println "old-clojure:" req)
    (is (= 11 (:minor worker-version)))
    (let [cl (worker/compile-env (:classpath req))]
      (is (= 8 (worker/shim-eval cl "(get *clojure-version* :minor)"))))))

(deftest can-load-specs
  (let [worker-version *clojure-version*
        coords '{:deps {org.clojure/clojure {:mvn/version "1.11.1"}}}
        basis (deps/create-basis {:project coords :user nil})
        req (assoc basic-req :classpath (->> basis :classpath-roots))]
    (println "clojure:" req)
    (is (= 11 (:minor worker-version)))
    (let [cl (worker/compile-env (:classpath req))]
      (is (worker/shim-eval cl "(do (require 'clojure.spec.alpha) (clojure.spec.alpha/valid? integer? 3))")))))

(deftest process-persistent-works
  (let [req (-> basic-req with-temp-output)
        work-req {:arguments req
                  :requestId 1}
        in-bais (java.io.ByteArrayInputStream. (-> work-req json/write-str (.getBytes "UTF-8")))
        in-reader (LineNumberingPushbackReader. (InputStreamReader. in-bais))
        pipe-in (PipedInputStream.)
        pipe-out (PipedOutputStream.)
        _ (.connect pipe-in pipe-out)
        out-reader (LineNumberingPushbackReader. (InputStreamReader. pipe-in))
        out-writer (OutputStreamWriter. pipe-out)
        resp-f (future (binding [*in* out-reader]
                         (println "reading from" *in*)
                         (read-line)))
        worker-ret (promise)]
    (binding [*in* in-reader
              *out* out-writer]
      (deliver worker-ret (worker/process-persistent)))
    (is (= :exit @worker-ret))
    (let [resp (deref resp-f 2000 nil)
          resp-json (json/read-str resp :key-fn keyword)]
      (is (s/valid? ::worker/work-response resp-json))
      (is (= 0 (:exit_code resp-json))))))

;; TODO process-persistent multiplex vs. singleplex
