(ns rules-clojure.worker
  (:require [clojure.set :as set]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [rules-clojure.jar :as jar]
            [rules-clojure.util :as util]
            [rules-clojure.persistent-classloader :as pcl])
  (:import java.nio.charset.StandardCharsets
           java.util.concurrent.TimeUnit))

(s/def ::classes-dir string?) ;; path to the *compile-files* dir
(s/def ::output-jar string?) ;; path where the output jar should be written
(s/def ::srcs (s/coll-of string?)) ;; seq of paths to src files to put on classpath while compiling. Relative to src-dir
(s/def ::src-dir (s/nilable string?)) ;; path to root of source tree, relative to execroot
(s/def ::resources (s/coll-of string?)) ;; seq of paths to include in the jar
(s/def ::aot-nses (s/coll-of string?)) ;; seq of namespaces to AOT
(s/def ::classpath (s/coll-of string?)) ;; seq of jars to put on compile classpath

(s/def ::compile-request (s/keys :req-un [::classes-dir
                                          ::output-jar
                                          ::classpath
                                          ::aot-nses]
                                 :opt-un [::resources
                                          ::srcs
                                          ::src-dir]))

(s/def ::arguments (s/cat :c ::compile-req))

(s/def ::requestId nat-int?)
(s/def ::exit_code nat-int?)
(s/def ::output string?)

;; requestId is not present in singleplex mode, required in multiplex mode. We don't have a good way of detecting which mode we're running in, so mark it optional
(s/def ::work-request (s/keys :req-un [::arguments] :opt-un [::requestId]))
(s/def ::work-response (s/keys :req-un [::exit_code ::output] :opt-un [::requestId]))

(defn all-dep-map-jars [dep-map]
  (apply set/union (set (keys dep-map)) (set (vals dep-map))))

(defn all-classpath-jars [classpath]
  (set classpath))

(defn process-request
  [{:keys [classloader-strategy] :as req}]
  (util/validate! ::compile-request req)
  (assert classloader-strategy)
  (let [compile-script (jar/get-compilation-script-json req)]
    (try
      (pcl/with-classloader classloader-strategy (select-keys req [:classpath :aot-nses])
        (fn [cl]
          (let [ret (util/shim-eval cl compile-script)]
            (when (seq ret)
              (println ret)))
          (jar/create-jar-json req)))
      (catch Throwable t
        (throw (ex-info "exception while compiling" {:request req
                                                     :script compile-script} t))))))

(defn process-ephemeral [args]
  (let [req-json (json/read-str (slurp (io/file (.substring (last args) 1))) :key-fn keyword)]
    (process-request (assoc req-json :classloader-strategy (pcl/slow-naive)))))

(defn process-persistent-1 [{:keys [arguments requestId classloader-strategy]}]
  (let [baos (java.io.ByteArrayOutputStream.)
        out-printer (java.io.PrintWriter. baos true StandardCharsets/UTF_8)
        real-out *out*]
    (let [exit (binding [*out* out-printer]
                 (try
                   (let [compile-req (json/read-str (first arguments) :key-fn keyword)]
                     (process-request (assoc compile-req :classloader-strategy classloader-strategy)))
                   0
                   (catch Throwable t
                     (println t) ;; print to bazel str
                     1)))
          _ (.flush out-printer)
          resp (merge
                {:exitCode exit
                 :output (str baos)}
                (when requestId
                  {:requestId requestId}))]
      (.write real-out (json/write-str resp))
      (.write real-out "\n")
      (.flush real-out))))

(defn process-persistent []
  (let [num-processors (-> (Runtime/getRuntime) .availableProcessors)
        executor (java.util.concurrent.Executors/newWorkStealingPool num-processors)
        classloader-strategy (pcl/caching-clean-GAV-thread-local)]
    (loop []
      (if-let [line (read-line)]
        (let [work-req (json/read-str line :key-fn keyword)]
          (util/print-err "got req")
          (let [out *out*
                err *err*]
            (.submit executor ^Runnable (fn []
                                          (binding [*out* out
                                                    *err* err]
                                            (process-persistent-1 (assoc work-req
                                                                         :classloader-strategy classloader-strategy)))))
            (recur)))
        (do
          (util/print-err "no request, exiting")
          (.shutdown executor)
          (util/print-err "awating task completion")
          (util/print-err "finished cleanly?" (.awaitTermination executor 60 TimeUnit/SECONDS))
          :exit)))))

(defn set-uncaught-exception-handler! []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ _ ex]
       (util/print-err ex "uncaught exception")))))

(defn -main [& args]
  (set-uncaught-exception-handler!)
  (let [persistent? (some (fn [a] (= "--persistent_worker" a)) args)
        f (if persistent?
            (fn [_args] (process-persistent))
            process-ephemeral)]
    (f args)))
