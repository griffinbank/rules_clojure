(ns rules-clojure.persistent-classloader
  (:require [clojure.data]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.tools.namespace.dependency :as dep]
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
  (.addURL cl (-> p io/file .toURL)))

(defn jar-files [path]
  (-> (JarFile. (str path))
      (.entries)
      (enumeration-seq)
      (->> (map (fn [e]
                  (.getName e))))))

(defn jar? [path]
  (re-find #".jar$" path))

(defn clojure? [path]
  (or (re-find #"org/clojure/clojure/.*.jar$" path)
      (re-find #"org/clojure/spec.alpha/.*.jar$" path)
      (re-find #"org/clojure/core.specs.alpha/.*.jar$" path)))

(defprotocol ClassLoaderStrategy
  (get-classloader[this classpath])
  (return-classloader [this aot-nses classpath classloader]))

(defn slow-naive []
  (reify ClassLoaderStrategy
    (get-classloader[_this classpath]
      (new-classloader- classpath))
    (return-classloader [_this aot-nses classpath classloader]
      nil)))

(defn dirty-fast []
  (let [dirty-classloader (new-classloader- [])]
    (reify ClassLoaderStrategy
      (get-classloader[this classpath]
        (doseq [p classpath]
          (add-url dirty-classloader p))
        dirty-classloader)
      (return-classloader [this aot-nses classpath classloader]
        nil))))

(defn clear-dead-refs [cache]
  (swap! cache (fn [cache]
                 (->> cache
                      (filter (fn [[k v]]
                                (.get v)))
                      (into {})))))

(defn cache-classloader [cache key cl]
  {:pre [(map? @cache)]
   :post [(map? @cache)]}
  (swap! cache assoc key (SoftReference. cl))
  (clear-dead-refs cache))

(defn claim-classloader
  "Attempt to claim a cached classloader with key. Returns the classloader if successful, else nil"
  [cache key]
  (let [claimed (atom nil)]
    (swap! cache (fn [cache]
                   (if-let [cl (get cache key)]
                     (do
                       (reset! claimed cl)
                       (dissoc cache key))
                     (do
                       (reset! claimed nil)
                       cache))))
    @claimed))

(defn parse-GAV-1
  "Given the path to a path, attempt to parse out maven Group Artifact Version coordinates, returns nil if it doesn't appear to be in a maven dir"
  [p]
  (let [[_match group artifact version] (re-find #"repository/(.+)/([^\/]+)/([^\/]+)/\2-\3.jar" p)]
    (when _match
      [(str group "/" artifact) version])))

(defn GAV-map
  "given a classpath, return a map of all parsed GAV coordinates"
  [classpath]
  (->> classpath
       (map parse-GAV-1)
       (filter identity)
       (into {})))

(defn compatible-classpaths? [c1 c2]
  (let [gav-1 (GAV-map c1)
        gav-2 (GAV-map c2)
        common-keys (set/intersection (set (keys gav-1)) (set (keys gav-2)))]
    (= (select-keys gav-1 common-keys)
       (select-keys gav-2 common-keys))))

(defn new-classloader-cache [cache classpath]
  {:pre [(every? string? classpath)]}
  (let [cp-desired (set classpath)
        cache-deref @cache
        caches (->> cache-deref
                    (filter (fn [[cp-cache _cl-ref]]
                              (compatible-classpaths? cp-cache cp-desired))))
        cp-parent (when (seq caches)
                    (let [[cp-parent parent-ref] (apply max-key (fn [[cp-cache cl-ref]]
                                                                  (count (set/intersection cp-desired cp-cache))) caches)]
                      cp-parent))
        cl (if (seq cache-deref)
             (if cp-parent
               (if-let [cl-ref (claim-classloader cache cp-parent)]
                 (if-let [cl (.get cl-ref)]
                   (let [cp-new (set/difference cp-desired cp-parent)]
                     (doseq [p cp-new]
                       (add-url cl p))
                     (do
                       ;; (println "hit")
                       cl))
                   (do
                     ;; (println "cache-miss GC")
                     (new-classloader- cp-desired)))
                 (do
                   ;; (println "cache failed claim. size:" (count cache-deref) )
                   (new-classloader- cp-desired)))
               (do
                 ;; (println "cache miss incompatible:" (map (fn [[cp-cache _cl-ref]] (compatible-classpaths? cp-desired cp-cache)) cache-deref))
                 (new-classloader- cp-desired)))
             (do
               ;; (println "cache-miss empty")
               (new-classloader- cp-desired)))]
    (util/shim-deref cl "clojure.core" "*clojure-version*")
    (util/bind-compiler-loader cl)
    cl))

(defn caching-clean []
  (let [cache (atom {})]
    (reify ClassLoaderStrategy
      (get-classloader [this classpath]
        (let [classloader (new-classloader-cache cache classpath)]
          classloader))
      (return-classloader [this aot-nses classpath classloader]
        (let [script (str `(do
                             (require 'rules-clojure.compile)
                             (some (fn [n#]
                                     (rules-clojure.compile/contains-protocols? (symbol n#))) [~@aot-nses])))
              ret (util/shim-eval classloader script)]
          (if-not ret
            (cache-classloader cache (set (filter jar? classpath)) classloader)
            (do
              ;; (println "AOT" aot-nses "discarding")
              )))))))

(defn new-classloader [classpath]
  (get-classloader (slow-naive) classpath))
