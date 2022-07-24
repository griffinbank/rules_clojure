(ns rules-clojure.worker
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [rules-clojure.jar :as jar])
  (:import [java.net URL URLClassLoader]
           java.nio.charset.StandardCharsets))

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

(defonce compile-env (ref
                      {:classloader nil
                       :shimdandy nil}))

(defn print-err [& args]
  (binding [*out* *err*]
    (apply println args)))

(def ^:const RUNTIME_SHIM_CLASS
  "org.projectodd.shimdandy.impl.ClojureRuntimeShimImpl")

(defn ensure-compile-runtime
  "Create a new compile env. The initial classpath must contain ShimDandy impl and a Clojure"
  [classpath]
  {:pre [(seq classpath)]
   :post [(:shimdandy @compile-env)
          (:classloader @compile-env)]}
  (dosync
   (alter compile-env (fn [{:keys [classloader] :as env}]
                        (if (not classloader)
                          (let [classloader (URLClassLoader.
                                             (into-array URL (map #(.toURL (io/file %)) classpath))
                                             ;; This is the boot class loader, the highest classloader, and importantly
                                             ;; the one without Clojure in.
                                             (.getParent (ClassLoader/getSystemClassLoader)))]
                            {:classloader classloader
                             :shimdandy  (doto (.newInstance (.loadClass classloader RUNTIME_SHIM_CLASS))
                                           (.setClassLoader classloader)
                                           (.setName (name (gensym "compiler")))
                                           (.init))})
                          env)))))

(defn add-url [cl path]
  {:pre [(instance? URLClassLoader cl)]}
  (-> URLClassLoader (.getDeclaredMethod "addURL" (into-array Class [URL]))
      (doto (.setAccessible true)
        (.invoke cl (into-array Object [(-> path io/file .toURI .toURL)])))))

(defn ensure-compile-classpath [classpath]
  (ensure-compile-runtime classpath)
  (doseq [path classpath]
    (add-url (:classloader @compile-env) path)))

(defn shim-invoke
  ([f]
   (.invoke (:shimdandy @compile-env) f))
  ([f arg]
   (assert (:shimdandy @compile-env))
   (assert f)
   (.invoke (:shimdandy @compile-env) f arg)))

(defn shim-require [ns]
  (.require (:shimdandy @compile-env) (into-array String [ns])))

(defn discard-shim-env []
  (when (:shimdandy @compile-env)
    (shim-invoke "clojure.core/shutdown-agents")
    (.close (:classloader @compile-env)))
  (dosync
   (alter compile-env (constantly {}))))

(defn process-request
  ([json]
   (process-request json true))
  ([json can-restart?]
   {:pre [(s/valid? ::compile-request json)]}
   (ensure-compile-classpath (:classpath json))
   (shim-require "clojure.core")
   (try
     (let [compile-script (jar/get-compilation-script-json json)
           read-script (shim-invoke "clojure.core/read-string" compile-script)
           compile-ret (shim-invoke "clojure.core/eval" read-script)]
       (when (= ":rules-clojure.compile/restart" compile-ret)
         (discard-shim-env)
         (if can-restart?
           (process-request json false)
           (throw (ex-info "already restarted" {:json json :compile-script compile-script}))))
       (when (= ":rules-clojure.compile/reload" compile-ret)
         (discard-shim-env))
       (jar/create-jar-json json)))))

(defn process-ephemeral [args]
  (if (seq args)
    (let [req-json (json/read-str (slurp (io/file (.substring (last args) 1))) :key-fn keyword)]
      (process-request req-json))
    (print-err "WARNING no args")))

(defn process-persistent-1 [json]
  (let [baos (java.io.ByteArrayOutputStream.)
        out-printer (java.io.PrintWriter. baos true StandardCharsets/UTF_8)
        real-out *out*]
    (binding [*out* out-printer]
      (let [exit (try
                   (process-request json)
                   0
                   (catch Exception e
                     (print-err e)
                     1))
            resp {:exit_code exit
                  :output (str baos)}]
        (.write real-out (json/write-str resp))
        (.flush real-out)))))

(defn process-persistent [_args]
  (loop []
    (let [work-req-str (read-line)
          work-req (json/read-str work-req-str :key-fn keyword)
          {:keys [arguments]} work-req
          req-json (when work-req
                     (json/read-str (first arguments) :key-fn keyword))]
      (if req-json
        (do
          (process-persistent-1 req-json)
          (recur))
        (print-err "no request, exiting")))))

(defn -main [& args]
  (try
    (let [persistent? (some (fn [a] (= "--persistent_worker" a)) args)
          f (if persistent? process-persistent process-ephemeral)]
      (f args))
    (catch Throwable t
      (print-err t))))
