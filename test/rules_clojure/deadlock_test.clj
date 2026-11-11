(ns rules-clojure.deadlock-test
  "End-to-end check that a namespace which loads another from its body, while
  holding a global lock, doesn't deadlock the parallel compile.

  This is the shape of dtype-next's `export-symbols`: each exported var runs
  `(.lock load-lib-lock) (load-lib nil 'src-ns) (.unlock load-lib-lock)`.
  Under rules-clojure `load-lib` waits on another thread's compile of
  `src-ns`, with the lock held.

  See test/rules_clojure/deadlock_fixtures for the namespaces:

    importer  loads src from its body, under the lock
    src       requires waiter, so loading it waits for waiter's compile
    waiter    wants the lock, after importer has taken it

  importer holds the lock and waits for src, src waits for waiter, and waiter
  waits for the lock. Both importer and waiter are compiled at once, as
  top-level aot namespaces."
  (:require [clojure.data.json :as json]
            [clojure.test :refer :all]
            [rules-clojure.test-utils :as test-utils]
            [rules-clojure.util :as util]
            [rules-clojure.worker :as worker])
  (:import [java.io InputStreamReader PipedInputStream PipedOutputStream OutputStreamWriter]
           [clojure.lang LineNumberingPushbackReader]))

(def fixture-ns-prefix "rules-clojure.deadlock-fixtures.")

(def request
  {:classes-dir "classes"
   :output-jar (str "/tmp/rules-clojure-deadlock-test" (System/currentTimeMillis) ".jar")
   :classpath (test-utils/runfiles-jars "TEST_JARS")
   :aot-nses [(str fixture-ns-prefix "importer")
              (str fixture-ns-prefix "waiter")]})

(defn print-stuck-threads
  "Print the frames of threads that are inside the fixtures, to show what a
  deadlock is waiting on."
  []
  (doseq [[^Thread t frames] (Thread/getAllStackTraces)
          :let [relevant (filter (fn [^StackTraceElement f]
                                   (re-find #"deadlock_fixtures|rules_clojure\.compile\$(spy_load|ns_send|pcompile)"
                                            (str (.getClassName f))))
                                 frames)]
          :when (seq relevant)]
    (println (str "STUCK " (.getName t) " " (.getState t)))
    (doseq [^StackTraceElement f (take 40 frames)
            :when (not (re-find #"^(clojure\.(lang|core)|java\.util\.concurrent\.(Executors|FutureTask|ThreadPool))" (.getClassName f)))]
      (println "   " (str f)))))

(defn run-worker
  "Run `req` through the persistent worker. Returns the worker's response, or
  ::timeout if the compile doesn't finish within `timeout-ms`."
  [req timeout-ms]
  (let [work-req {:arguments [(json/write-str req)]
                  :requestId 1
                  :inputs (map (fn [p]
                                 {:path p :digest (str (hash p))}) (util/classpath))}
        in-reader (LineNumberingPushbackReader.
                   (InputStreamReader.
                    (java.io.ByteArrayInputStream.
                     (.getBytes (json/write-str work-req) "UTF-8"))))
        pipe-in (PipedInputStream.)
        pipe-out (PipedOutputStream.)
        _ (.connect pipe-in pipe-out)
        out-reader (LineNumberingPushbackReader. (InputStreamReader. pipe-in))
        out-writer (OutputStreamWriter. pipe-out)
        resp-f (future (binding [*in* out-reader] (read-line)))
        worker-f (future (binding [*in* in-reader
                                   *out* out-writer]
                           (worker/process-persistent)))
        done (deref worker-f timeout-ms ::timeout)]
    (if (= ::timeout done)
      (do (print-stuck-threads)
          ::timeout)
      (some-> (deref resp-f 10000 nil)
              (json/read-str :key-fn keyword)))))

(deftest export-symbols-style-load-does-not-deadlock
  (let [resp (run-worker request 60000)]
    (is (not= ::timeout resp)
        "compile did not finish: importer holds the lock and waits for src, src waits for waiter, waiter waits for the lock")
    (when (map? resp)
      (is (zero? (:exitCode resp)) (:output resp)))))
