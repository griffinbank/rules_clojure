(ns rules-clojure.persistent-classloader
  (:require [clojure.data]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [clojure.string :as str]
            [rules-clojure.persistentClassLoader]
            [rules-clojure.fs :as fs]
            [rules-clojure.namespace.find :as find]
            [rules-clojure.namespace.parse :as parse]
            [rules-clojure.util :as util :refer [debug]])
  (:import java.net.URL
           rules_clojure.persistentClassLoader
           [java.util.jar JarFile JarEntry]
           [java.io InputStream PushbackReader]
           java.lang.ref.SoftReference))

;; We want a clean, deterministic build. The naive way to do that is
;; to construct a new URLClassloader containing exactly what the user
;; requested for each build. Unfortunately, that's too slow, because
;; every time we construct a new classloader, the code has to be
;; reloaded. Using caching to remove reloads as much as possible.


;;; Compatibility

;; We want to reuse classloaders as much as possible because it's
;; faster and uses less memory. We want to avoid jarhell. We want
;; builds to be self-contained and reproducible.

;; To avoid jarhell, a classpath should have at most one path (a jar
;; or directory), containing any given namespace or .class. Two
;; classpaths are _compatible_ if for every namespace/class in A, the
;; file is either present in B with the same path and same
;; shasum/bazelsum, or not present.

;; If we have a cached classloader `A` and a request arrives with
;; classpath B, aot-ns of `bar` and an input file of `src/bar.clj`. We
;; can reuse `A` to process `B` IIF:

;; - the two classloaders are compatible
;; - `bar` is not AOT'd in `A`

;; To keep the cache warm, we pick the largest compatible classpath
;; (largest defined as the one containing the most
;; namespaces/classes). We use SoftReferences to allow unused
;; classloaders to GC

;; Also note that directories (and files within) are mutable over time
;; as requests arrive. To avoid mutation problems, only check for
;; compatibility when appending to a classpath.


(defn soft-ref? [x]
  (instance? SoftReference x))

(defn classloader? [x]
  (instance? ClassLoader x))

(defn new-classloader-
  ([cp]
   (new-classloader- cp (.getParent (ClassLoader/getSystemClassLoader))))
  ([cp parent]
   {:pre [(every? string? cp)
          (classloader? parent)]}
   (debug "new classloader" cp)
   (persistentClassLoader.
    (into-array URL (map #(.toURL (io/file %)) cp))
    parent)))

(defn add-url [cl p]
  {:pre [p]}
  (let [pf (io/file p)]
    (assert (.exists pf) (print-str p "not found"))
    (.addURL cl (.toURL pf))))

(defn jar? [path]
  (re-find #".jar$" path))

(defprotocol ClassLoaderFactory
  (build [this args]
    "Given a classpath, return a classloader that contains it. Factories
will vary in e.g. caching and reuse strategies"))

(defn slow-naive
  "Use a new classloader for every compile. Works. Slow."
  []
  (reify ClassLoaderFactory
    (build [_this {:keys [classpath]}]
      (assert (seq classpath))
      (new-classloader- classpath))))

(defn dirty-fast
  "Use a single classloader for all compiles, and always use. Works as
long as all jar sets are compatible with each other"
  []
  (let [dirty-classloader (new-classloader- [])]
    (reify ClassLoaderFactory
      (build [_ {:keys [classpath]}]
        (assert (seq classpath))
        (doseq [p classpath]
          (add-url dirty-classloader p))
        dirty-classloader))))

(s/def ::input-map (s/map-of string? string?))

(defn shas? [m]
  (and (map? m)
       (every? (fn [[k v]]
                 (and (or (string? k) (symbol? k))
                      (string? v)
                      (= 88 (count v)))) m)))

(defn conflicting-keys
  "given two maps, return a seq of conflicts (same key in both maps with different values)"
  [m1 m2]
  (let [[smaller larger] (if (< (count m1) (count m2))
                           [m1 m2]
                           [m2 m1])]
    (->> smaller
         (keep (fn [[k1 v1]]
                (when-let [[_k2 v2] (find larger k1)]
                  (when (not= v1 v2)
                    [k1 [v1 v2]])))))))

(defn compatible-maps?
  "Do all intersecting keys have the same values in both m1 and m2?"
  [m1 m2]
  (not (seq (conflicting-keys m1 m2))))

(def bazelsum (memoize fs/bazelsum))

(def clj-extensions #{"cljc" "clj"})

(defn dir-shas- [dir]
  {:pre [(string? dir)]
   :post [(shas? %)]}
  (->> (fs/ls-r (fs/->path dir))
       (filter (fn [path]
                 (and (contains? clj-extensions (fs/extension path))
                      (.isFile (fs/path->file path)))))
       (pmap (fn [path]
               (with-open [rdr (PushbackReader.
                                (io/reader
                                 (fs/path->file path)))]
                 (when-let [name (parse/name-from-ns-decl (parse/read-ns-decl rdr))]
                   [name (bazelsum path)]))))
       (into {})))

(def dir-shas (memoize dir-shas-))

(defn jar-shas-
  ;; take bazel-hash for cache-busting
  [jar hash]
  {:pre [(string? jar)
         (string? hash)]
   :post [(shas? %)]}
  (when (.exists (io/file jar))
    (let [jarfile (JarFile. jar)]
      (->> jarfile
           (.entries)
           (enumeration-seq)
           (filter (fn [entry]
                     (let [name (.getRealName entry)]
                       (and (not (.isDirectory ^JarEntry entry))
                          ;; many unrelated jars have conflicting META-INF
                            (not (re-find #"^META-INF" name))
                          ;; ignore single-segment files such as project.clj, deps.edn, data_readers.clj
                            (re-find #"/" name)
                          ;; ignore clj anonymous expressions
                            (not (re-find #"\$" name))
                            (re-find #"(.clj|.cljc|.class)$" name)))))
           (pmap (fn [^JarEntry entry]
                   [(.getRealName entry)
                    (-> (.getInputStream jarfile entry)
                        InputStream/.readAllBytes
                        fs/bazel-hash)]))
           (into {})))))

(def jar-shas (memoize jar-shas-))

(defn shas
  "Given a single classpath entry (jar or directory), return (map-of ns hash)"
  [path hash]
  {:pre [(do (when-not (string? path)
               (debug "pcl/shas:" path)) true)
         (string? path)]
   :post [(shas? %)]}
  (if (.endsWith ^String path ".jar")
    (jar-shas path hash)
    (dir-shas path)))

(s/def ::classpath (s/coll-of string? :kind sequential?))

(defn ns->class-resource-name
  "given a namespace symbol, return the name of classfile that will load it"
  [ns]
  (-> ns
      (munge)
      (str/replace #"\." "/")
      (str "__init.class")))

(defn compiled?
  "Is ns compiled in classloader?"
  [ns cl]
  ;; We could use Class/forName, but that would attempt to load the
  ;; class. Use resource instead to avoid the side effect
  (io/resource (ns->class-resource-name ns) cl))

(defn in-jar?
  "given a resource, return true if it loads from a jar"
  [^URL r]
  (= "jar" (.getProtocol r)))

(defn compiled-in-jar?
  "is the ns compiled, and the class is loaded from a jar"
  [ns cl]
  {:pre [(symbol? ns)]}
  (if-let [r (compiled? ns cl)]
    (in-jar? r)
    false))

(def println-memo (memoize (fn [& args]
                             (apply println args))))

(defn explode-inputs
  "Given an input-map containing .clj files and .jar files, list the contents of jars and `merge` in .jar clj and class files"
  [input-map]
  {:post [(if (some (fn [[k v]]
                      (.endsWith k ".jar")) input-map)
            (> (count %) (count input-map))
            (= % input-map))]}
  (->> input-map
       (mapcat (fn [[path hash]]
                 (if (.endsWith ^String path ".jar")
                   (concat [[path hash]] (jar-shas path hash))
                   [[path hash]])))
       (into {})))

(s/def ::classpaths (s/coll-of ::classpath :kind sequential?))

(defn ns-loaded? [cl ns]
  (util/shim-require cl 'rules-clojure.compile)
  (util/shim-invoke cl "rules-clojure.compile" "loaded?-str" (str ns)))

(defn get-best-classpath
  "Given a seq of existing cached classloaders, return the best match, or nil if none are compatible"
  [cached input-map aot-nses]
  (let [desired-in (explode-inputs input-map)
        cache (->> cached
                   (filter (fn [[in cache]]
                             ;; TODO conflicting source files are
                             ;; acceptable, conflicting classfiles are
                             ;; not.
                             (compatible-maps? in desired-in)))
                   (remove (fn [[in cache]]
                             ;; compiled in a directory is a temp dir
                             ;; that the rules-clojure.compile/cache
                             ;; can reuse. Compiled in a jar is an
                             ;; artifact that is incompatible or bazel
                             ;; wouldn't have asked us to compile
                             (some (fn [ns]
                                     {:post [(do (when %
                                                   (println-memo "not reusing classloader because" ns "already compiled")) true)]}
                                     (compiled-in-jar? (symbol ns) (:classloader cache))) aot-nses)))
                   (sort-by (comp count first))
                   (last))]
    (when (not cache)
      (->> cached
           (mapv (fn [[in _cl]]
                   (println-memo "conflict" (first (conflicting-keys in desired-in)))))))
    cache))

(defn deref-cache [caches]
  {:pre [(map? caches)
         (every? (fn [[_k v]]
                   (soft-ref? (:classloader v))) caches)]
   :post [(map? %)
          (every? (fn [[_k v]]
                    (classloader? (:classloader v))) %)
          (do (when (< (count %) (count caches))
                (debug "pcl:deref-cache GC'd" (- (count caches) (count %)))) true)]}
  (->> caches
       (map (fn [[cp cache]]
              [cp (update cache :classloader SoftReference/.get)]))
       (filter (fn [[_cp cl]]
                 (:classloader cl)))
       (into {})))

(defn soft-ref-cache [caches]
  {:pre [(map? caches)
         (every? (fn [[_k v]]
                   (classloader? (:classloader v))) caches)]
   :post [(map? %)
          (every? (fn [[_k v]]
                    (soft-ref? (:classloader v))) %)]}
  (->> caches
       (map (fn [[cp cache]]
              [cp (update cache :classloader SoftReference/new)]))
       (into {})))

(defn keep-n
  "Keep at most N caches"
  [caches n]
  (let [caches (sort-by (fn [[k _]] (count k)) caches)
        [expire keep] (split-at (- (count caches) n) caches)]
    (doseq [[_cp cache] expire]
      (util/shim-invoke (:classloader cache) "clojure.core" "shutdown-agents"))
    (into {} keep)))

(defn ensure-classloader [*caches desired-cp input-map aot-nses]
  (let [*cl (promise)]
    (locking *caches
      (swap! *caches (fn [caches]
                       ;; sanity check to prevent unbounded caching
                       ;; {:post [(< (count %) 5)]}
                       (let [caches (deref-cache caches)
                             [in cache] (get-best-classpath caches input-map aot-nses)]
                         (soft-ref-cache
                          (if cache
                            (let [desired-cp (set desired-cp)
                                  cache-cp (:classpath cache)
                                  new-paths (set/difference desired-cp cache-cp)
                                  new-in (merge in (explode-inputs input-map))
                                  cl (:classloader cache)]
                              (doseq [p new-paths]
                                (add-url cl p))
                              (deliver *cl cl)
                              (-> caches
                                  (dissoc in)
                                  (assoc new-in cache)
                                  ;; (keep-n 5)
                                  ))
                            (let [cl (new-classloader- desired-cp)
                                  in (explode-inputs input-map)]
                              (debug "new cl" (inc (count caches)))
                                ;; imagine
                              ;; (shim-require 'rules-clojure.compile)
                              ;; (shim-eval 'compile/compile!)

                              ;; require loads forms sequentially, but updates
                              ;; clojure.core/*loaded-libs* at the end of the ns block (not the end of the file!).

                              ;; If two compile requests in two
                              ;; threads come in at the same time, one
                              ;; starts the require and evaluating,
                              ;; the second one will see *loaded-libs*
                              ;; does contain rules-clojure.compile,
                              ;; but the
                              ;; `rules-clojure.compile/compile!` var
                              ;; hasn't been loaded yet. Load it here
                              ;; so we don't have to lock later.
                              (util/shim-require cl 'rules-clojure.compile)
                              (deliver *cl cl)
                              (assoc caches in {:classpath desired-cp
                                                :classloader cl}))))))))
    @*cl))

(defn caching
  "Cache and reuse the classloader, IIF if the inputs are compatible"
  []
  ;; (atom-of (map-of input-map (soft-ref classloader))).
  (let [*caches (atom {})]
    (reify ClassLoaderFactory
      (build [_ {:keys [classpath input-map aot-nses]}]
        (ensure-classloader *caches classpath input-map aot-nses)))))
