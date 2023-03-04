(ns rules-clojure.persistent-classloader
  (:require [clojure.data]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [rules-clojure.util :as util]
            [rules-clojure.persistentClassLoader])
  (:import [java.net URL URLClassLoader]
           java.util.jar.JarFile
           java.lang.ref.SoftReference
           rules_clojure.persistentClassLoader))

;; We want a clean, deterministic build. The naive way to do that is
;; to construct a new URLClassloader containing exactly what the user
;; requested for each build. Unfortunately, that's too slow, because
;; every time we construct a new classloader, the code has to be
;; reloaded. Using caching to remove reloads as much as possible.

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
  (let [pf (io/file p)]
    (assert (.exists pf) (print-str p "not found"))
    (.addURL cl (.toURL pf))))

(defn jar-files [path]
  (-> (JarFile. (str path))
      (.entries)
      (enumeration-seq)
      (->> (map (fn [e]
                  (.getName e))))))

(defn jar? [path]
  (re-find #".jar$" path))

(defprotocol ClassLoaderStrategy
  (with-classloader [this args f]
    "Given a classpath, calls f, a fn of one arg, the classloader. Args is
     a map that must contain `:classloader`, and any protocol
     implementation specific keys "))

(defn slow-naive
  "Use a new classloader for every compile. Works. Slow."
  []
  (reify ClassLoaderStrategy
    (with-classloader [_this {:keys [classpath]} f]
      (f (new-classloader- classpath)))))

(defn dirty-fast
  "Use a single classloader for all compiles, and always use. Works, only in single-threaded mode"
  []
  (let [dirty-classloader (new-classloader- [])]
    (reify ClassLoaderStrategy
      (with-classloader[this {:keys [classpath]} f]
        (doseq [p classpath]
          (add-url dirty-classloader p))
        (f dirty-classloader)))))

(defn clear-dead-refs [cache]
  (swap! cache (fn [cache]
                 (->> cache
                      (filter (fn [[k v]]
                                (.get v)))
                      (into {})))))

(defn parse-GAV-1
  "Given the path to a path, attempt to parse out maven Group Artifact Version coordinates, returns nil if it doesn't appear to be in a maven dir"
  [p]
  (let [[match group artifact version] (re-find #"repository/(.+)/([^\/]+)/([^\/]+)/\2-\3.jar" p)]
    (when match
      [(str group "/" artifact) version])))

(defn compatible-inputs?
  "Given two maps of jar to digest, return true if they are compatible (all keys in common have the same digest)"
  [im1 im2]
  {:pre [(map? im1)
         (map? im2)
         (every? (fn [[k v]]
                     (and (string? k) (string? v))) im1)
         (every? (fn [[k v]]
                   (and (string? k) (string? v))) im2)]}
  (let [common-keys (set/intersection (set (keys im1)) (set (keys im2)))]
    (= (select-keys im1 common-keys)
       (select-keys im2 common-keys))))

(s/def ::input-map (s/map-of string? string?))

(defn caching-clean-digest-thread-local
  "Take a classloader from the cache, if the maven GAV coordinates are
  not incompatible. Reuse the classloader, iff the namespaces compiled
  do not contain protocols, because those will cause CLJ-1544 errors
  if reused. Fastest working implementation."
  []
  (let [cache (ThreadLocal.)]
    (reify ClassLoaderStrategy
      (with-classloader [this {:keys [classpath aot-nses input-map]} f]
        (let [{cl-cache :classloader
               input-cache :input-map} (.get cache)
              cp-desired (set classpath)
              _ (when-not (s/valid? ::input-map input-map)
                  (s/explain ::input-map input-map))
              _ (assert (s/valid? ::input-map input-map))
              _ (assert (seq input-map))
              input-jars (->> input-map
                              (filter (fn [[path digest]]
                                        (jar? path)))
                              (into {}))
              cp-dirs (set (remove jar? classpath))

              cacheable-classloader (if (and cl-cache
                                             (compatible-inputs? input-jars input-cache)
                                             (every? (fn [ns]
                                                       (let [loaded? (util/shim-eval cl-cache (str
                                                                                               `(do
                                                                                                  (require 'rules-clojure.compile)
                                                                                                  (rules-clojure.compile/loaded? (symbol ~ns)))))]
                                                         (not loaded?))) aot-nses))
                                      (let [cp-new (set/difference (set (keys input-jars)) (set (keys input-cache)))]
                                        (doseq [p cp-new]
                                          (add-url cl-cache p))
                                        (do
                                          ;; (println "cache hit")
                                          cl-cache))
                                      (do
                                        ;; (println "cache miss")
                                        (assert (seq input-jars))
                                        (new-classloader- (keys input-jars))))
              classloader (new-classloader- cp-dirs cacheable-classloader)]
          (let [ret (f classloader)
                script (str `(do
                               (require 'rules-clojure.compile)
                               (some (fn [n#]
                                       (or (rules-clojure.compile/contains-protocols? (symbol n#))
                                           )) [~@aot-nses])))
                contains-protocols? (util/shim-eval classloader script)]
            (if-not contains-protocols?
              (.set cache {:input-map input-jars
                           :classloader cacheable-classloader})
              (.set cache nil))
            ret))))))
