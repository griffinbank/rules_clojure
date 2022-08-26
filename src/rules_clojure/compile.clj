(ns rules-clojure.compile
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import java.util.regex.Pattern))

(defn deftype? [ns v]
  (and (class? v)
       (-> v
           (.getName)
           (clojure.string/starts-with? (munge (name ns))))
       (= (count (clojure.string/split (.getName v) #"\."))
          (inc (count (clojure.string/split (name ns) #"\."))))))

(defn protocol? [val]
  (and (map? val)
       (class? (:on-interface val))
       (map? (:sigs val))
       (map? (:method-map val))))

(defn contains-protocols? [ns]
  (assert (find-ns ns) (print-str ns "is not loaded"))
  (->> ns
       ns-interns
       vals
       (map (fn [x]
              (if (var? x)
                (deref x)
                x)))
       (some protocol?)))

(defn contains-deftypes? [ns]
  (->> ns
       ns-map
       vals
       (some (fn [v]
               (deftype? ns v)))))

(defn compile-path-files []
  (-> *compile-path*
      io/file
      file-seq
      (->>
       (filter (fn [f]
                 (.isFile f)))
       (rest))))

(defn compile-path-classes []
  "convert the list of compiled classes files in *compile-path* to class names"
  []
  (->> (compile-path-files)
       (map (fn [p]
              (-> p
                  (str/replace (Pattern/compile (str "^" *compile-path* "/")) "")
                  (str/replace #".class$" "")
                  (str/replace #"/" ".")
                  (Compiler/demunge ))))))

(defn aot-class-name [ns]
  (str (.substring (#'clojure.core/root-resource ns) 1) "__init.class"))

(defn src-resource-name [ns]
  (.substring (#'clojure.core/root-resource ns) 1))

(defn src-resource [ns]
  (->> [".clj" ".cljc"]
       (map (fn [ext]
              (let [src-path (str (src-resource-name ns) ext)
                    src-resource (clojure.java.io/resource src-path)]
                (when src-resource
                  [src-path src-resource]))))
       (filter identity)
       (first)))

(defn get-dcl-cache []
  (let [cache-f (.getDeclaredField clojure.lang.DynamicClassLoader "classCache")
        _ (.setAccessible cache-f true)
        dcl (clojure.lang.RT/baseLoader)]
    (.get cache-f dcl)))

(defn get-dcl-refqueue []
  (let [refqueue-f (.getDeclaredField clojure.lang.DynamicClassLoader "rq")
        _ (.setAccessible refqueue-f true)
        dcl (clojure.lang.RT/baseLoader)]
    (.get refqueue-f dcl)))

(defn clear-dcl-refqueue []
  (clojure.lang.Util/clearCache (get-dcl-refqueue) (get-dcl-cache)))

(defn get-namespaces []
  (let [namespaces-f (.getDeclaredField clojure.lang.Namespace "namespaces")
        _ (.setAccessible namespaces-f true)]
    (.get namespaces-f clojure.lang.Namespace)))

(defn system-classpath
  "Returns a sequence of File paths from the 'java.class.path' system
  property."
  []
  (map #(java.io.File. ^String %)
       (.split (System/getProperty "java.class.path")
               (System/getProperty "path.separator"))))

(defn classpath []
  (->> (or (seq
            (mapcat (fn [x] (when (instance? java.net.URLClassLoader x) (seq (.getURLs x))))
                    (take-while identity (iterate #(.getParent %) (clojure.lang.RT/baseLoader)))))
           (system-classpath))
       (map str)))

(defn remove-ns! [ns]
  (when (get @@#'clojure.core/*loaded-libs* ns)
    (println "remove-ns" ns))
  (.remove (get-namespaces) ns)
  (dosync (alter @#'clojure.core/*loaded-libs* disj ns)))

(defn with-cleanup! [ns f]
  (let [data-readers-pre *data-readers*
        dcl-cache (get-dcl-cache)
        dcl-cache-pre (into {} dcl-cache)
        namespaces (get-namespaces)
        namespaces-pre (into {} namespaces)]
    (assert (not (seq (compile-path-files))) (print-str "*compile-path* not empty:" *compile-path* (compile-path-files)))

    ;; previous compiles sometimes cause `ns` to be loaded
    (remove-ns! ns)

    (clear-dcl-refqueue)

    (println "compile" ns)
    (f)

    (let [libs-pre (count @@#'clojure.core/*loaded-libs*)
          dcl-cache-post (into {} dcl-cache)
          namespaces-post (into {} namespaces)

          dcl-to-remove (set/difference (set (keys dcl-cache-post)) (set (keys dcl-cache-pre)))
          namespaces-to-remove (conj (set/difference (set (keys namespaces-post)) (set (keys namespaces-pre)))
                                     ;; the ns might already be loaded, but we should always remove it
                                     ns)]
      (doseq [k dcl-to-remove]
        (.remove dcl-cache k))

      (assert (= (count dcl-cache-pre)
                 (count dcl-cache)))

      (when (> (count namespaces-to-remove) 1)
        ;; we really should be removing exactly one namespace per
        ;; compile, but some code does non-standard things like
        ;; requiring at the toplevel which can break that assumption
        (println "WARNING removing multiple namespaces:" namespaces-to-remove))
      (doseq [k namespaces-to-remove]
        (remove-ns! k))

      (clear-dcl-refqueue)
    (alter-var-root #'*data-readers* (constantly data-readers-pre)))))

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

(defn unconditional-compile
  "the clojure compiler works by binding *compile-files* true and then
  calling `load`. `load` looks for both the source file and .class. If
  the .class file is present it is loaded as a normal java class. If
  the src file is present , the compiler runs, and .class files are
  produced as a side effect of the load. If both are present, the
  newer one is loaded.

  If the .class file is loaded, the compiler will not run and no
  .class files will be produced. Work around that by bypassing RT/load
  and calling Compiler/compile directly"
  [ns]
  (let [[src-path src-resource] (src-resource ns)]
    (assert src-resource)
    (try

      (with-open [rdr (clojure.java.io/reader src-resource)]
        (binding [*out* *err*
                  *compile-files* true]
          (clojure.lang.Compiler/compile rdr src-path (-> src-path (clojure.string/split #"/") last))))
      (catch Exception e
        (throw (ex-info "while compiling" {:classpath (classpath)} e))))))

(defn non-transitive-compile [dep-nses ns]
  {:pre [(every? symbol? dep-nses)
         (symbol? ns)
         (not (contains? (set dep-nses) ns))]}

  (when (seq dep-nses)
    (apply require dep-nses))

  (assert (not (seq (compile-path-files))) (print-str "non-empty compile-path:" ns *compile-path* (seq (compile-path-files))))
  (let [aot-class-resource (clojure.java.io/resource (aot-class-name ns))
        loaded (loaded-libs)]
    (unconditional-compile ns)
    (assert (seq (compile-path-files)) (print-str "no classfiles generated for" ns *compile-path* "loaded:" loaded))))

(defn non-transitive-compile-json [dep-nses ns]
  (let [baos (java.io.ByteArrayOutputStream.)
        out-printer (java.io.PrintWriter. baos true java.nio.charset.StandardCharsets/UTF_8)]
    (binding [*out* out-printer
              *err* out-printer]
      (non-transitive-compile dep-nses ns)
      (.flush out-printer)
      (str (str baos)))))
