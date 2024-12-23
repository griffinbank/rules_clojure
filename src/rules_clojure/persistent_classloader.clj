(ns rules-clojure.persistent-classloader
  (:require [clojure.data]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [rules-clojure.util :as util]
            [rules-clojure.persistentClassLoader])
  (:import java.util.jar.JarFile
           java.net.URL
           rules_clojure.persistentClassLoader
           [java.util.concurrent.locks Lock ReentrantLock]))

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

(s/def ::input-map (s/map-of string? string?))

(defn caching-threadsafe
  "Similar to dirty-fast-discard, but multi-threaded. Each thread uses an independent classloader"
  []
  (let [cache (ThreadLocal.)
        reload! (fn [new-classpath]
                  (let [classloader (new-classloader- new-classpath)]
                    (.set cache {:classloader classloader
                                 :loaded? (util/invoker-1 classloader "rules-clojure.compile" "loaded?")
                                 :contains-protocols? (util/invoker-1 classloader "rules-clojure.compile" "contains-protocols-or-deftypes?")})))
        init! (fn [classpath]
                (let [{:keys [classloader]} (.get cache)
                      _ (when-not classloader
                          (reload! classpath))
                      {:keys [classloader]} (.get cache)]
                  (doseq [p classpath]
                    (add-url classloader p))))]
    (reify ClassLoaderStrategy
      (with-classloader [this {:keys [classpath aot-nses]} f]
        (init! classpath)
        (let [{:keys [loaded?]} (.get cache)
              _ (when (some (fn [ns] (loaded? ns)) aot-nses)
                  (reload! classpath))
              {:keys [classloader contains-protocols?]} (.get cache)]
          (let [ret (f classloader)]
            (when (some (fn [ns] (contains-protocols? ns)) aot-nses)
              (reload! classpath))
            ret))))))
