(ns rules-clojure.persistent-classloader
  (:require [clojure.java.io :as io]
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

(defn cache-classloader [cache key cl]
  {:pre [(map? @cache)]
   :post [(map? @cache)]}
  (swap! cache assoc key (SoftReference. cl)))

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

(defn new-classloader-cache [cache classpath]
  {:pre [(every? string? classpath)]}
  (let [cp-desired (set classpath)
        ;; dirs will never be cached, so exclude
        cp-jars (set (filter jar? cp-desired))
        cp-dirs (set (remove jar? cp-desired))
        caches (->> @cache
                    (filter (fn [[k _ref]]
                              (= #{} (set/difference k cp-jars)))))
        cp-parent (when (seq caches)
                    (let [[cp-parent parent-ref] (apply max-key (fn [[cp-cache cl-ref]]
                                                                  (count cp-cache)) caches)]
                      cp-parent))
        new-cache-cl (if-let [cl (and cp-parent (some-> (claim-classloader cache cp-parent) (.get)))]
                       (do
                         (doseq [p cp-jars]
                           (add-url cl p))
                         cl)
                       (new-classloader- cp-jars))
        cl-final (new-classloader- cp-dirs new-cache-cl)]
    (util/shim-deref cl-final "clojure.core" "*clojure-version*")
    (util/bind-compiler-loader cl-final)
    cl-final))

(defn new-classloader-cache-2 [cache classpath]
  {:pre [(every? string? classpath)]}
  (let [cp-desired (set classpath)
        caches (->> @cache
                    (filter (fn [[k _ref]]
                              (= #{} (set/difference k cp-desired)))))
        cp-parent (when (seq caches)
                    (let [[cp-parent parent-ref] (apply max-key (fn [[cp-cache cl-ref]]
                                                                  (count cp-cache)) caches)]
                      cp-parent))
        cl (if-let [cl (and cp-parent (some-> (claim-classloader cache cp-parent) (.get)))]
                       (do
                         (doseq [p cp-desired]
                           (add-url cl p))
                         cl)
                       (new-classloader- cp-desired))]
    (util/shim-deref cl "clojure.core" "*clojure-version*")
    (util/bind-compiler-loader cl)
    cl))

(defn caching-naive []
  ;; set of cp jars to a SoftReference to classloader
  (let [cache (atom {})]
    (reify ClassLoaderStrategy
      (get-classloader[this classpath]
        (new-classloader-cache cache classpath))
      (return-classloader [this aot-nses classpath classloader]
        (cache-classloader cache (set (filter jar? classpath)) (.getParent classloader))))))

(defn caching-clean []
  (let [cache (atom {})]
    (reify ClassLoaderStrategy
      (get-classloader [this classpath]
        (let [classloader (new-classloader-cache-2 cache classpath)]
          classloader))
      (return-classloader [this aot-nses classpath classloader]
        (let [script (str `(do
                             (require 'rules-clojure.compile)
                             (some (fn [n#]
                                     (rules-clojure.compile/contains-protocols? (symbol n#))) [~@aot-nses])))
              ret (util/shim-eval classloader script)]
          (when-not ret
            (cache-classloader cache (set (filter jar? classpath)) classloader)))))))

(defn new-classloader [classpath]
  (get-classloader (slow-naive) classpath))
