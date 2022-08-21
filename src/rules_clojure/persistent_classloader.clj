(ns rules-clojure.persistent-classloader
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.tools.namespace.dependency :as dep]
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

(defn print-err [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn add-url [cl p]
  (.addURL cl (-> p io/file .toURL)))

;; set of cp jars to a SoftReference to classloader
(def classloader-cache (atom {}))

(defn cache-classloader [key cl]
  {:pre [(map? @classloader-cache)]
   :post [(map? @classloader-cache)]}
  (swap! classloader-cache assoc key (SoftReference. cl)))

(defn claim-classloader
  "Attempt to claim a cached classloader with key. Returns the classloader if successful, else nil"
  [key]
  (let [claimed (atom nil)]
    (swap! classloader-cache (fn [cache]
                               (if-let [cl (get cache key)]
                                 (do
                                   (reset! claimed cl)
                                   (dissoc cache nil))
                                 (do
                                   (reset! claimed nil)
                                   cache))))
    @claimed))

(defn jar-files [path]
  (-> (JarFile. (str path))
      (.entries)
      (enumeration-seq)
      (->> (map (fn [e]
                  (.getName e))))))

(defn jar? [path]
  (re-find #".jar$" path))

(defn new-classloader-cache [classpath]
  {:pre [(every? string? classpath)]}
  (let [cp-desired (set classpath)
        ;; dirs will never be cached, so exclude
        cp-jars (set (filter jar? cp-desired))
        cp-dirs (set (remove jar? cp-desired))
        caches (->> @classloader-cache
                    (filter (fn [[k _ref]]
                              (= #{} (set/difference k cp-jars)))))
        cp-parent (when (seq caches)
                    (let [[cp-parent parent-ref] (apply max-key (fn [[cp-cache cl-ref]]
                                                                  (count cp-cache)) caches)]
                      cp-parent))
        new-cache-cl (if-let [cl (and cp-parent (.get (claim-classloader cp-parent)))]
                       (do
                         (doseq [p cp-jars]
                          (add-url cl p))
                         cl)
                       (new-classloader- cp-jars))]
    (cache-classloader cp-jars new-cache-cl)
    (new-classloader- cp-dirs new-cache-cl)))

(defn new-classloader-naive [classpath]
  (new-classloader- classpath))

(def new-classloader new-classloader-cache)
