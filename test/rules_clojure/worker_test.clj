(ns rules-clojure.worker-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [rules-clojure.worker :as worker]
            [rules-clojure.persistent-classloader :as pcl]
            [rules-clojure.fs :as fs]
            [rules-clojure.util :as util]
            [rules-clojure.test-utils :as test-utils])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStreamReader PipedOutputStream PipedInputStream OutputStreamWriter]
           [clojure.lang LineNumberingPushbackReader]))

(doseq [p ["os.arch" "java.version" "java.home"]]
  (printf "%s: %s\n" p (System/getProperty p)))

(def basic-req
  {:classes-dir "classes"
   :output-jar "test.jar"
   :srcs ["rules_clojure/compile.clj"]
   :src-dir "src"
   :classpath (test-utils/runfiles-jars "TEST_JARS")
   :aot-nses ["rules-clojure.compile"]})

(defn with-temp-output [req]
  (assoc req :output-jar (str "/tmp/" "rules-clojure-test" (System/currentTimeMillis) ".jar")))

(deftest sanity-check
  (doseq [p (:classpath basic-req)]
    (is (-> p io/file .exists))))

(deftest ephemeral
  (let [arg-path (fs/new-temp-file "/tmp" "args" ".txt")
        req (-> basic-req with-temp-output)
        output-jar-path (-> req :output-jar fs/->path)]
    (is (not (fs/exists? output-jar-path)))
    (spit (-> arg-path fs/path->file) (json/write-str req))
    (is (= req (-> arg-path fs/path->file slurp (json/read-str :key-fn keyword))))
    (worker/-main (str "@" arg-path))
    (is (fs/exists? output-jar-path))))

(deftest process-persistent-works
  (let [req (-> basic-req with-temp-output)
        work-req {:arguments [(json/write-str req)]
                  :requestId 1
                  :inputs (map (fn [p]
                                 {:path p :digest (str (hash p))}) (util/classpath))}
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
          _ (is resp)
          resp-json (json/read-str resp :key-fn keyword)]
      (when-not (s/valid? ::worker/work-response resp-json)
        (s/explain ::worker/work-response resp-json))
      (is (s/valid? ::worker/work-response resp-json))
      (when-not (zero? (:exitCode resp-json))
        (println resp-json))
      (is (= 0 (:exitCode resp-json))))))

;; TODO process-persistent multiplex vs. singleplex
