(ns rules-clojure.worker
  (:require [clojure.set :as set]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [rules-clojure.fs :as fs]
            [rules-clojure.jar :as jar]
            [rules-clojure.util :as util]
            [rules-clojure.persistent-classloader :as pcl])
  (:import java.nio.charset.StandardCharsets
           java.util.concurrent.TimeUnit
           [java.util.logging ConsoleHandler FileHandler Logger Level SimpleFormatter]
           java.lang.ProcessHandle))

(s/def ::classes-dir string?) ;; path to the *compile-files* dir
(s/def ::output-jar string?) ;; path where the output jar should be written
(s/def ::srcs (s/coll-of string?)) ;; seq of paths to src files to put on classpath while compiling.
(s/def ::resources (s/coll-of string?)) ;; seq of paths to include in the jar
(s/def ::aot-nses (s/coll-of string?)) ;; seq of namespaces to AOT
(s/def ::classpath (s/coll-of string?)) ;; seq of jars to put on compile classpath

(s/def ::compile-request (s/keys :req-un [::classes-dir
                                          ::output-jar
                                          ::classpath
                                          ::aot-nses]
                                 :opt-un [::resources
                                          ::srcs]))

(s/def ::arguments (s/cat :c ::compile-req))

(s/def ::requestId nat-int?)
(s/def ::path string?)
(s/def ::digest string?)
(s/def ::input (s/keys :req [::path ::digest]))
(s/def ::inputs (s/coll-of ::input))
(s/def ::exitCode nat-int?)
(s/def ::output string?)

;; requestId is not present in singleplex mode, required in multiplex mode. We don't have a good way of detecting which mode we're running in, so mark it optional
(s/def ::work-request (s/keys :req-un [::arguments ::inputs] :opt-un [::requestId]))
(s/def ::work-response (s/keys :req-un [::exitCode ::output] :opt-un [::requestId]))

(defn all-dep-map-jars [dep-map]
  (apply set/union (set (keys dep-map)) (set (vals dep-map))))

(defn all-classpath-jars [classpath]
  (set classpath))

;; bazel requires us to write to stdout, and doesn't reliably report
;; stderr, so log to a temp file to guarantee we find everything.

(defn pid []
  (-> (ProcessHandle/current) .pid))

(defn configure-logging! []
  (let [handler (FileHandler. (format "/tmp/rules-clojure-worker-%s.log" (pid)))
        formatter (SimpleFormatter.)
        logger (Logger/getLogger (str *ns*))]
    (.setFormatter handler formatter)
    (.addHandler logger handler)
    (.addHandler logger (ConsoleHandler.))
    (.setLevel logger Level/INFO)))

(defn log [& args]
  (Logger/.log (Logger/getLogger (str *ns*)) Level/INFO (apply str args)))

(defn process-request
  [{:keys [classloader-strategy
           input-map] :as req}]
  {:pre [(map? input-map)]}
  (util/validate! ::compile-request req)
  (assert classloader-strategy)
  (let [compile-script (jar/get-compilation-script-json req)]
    (try
      (assert (:classpath req))
      (pcl/with-classloader classloader-strategy (select-keys req [:classpath :aot-nses :input-map])
        (fn [cl]
          (assert cl)
          (assert compile-script)
          (let [ret (util/shim-eval cl compile-script)]
            (when (seq ret)
              (println ret)))
          (jar/create-jar-json req)))
      (catch Throwable t
        (throw (ex-info "exception while compiling" {:request req
                                                     :script compile-script} t))))))

(defn input-map [inputs]
  (->> inputs
       (map (fn [{:keys [path digest]}]
              [path digest]))
       (into {})))

(defn classpath-input-map [classpath]
  {:post [(map? %)
          (every? (fn [[k v]]
                    (and (string? k) (string? v))) %)]}
  ;; bazel doesn't pass inputs to ephemeral jobs, so do it ourselves
  (->> classpath
       (mapcat (fn [path]
                 (let [path (fs/->path path)]
                   (if (fs/directory? (fs/path->file path))
                     (fs/ls-r path)
                     [path]))))
       (filter (fn [path]
                 (fs/normal-file? (fs/path->file path))))
       (map (fn [path]
              [(str path) (fs/shasum path)]))
       (into {})))

(defn process-ephemeral [args]
  (let [req-json (json/read-str (slurp (io/file (.substring (last args) 1))) :key-fn keyword)
        {:keys [classpath]} req-json]
    (process-request (assoc req-json
                            :classloader-strategy (pcl/slow-naive)
                            :input-map (classpath-input-map classpath)))))

(defn process-persistent-1 [{:keys [arguments requestId classloader-strategy inputs] :as work-req}]
  (let [baos (java.io.ByteArrayOutputStream.)
        out-printer (java.io.PrintWriter. baos true StandardCharsets/UTF_8)
        real-out *out*]
    (let [exit (binding [*out* out-printer]
                 (try
                   (process-request (assoc work-req
                                           :classloader-strategy classloader-strategy
                                           :input-map (input-map inputs)))
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
      (util/print-err "persistent done:" resp)
      (.write real-out (json/write-str resp))
      (.write real-out "\n")
      (.flush real-out))))

;; [--classes-dir bazel-out/darwin_arm64-fastbuild/bin/external/deps/.ns_metosin_reitit_core_reitit_exception.classes --output-jar bazel-out/darwin_arm64-fastbuild/bin/external/deps/ns_metosin_reitit_core_reitit_exception.jar --resource-strip-prefix '' --aot-ns reitit.exception --classpath external/deps/repository/metosin/reitit-core/0.6.0/reitit-core-0.6.0.jar:external/deps/repository/meta-merge/meta-merge/1.0.0/meta-merge-1.0.0.jar:external/deps/repository/org/clojure/clojure/1.12.2/clojure-1.12.2.jar:external/deps/repository/org/clojure/core.specs.alpha/0.4.74/core.specs.alpha-0.4.74.jar:external/deps/repository/org/clojure/spec.alpha/0.5.238/spec.alpha-0.5.238.jar:bazel-out/darwin_arm64-fastbuild/bin/external/rules_clojure/src/rules_clojure/libcompile.jar]

(defn parse-classpath [classpath-str]
  (str/split classpath-str #":"))

(def cli-options
  ;; An option with an argument
  [[nil "--classes-dir dir" "output directory where classfiles will be written"]
   [nil "--output-jar jar" "output jar name"]
   [nil "--resource-strip-prefix path" ]
   [nil "--aot-nses ns" "names of namespaces to AOT. May be repeated"
    :default []
    :update-fn conj
    :multi true]
   [nil "--classpath cp" "classpath to use while compiling, separated by :"
    :parse-fn parse-classpath]])

(defn parse-arguments [^String args]
  {:post [(do (log "worker parse-req" args "=>" %) true)]}
  (-> args
      (parse-opts cli-options)
      :options))

(defn process-persistent []
  (let [executor (java.util.concurrent.Executors/newWorkStealingPool)
        classloader-strategy (pcl/caching-threadsafe)]
    (loop []
      (log "blocking on read-line")
      (let [line (read-line)]
        (if (and line (seq line))
          (let [_ (log "persistent: line" line)
                work-req (json/read-str line :key-fn keyword)
                arguments (parse-arguments (:arguments work-req))
                prefix (:resource-strip-prefix arguments)
                _ (log "persistent: prefix:" prefix)
                arguments (if (seq prefix)
                            (update arguments :classpath (fn [classpath] (distinct (conj classpath prefix))))
                            arguments)
                work-req (-> work-req
                             (dissoc :arguments)
                             (merge arguments))]
            (log "persistent: req" work-req)
            (let [out *out*
                  err *err*]
              (.submit executor ^Runnable (fn []
                                            (binding [*out* out
                                                      *err* err]
                                              (process-persistent-1 (assoc work-req
                                                                           :classloader-strategy classloader-strategy)))))
              (recur)))
          (do
            (log "no request, exiting")
            (.shutdown executor)
            (log "awating task completion")
            (log "finished cleanly?" (.awaitTermination executor 60 TimeUnit/SECONDS))
            :exit))))))

(defn set-uncaught-exception-handler! []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ _ ex]
       (log ex "uncaught exception")))))

(defn -main [& args]
  (set-uncaught-exception-handler!)
  (configure-logging!)
  (let [persistent? (some (fn [a] (= "--persistent_worker" a)) args)
        f (if persistent?
            (fn [_args] (process-persistent))
            process-ephemeral)]
    (try
      (f args)
      (catch Exception e
        (log e)
        (throw e)))))
