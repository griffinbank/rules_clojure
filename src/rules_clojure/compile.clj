(ns rules-clojure.compile
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [rules-clojure.fs :as fs]
            [rules-clojure.util :as util])
  (:import java.util.regex.Pattern
           clojure.lang.Compiler))

(defn deftype? [ns v]
  (and (class? v)
       (-> v
           (.getName)
           (clojure.string/starts-with? (munge (name ns))))
       (= (count (clojure.string/split (.getName v) #"\."))
          (inc (count (clojure.string/split (name ns) #"\."))))))

(defn contains-protocols? [ns]
  (assert (find-ns ns) (print-str ns "is not loaded"))
  (->> ns
       ns-interns
       vals
       (some (fn [x]
               (try
                 (and
                  (var? x)
                  (let [v (deref x)]
                    (and (map? v)
                         (#'clojure.core/protocol? v))))
                 (catch Exception e
                   (throw (ex-info (print-str "while examining" x (class x)) {:x x} e))))))))

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

(defn compile-path-classes
  "convert the list of compiled classes files in *compile-path* to class names"
  []
  (->> (compile-path-files)
       (map (fn [p]
              (-> p
                  (str/replace (Pattern/compile (str "^" *compile-path* "/")) "")
                  (str/replace #".class$" "")
                  (str/replace #"/" ".")
                  (Compiler/demunge))))))

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

(defn get-namespaces []
  (let [namespaces-f (.getDeclaredField clojure.lang.Namespace "namespaces")
        _ (.setAccessible namespaces-f true)]
    (.get namespaces-f clojure.lang.Namespace)))

(defn print-err [& args]
  (binding [*out* *err*]
    (apply println args)))

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
    (assert src-resource (print-str "couldn't find a .clj or .cljc file for" ns "on classpath"))
    (try
      (with-open [rdr (clojure.java.io/reader src-resource)]
        (binding [*out* *err*
                  *compile-files* true]
          (clojure.lang.Compiler/compile rdr src-path (-> src-path (clojure.string/split #"/") last))))
      (catch Exception e
        (throw (ex-info "while compiling" {:ns ns
                                           :classpath (util/classpath)} e))))))

(defn non-transitive-compile [dep-nses ns]
  {:pre [(every? symbol? dep-nses)
         (symbol? ns)
         (not (contains? (set dep-nses) ns))]}

  (when (seq dep-nses)
    (apply require dep-nses))

  (let [loaded (loaded-libs)]
    (unconditional-compile ns)
    (assert (seq (compile-path-files)) (print-str "no classfiles generated for" ns *compile-path* "loaded:" loaded))))

(defn non-transitive-compile-json [dep-nses ns]
  (let [baos (java.io.ByteArrayOutputStream.)
        out-printer (java.io.PrintWriter. baos true java.nio.charset.StandardCharsets/UTF_8)]
    (binding [*out* out-printer
              *err* out-printer]
      (non-transitive-compile dep-nses ns)
      (.flush out-printer)
      (str baos))))
