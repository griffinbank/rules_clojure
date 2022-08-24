(ns rules-clojure.worker
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [rules-clojure.jar :as jar]
            [rules-clojure.persistent-classloader :as pcl ])
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

(defn print-err [& args]
  (binding [*out* *err*]
    (apply println args)))

(defmacro with-context-classloader [cl & body]
  `(let [old-cl# (.getContextClassLoader (Thread/currentThread))]
     (try
       (.setContextClassLoader (Thread/currentThread) ~cl)
       ~@body
       (finally
         (.setContextClassLoader (Thread/currentThread) old-cl#)))))

(defn bind-compiler-loader [cl]
  (with-context-classloader cl
    (let [compiler (.loadClass cl "clojure.lang.Compiler")
          var (.loadClass cl "clojure.lang.Var")
          loader-f (.getDeclaredField compiler "LOADER")
          loader (.get loader-f compiler)
          bind-root-m (.getDeclaredMethod var "bindRoot" (into-array Class [Object]))]
      (println "bind compiler loader: loader" loader)
      (.invoke bind-root-m loader (into-array Object [cl])))))

(defn shim-init [cl]
  (with-context-classloader cl
    (let [rt (.loadClass cl "clojure.lang.RT")
         init-m (.getDeclaredMethod rt "init" (into-array Class []))]
     (.invoke init-m rt (into-array Object [])))))

(defn shim-var [cl ns name]
  (with-context-classloader cl
    (let [Clj (.loadClass cl "clojure.java.api.Clojure")
          m (.getDeclaredMethod Clj "var" (into-array Class [Object Object]))]
      (.invoke m Clj (into-array Object [ns name])))))

(defn shim-invoke
  [cl ns name & args]
  (with-context-classloader cl
    (let [v (shim-var cl ns name)
          ifn (.loadClass cl "clojure.lang.IFn")
          m (.getDeclaredMethod ifn "invoke" (into-array Class (take (count args) (repeat Object))))]
      (.invoke m v (into-array Object args)))))

(defn shim-deref [cl ns name]
  (with-context-classloader cl
    (let [v (shim-var cl ns name)
          ifn (.loadClass cl "clojure.lang.IFn")
          m (.getDeclaredMethod ifn "deref" (into-array Class []))]
      (.invoke m v))))

(defn shim-eval [cl s]
  (with-context-classloader cl
    (let [script (shim-invoke cl "clojure.core" "read-string" s)]
      (shim-invoke cl "clojure.core" "eval" script))))

(defn compile-env
  "Create a new compile env. The classpath must contain Clojure"
  [classpath]
  {:pre [(s/valid? (s/coll-of string?) classpath)]}
  (let [cl (pcl/new-classloader classpath)]
    ;; we need to force c.l.RT to load before we can bind the compiler loader var
    (shim-deref cl "clojure.core" "*clojure-version*")
    (bind-compiler-loader cl)

    cl))

(defn shim-require [cl ns]
  (shim-invoke cl "clojure.core" "require" ns))

(defn validate! [spec val]
  (let [explain (s/explain-data spec val)]
    (if explain
      (throw (ex-info "validation failed:" explain))
      val)))

(defn process-request
  [req]
  (validate! ::compile-request req)
  (let [cl (pcl/new-classloader (:classpath req))
        compile-script (jar/get-compilation-script-json req)]
    (print-err "script:" compile-script)
    (try
      (let [ret (shim-eval cl compile-script)]
        (jar/create-jar-json req))
      (catch Exception e
        (throw (ex-info "exception while compiling" req e))))))

(defn process-ephemeral [args]
  (let [req-json (json/read-str (slurp (io/file (.substring (last args) 1))) :key-fn keyword)]
    (process-request req-json)))

(defn process-persistent-1 [{:keys [arguments requestId]}]
  (let [baos (java.io.ByteArrayOutputStream.)
        out-printer (java.io.PrintWriter. baos true StandardCharsets/UTF_8)
        real-out *out*]
    (let [exit (binding [*out* out-printer]
                 (try
                   (let [compile-req (json/read-str (first arguments) :key-fn keyword)]
                     (process-request compile-req))
                   0
                   (catch Throwable t
                     (println t) ;; print to bazel str
                     1)))
          _ (.flush out-printer)
          resp (merge
                {:exit_code exit
                 :output (str baos)}
                (when requestId
                  {:requestId requestId}))]
      (print-err "writing" (json/write-str resp) "to" real-out)
      (.write real-out (json/write-str resp))
      (.write real-out "\n")
      (.flush real-out))))

(defn process-persistent []
  (let [num-processors (-> (Runtime/getRuntime) .availableProcessors)
        executor ;; (java.util.concurrent.Executors/newSingleThreadExecutor)
        (java.util.concurrent.Executors/newWorkStealingPool num-processors)
        ]
    (loop []
      (print-err "reading from *in*")
      (if-let [work-req (json/read-str (read-line) :key-fn keyword)]
        (let [out *out*
              err *err*]
          (.submit executor ^Runnable (fn []
                                        (binding [*out* out
                                                  *err* err]
                                          (process-persistent-1 work-req))))
          (recur))
        (do
          (print-err "no request, exiting")
          (.shutdown executor)
          :exit)))))

(defn set-uncaught-exception-handler! []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ _ ex]
       (print-err ex "uncaught exception")))))

(defn -main [& args]
  (set-uncaught-exception-handler!)
  (let [persistent? (some (fn [a] (= "--persistent_worker" a)) args)
        f (if persistent?
            (fn [_args] (process-persistent))
            process-ephemeral)]
    (f args)))
