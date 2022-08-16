(ns rules-clojure.compile
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
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
         (->> ns
              ns-interns
              vals
              (some protocol?)))

(defn contains-deftypes? [ns]
  (->> ns
       ns-map
       vals
       (some (fn [v]
               (deftype? ns v)))))

(defn compile-path-files []
  (-> *compile-path*
      (clojure.java.io/file)
      file-seq
      (rest)))

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
    (with-open [rdr (clojure.java.io/reader src-resource)]
      (binding [*out* *err*
                *compile-files* true]
        (println "Compiler LOADER:" (clojure.lang.RT/baseLoader))
        (clojure.lang.Compiler/compile rdr src-path (-> src-path (clojure.string/split #"/") last))))))

(defn non-transitive-compile [dep-nses ns]
  {:pre [(every? symbol? dep-nses)
         (symbol? ns)
         (not (contains? (set dep-nses) ns))]}

  (when (seq dep-nses)
    (apply require dep-nses))

  (let [aot-class-resource (clojure.java.io/resource (aot-class-name ns))
        loaded (loaded-libs)]
    (try
      (if (not (contains? loaded ns))
        (do
          (unconditional-compile ns)
          (assert (seq (compile-path-files)) (print-str "no classfiles generated for" ns *compile-path* "loaded:" loaded))
          (when (or (contains-protocols? ns)
                    (contains-deftypes? ns))
            (str :rules-clojure.compile/reload)))
        (do
          (str :rules-clojure.compile/restart)))

      (catch Throwable t
        (throw (ex-info (print-str "while compiling" ns) {:loaded loaded} t))))))
