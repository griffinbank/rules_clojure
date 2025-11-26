(ns rules-clojure.worker
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [rules-clojure.fs :as fs]
            [rules-clojure.jar :as jar]
            [rules-clojure.util :as util :refer [print-err]]
            [rules-clojure.persistent-classloader :as pcl])
  (:import java.nio.charset.StandardCharsets)
  (:gen-class))

(s/def ::output-jar string?) ;; path where the output jar should be written
(s/def ::srcs (s/coll-of string?)) ;; seq of paths to src files to put on classpath while compiling. Relative to src-dir
(s/def ::src-dir (s/nilable string?)) ;; path to root of source tree, relative to execroot
(s/def ::resources (s/coll-of string?)) ;; seq of paths to include in the jar
(s/def ::aot-nses (s/coll-of string?)) ;; seq of namespaces to AOT
(s/def ::classpath (s/coll-of string?)) ;; seq of jars to put on compile classpath

(s/def ::compile-request (s/keys :req-un [::output-jar
                                          ::classpath
                                          ::aot-nses]
                                 :opt-un [::resources
                                          ::srcs
                                          ::src-dir]))

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

(def temp-dirs (atom #{}))

(defn src-dir-munge [req]
  ;; include the src-dir to avoid src1/foo/bar.clj conflicting with src2/foo/bar.clj
  (when-let [dir (:src-dir req)]
    (str/replace dir "/" ".")))

(defn process-request
  [{:keys [classloader-strategy
           input-map] :as req}]
  {:pre [(map? input-map)]}
  (util/validate! ::compile-request req)
  (assert classloader-strategy)
  (try
    (assert (:classpath req))
    (let [classes-dir (fs/new-temp-dir (apply str (interpose "+" (concat ["rules_clojure" (src-dir-munge req)] (:aot-nses req)))) )
          req (-> req
                  (update :classpath conj (str classes-dir))
                  (assoc :classes-dir (str classes-dir)))
          cl (pcl/build classloader-strategy (select-keys req [:classpath :input-map :aot-nses]))]
      (jar/compile! cl req)
      (jar/create-jar-json req)
      (swap! temp-dirs conj classes-dir))
    (catch Throwable t
      (throw (ex-info "worker: while compiling" req t)))))

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
                            :classloader-strategy (pcl/caching)
                            :input-map (classpath-input-map classpath)))))

(defn process-persistent-1 [{:keys [arguments requestId classloader-strategy inputs] :as _req}]
  (let [baos (java.io.ByteArrayOutputStream.)
        out-printer (java.io.PrintWriter. baos true StandardCharsets/UTF_8)
        real-out *out*
        *err (atom nil)]
    (let [exit (binding [*out* out-printer]
                 (try
                   (let [compile-req (json/read-str (first arguments) :key-fn keyword)]
                     (process-request (assoc compile-req
                                             :classloader-strategy classloader-strategy
                                             :input-map (input-map inputs))))
                   0
                   (catch Throwable t
                     (println t) ;; print to bazel str
                     (reset! *err t)
                     1)))
          _ (.flush out-printer)
          resp (merge
                {:exitCode exit
                 :output (str baos)}
                (when requestId
                  {:requestId requestId})
                (when @*err
                  {:error (print-str @*err)}))]
      (.write real-out (json/write-str resp))
      (.write real-out "\n")
      (.flush real-out))))

(defn process-persistent []
  (let [classloader-strategy (pcl/caching)
        *error (atom nil)
        *continue (atom true)]
    (loop []
      (if-let [line (and @*continue (read-line))]
        (let [work-req (json/read-str line :key-fn keyword)
              _ (util/print-err "got req" work-req)
              f (future (try
                           (process-persistent-1 (assoc work-req
                                                        :classloader-strategy classloader-strategy))
                           (catch Throwable t
                             (reset! *error t)
                             (reset! *continue false))))]
          (recur))
        (do
          (print-err "no request, exiting")
          (shutdown-agents)
          (when @*error
            (println @*error))
          :exit)))))

(defn set-uncaught-exception-handler! []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ _ ex]
       (println ex "uncaught exception")))))

(defn -main [& args]
  (set-uncaught-exception-handler!)
  (-> (Runtime/getRuntime) (.addShutdownHook (Thread. ^Runnable (fn []
                                                                  (doseq [d @temp-dirs]
                                                                    (fs/rm-rf d))))))
  (let [persistent? (some (fn [a] (= "--persistent_worker" a)) args)
        f (if persistent?
            (fn [_args] (process-persistent))
            process-ephemeral)]
    (f args)))
