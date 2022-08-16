(ns rules-clojure.worker
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [rules-clojure.jar :as jar]
            [rules-clojure.persistent-classloader :refer [new-classloader]])
  (:import java.nio.charset.StandardCharsets))

(s/def ::classes-dir string?) ;; path to the *compile-files* dir
(s/def ::output-jar string?) ;; path where the output jar should be written
(s/def ::srcs (s/coll-of string?)) ;; seq of paths to src files to put on classpath while compiling. Relative to src-dir
(s/def ::src-dir (s/nilable string?)) ;; path to root of source tree, relative to execroot
(s/def ::resources (s/coll-of string?)) ;; seq of paths to include in the jar
(s/def ::aot-nses (s/coll-of string?)) ;; seq of namespaces to AOT
(s/def ::compile-classpath (s/coll-of string?)) ;; seq of jars to put on compile classpath

(s/def ::compile-request (s/keys :req-un [::classes-dir
                                          ::output-jar
                                          ::classpath
                                          ::aot-nses]
                                 :opt-un [::resources
                                          ::srcs
                                          ::src-dir]))

(def ^:const RUNTIME_SHIM_CLASS
  "org.projectodd.shimdandy.impl.ClojureRuntimeShimImpl")

(defn all-dep-map-jars [dep-map]
  (apply set/union (set (keys dep-map)) (set (vals dep-map))))

(defn all-classpath-jars [classpath]
  (set classpath))

(defn print-err [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn compile-env
  "Create a new compile env. The classpath must contain ShimDandy impl and Clojure"
  [classpath]
  {:pre [(s/valid? (s/coll-of string?) classpath)]
   :post [(:shimdandy %)
          (:classloader %)]}
  (let [classloader (new-classloader classpath)]
    {:classloader classloader
     :shimdandy  (try
                   (doto (.newInstance (.loadClass classloader RUNTIME_SHIM_CLASS))
                    (.setClassLoader classloader)
                    (.setName (name (gensym "compiler")))
                    (.init))
                   (catch ClassNotFoundException e
                     (println "compile env failed:")
                     (println e)
                     (throw e)))}))

(defn shim-invoke
  ([env f]
   (.invoke (:shimdandy env) f))
  ([env f arg]
   (assert (:shimdandy env))
   (assert f)
   (.invoke (:shimdandy env) f arg)))

(defn shim-require [env ns]
  (.require (:shimdandy env) (into-array String [ns])))

(defn validate! [spec val]
  (let [explain (s/explain-data spec val)]
    (if explain
      (throw (ex-info "validation failed:" {:spec spec
                                            :value val
                                            :explain explain}))
      val)))

(defn contains-clojure? [jars]
  (some (fn [j]
          (re-find #"org/clojure/clojure" j)) jars))

(defn process-request
  [json]
  (validate! ::compile-request json)

  (let [env (compile-env (:classpath json))]
    (shim-require env "clojure.core")
    (try
      (let [compile-script (jar/get-compilation-script-json json)
            read-script (shim-invoke env "clojure.core/read-string" compile-script)
            compile-ret (shim-invoke env "clojure.core/eval" read-script)]
        (jar/create-jar-json json)))))

(defn process-ephemeral [args]
  (if (seq args)
    (let [req-json (json/read-str (slurp (io/file (.substring (last args) 1))) :key-fn keyword)]
      (process-request req-json))
    (println "WARNING no args")))

(defn process-persistent-1 [{:keys [arguments requestId]}]
  (let [baos (java.io.ByteArrayOutputStream.)
        out-printer (java.io.PrintWriter. baos true StandardCharsets/UTF_8)
        real-out *out*]
    (binding [*out* out-printer]
      (let [exit (try
                   (process-request arguments)
                   ;; (binding [*out* *err*]
                   ;;   (println (str baos)))
                   0
                   (catch Throwable t
                     (println t) ;; print to bazel str
                     ;; (binding [*out* *err*]
                     ;;   ;; also print to stderr
                     ;;   (println (str baos)))
                     1)
                   ;; (catch Throwable t
                   ;;   (print-err t)
                   ;;   (throw t))
                   )
            _ (.flush out-printer)
            resp (merge
                  {:exit_code exit
                   :output (str baos)}
                  (when requestId
                    {:requestId requestId}))]
        (print-err "writing" resp)
        (.write real-out (json/write-str resp))
        (.flush real-out)))))

(defn keywordize-keys
  "keyword-ize toplevel keys in a map. non-recursive"
  [m]
  (->> m
       (map (fn [[k v]]
              [(keyword k) v]))
       (into {})))


(defn new-work-pool [{:keys [num-processors]}]
  (let [queue (java.util.concurrent.ArrayBlockingQueue. (* num-processors 2))
        executor (java.util.concurrent.ThreadPoolExecutor. num-processors
                                                           num-processors
                                                           1
                                                           java.util.concurrent.TimeUnit/SECONDS
                                                           queue
                                                           (reify java.util.concurrent.RejectedExecutionHandler
                                                             (rejectedExecution [this runnable executor]
                                                               (print-err "rejected")
                                                               (assert false))))]
    {:queue queue
     :executor executor}))

(defn process-persistent []
  (let [num-processors (-> (Runtime/getRuntime) .availableProcessors)
        {:keys [queue executor]} (new-work-pool {:num-processors num-processors})]
    (loop []
      (let [work-req-str (read-line)
            work-req (json/read-str work-req-str :key-fn keyword)
            _ (print-err "work-req:" work-req)
            {:keys [arguments requestId]} work-req
            req-json (when work-req
                       (json/read-str (first arguments)))
            req-json (keywordize-keys req-json)]
        (if req-json
          (do
            (.execute executor (fn []
                                 (process-persistent-1 {:arguments req-json
                                                        :requestId requestId})))
            (recur))
          (println "no request, exiting"))))))

(defn set-uncaught-exception-handler! []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ _ ex]
       (print-err ex "uncaught exception")))))

(defn -main [& args]
  (let [persistent? (some (fn [a] (= "--persistent_worker" a)) args)
        f (if persistent? (fn [_args] (process-persistent)) process-ephemeral)]
    (f args)))
