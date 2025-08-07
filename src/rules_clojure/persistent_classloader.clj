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
            [rules-clojure.util :as util])
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
;; or directory), containing any given namespace. Two classpaths are
;; _compatible_ if for every namespace in A, it is either present in B
;; with the same path and same shasum/bazelsum, or not present.

;; If we have a cached classloader `A` and a request arrives with
;; classpath B, aot-ns of `bar` and an input file of `src/bar.clj`. We
;; can reuse `A` to process `B` IIF:

;; - every namespace in B is located at the same path in A with the
;; - same bazelsum, or not present

;; To keep the cache warm, we pick the largest compatible classpath
;; (largest defined as the one with the most namespaces). We use
;; SoftReferences to allow unused classloaders to GC

;; Also note that directories (and files within) are mutable over time
;; as requests arrive. To avoid mutation problems, only check for
;; compatibility when appending to a classpath.


(defn new-classloader-
  ([cp]
   (new-classloader- cp (.getParent (ClassLoader/getSystemClassLoader))))
  ([cp parent]
   {:pre [(every? string? cp)
          (instance? ClassLoader parent)]}
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

(defn compatible-maps?
  "Do all intersecting keys have the same values in both m1 and m2?"
  [m1 m2]
  (not-any?
   (fn [[k v]] (and (contains? m1 k) (not= (m1 k) v))) m2))

(defn incompatible-maps
  "When `compatible-maps?` returns true, return the conflicting values"
  [m1 m2]
  (reduce (fn [conflicting [k v2]]
            (let [v1 (get m1 k)]
              (if (and v1 (not= v2 v1))
                (assoc conflicting k [v1 v2])
                conflicting))) {} m2))

(s/def ::input-map (s/map-of string? string?))

(def bazelsum (memoize fs/bazelsum))

(def clj-extensions #{"cljc" "clj"})

(defn shas? [m]
  (and (map? m)
       (every? (fn [[k v]]
                 (and (symbol? k)
                      (string? v)
                      (= 88 (count v)))) m)))

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
  [jar]
  {:pre [(string? jar)]
   :post [(shas? %)]}
  (let [jarfile (JarFile. jar)]
    (-> jarfile
        (find/sources-in-jar find/clj)
        (->> (pmap (fn [^JarEntry entry]
                     (when-let [name (parse/name-from-ns-decl (find/read-ns-decl-from-jarfile-entry jarfile entry find/clj))]
                       [name
                        (-> (.getInputStream jarfile entry)
                            InputStream/.readAllBytes
                            fs/bazel-hash)])))
             (into {})))))

(def jar-shas (memoize jar-shas-))

(defn shas
  "Given a single classpath entry (jar or directory), return (map-of ns hash)"
  [path]
  {:pre [(do (when-not (string? path)
               (println "pcl/shas:" path)) true)
         (string? path)]}
  (if (.endsWith ^String path ".jar")
    (jar-shas path)
    (dir-shas path)))

(defn classpath-shas
  "Given a vector of classpath jars and dirs, return a map of `{container
{file sha}}`, where container is a jar or directory, and file is a
clojure source file in the container"
  [classpath]
  (doseq [p classpath]
    (assert (string? p) p))
  (->> classpath
       (map shas)
       (apply merge)))

(s/def ::classpath (s/coll-of string? :kind sequential?))

(defn ns->resource-name
  "given a namespace symbol, return the name of the resource where it can
be found"
  [ns]
  (-> ns
      (munge)
      (str/replace #"\." "/")
      (str "__init.class")))

(defn aot? [^ClassLoader cl ns]
  {:pre [(string? ns)]}
  (.getResource cl (ns->resource-name ns)))

(defn compatible-shas?
  [a b]
  (let [new-paths (set/difference (set b) (set a))
        a-shas (classpath-shas a)]
    (assert (seq a-shas))
    (every? (fn [path]
              (let [b-shas (shas path)
                    compatible (compatible-maps? a-shas b-shas)]
                compatible)) new-paths)))

(s/def ::classpaths (s/coll-of ::classpath :kind sequential?))

(defn get-best-classpath
  "Given a seq of existing cached classloaders, return the best match, or nil if none are compatible"
  [cached desired aot-nses]
  (->> cached
       (filter (fn [[cp _cl]]
                 (compatible-shas? cp desired)))
       (filter (fn [[_cp cl]]
                 (every? (fn [ns]
                           (not (aot? cl ns))) aot-nses)))
       ((fn [cps]
          (sort-by (fn [[cp _cl]]
                     (count (classpath-shas cp))) cps)))
       (last)))

(defn deref-cache [caches]
  {:pre [(map? caches)
         (every? (fn [[k v]]
                   (instance? SoftReference v)) caches)]
   :post [(map? %)
          (every? (fn [[k v]]
                    (instance? ClassLoader v)) %)
          (do (when (< (count %) (count caches))
                (util/print-err "pcl:deref-cache GC'd" (- (count caches) (count %)))) true)]}
  (->> caches
       (map (fn [[cp ref]]
              [cp (.get ref)]))
       (filter (fn [[_cp cl]]
                 cl))
       (into {})))

(defn soft-ref-cache [caches]
  {:pre [(map? caches)
         (every? (fn [[k v]]
                   (instance? ClassLoader v)) caches)]
   :post [(map? %)
          (every? (fn [[k v]]
                    (instance? SoftReference v)) %)]}
  (->> caches
       (map (fn [[cp cl]]
              [cp (SoftReference. cl)]))
       (into {})))


(defn ensure-classloader [*caches desired-cp aot-nses]
  (let [*cl (promise)]
    (locking *caches
      (swap! *caches (fn [caches]
                       ;; sanity check to prevent unbounded caching
                       ;; {:post [(< (count %) 5)]}
                       (let [caches (deref-cache caches)
                             _ (assert (map? caches))
                             [best-cp best-cl] (get-best-classpath caches desired-cp aot-nses)]
                         (soft-ref-cache
                          (if best-cl
                            (let [new-paths (set/difference (set desired-cp) (set best-cp))
                                  new-cp (concat best-cp new-paths)]
                              (doseq [p new-paths]
                                (add-url best-cl p))
                              (deliver *cl best-cl)
                              (-> caches
                                  (dissoc best-cp)
                                  (assoc new-cp best-cl)))
                            (let [cl (new-classloader- desired-cp)]
                              (deliver *cl cl)
                              (assoc caches desired-cp cl))))))))
    @*cl))

(defn caching
  "Cache and reuse the classloader, IIF if the inputs are compatible"
  []
  ;; (atom-of (map-of classpath (soft-ref classloader))).
  (let [*caches (atom {})]
    (reify ClassLoaderFactory
      (build [_ {:keys [classpath aot-nses]}]
        (ensure-classloader *caches classpath aot-nses)))))
