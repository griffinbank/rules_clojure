(ns rules-clojure.persistent-classloader
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.tools.namespace.dependency :as dep])
  (:import [java.net URL URLClassLoader]
           java.lang.ref.SoftReference))

;; We want a clean, deterministic build. The naive way to do that is
;; to construct a new URLClassloader containing exactly what the user
;; requested for each build. Unfortunately, that's too slow, because
;; every time we construct a new classloader, the code has to be
;; reloaded. Using caching to remove reloads as much as possible.

(defn new-url-classloader-
  ([cp]
   (new-url-classloader- cp (.getParent (ClassLoader/getSystemClassLoader))))
  ([cp parent]
   {:pre [(every? string? cp)
          (do (when-not (instance? ClassLoader parent)
                (println "parent:" parent)) true)
          (instance? ClassLoader parent)]}
   (URLClassLoader.
    (into-array URL (map #(.toURL (io/file %)) cp))
    parent)))

(defn find-clojure
  "Given a set of jars, return the clojure.jar"
  [classpath]
  (->> classpath
       (filter (fn [p]
                 (re-find #"org/clojure/clojure/.*.jar" p)))))

(defn new-dcl- [parent]
  (let [dcl-class (.loadClass parent "clojure.lang.DynamicClassLoader")
        constructor (.getConstructor dcl-class (into-array Class [ClassLoader]))]
    (.newInstance constructor (into-array Object [parent]))))

(defn add-to-dcl [cl paths]
  (let [dcl (.loadClass cl "clojure.lang.DynamicClassLoader")
        add-url (.getDeclaredMethod dcl "addURL" (into-array Class [URL]))]
    (doseq [p paths]
      (.invoke add-url cl (into-array URL [(-> p io/file .toURL)])))
    cl))

(defn new-dcl [classpath]
  (let [cp-clojure (set (find-clojure classpath))
        cp-rest (set/difference classpath cp-clojure)
        url-cl (new-url-classloader- cp-clojure)
        dcl (new-dcl- url-cl)]

    (when (> (count cp-clojure) 1)
      (println "WARNING more than one clojure on classpath:" cp-clojure))
    (when (zero? (count cp-clojure))
      (println "no clojure found on classpath:" classpath))

    (add-to-dcl dcl cp-rest)))

;; set of cp jars to a SoftReference to classloader
(def classloader-cache (atom {}))

(defn claim-cached-cl [cp-jars]
  (let [claimed-cache (atom nil)]
    (swap! classloader-cache (fn [cl-cache]
                               (let [entries (->> cl-cache
                                                 (filter (fn [[cp-cached _classloaders]]
                                                           (= #{} (set/difference cp-cached cp-jars)))))
                                     [cp-set ref] (when (seq entries)
                                                             (apply max-key (fn [[k _ref]]
                                                                              (count k)) entries))]
                                 (if cp-set
                                   (do
                                     (reset! claimed-cache [cp-set ref])
                                     (dissoc cl-cache cp-set))
                                   cl-cache))))
    @claimed-cache))

(defn cache-classloader [key cl]
  {:pre [(map? @classloader-cache)]
   :post [(map? @classloader-cache)]}
  (swap! classloader-cache assoc key (SoftReference. cl)))

(defn new-classloader [classpath]
  {:pre [(every? string? classpath)]}
  ;; CLJ-1544 happens when a protocol definition and call site are in
  ;; two different classloaders. To avoid, stick everything in the
  ;; clojure.lang.DynamicClassLoader

  (let [cp-desired (set classpath)
        cp-jars (set (filter (fn [path] (and (re-find #".jar$" path) (-> path io/file .isFile))) cp-desired))
        cp-dirs (set (filter (fn [path] (-> path io/file .isDirectory)) cp-desired))
        _ (when-not (= (count cp-desired) (+ (count cp-jars) (count cp-dirs)))
            (println (count cp-desired) "vs." (+ (count cp-jars) (count cp-dirs)) (set/difference cp-desired cp-jars cp-dirs)))
        _ (assert (= (count cp-desired) (+ (count cp-jars) (count cp-dirs))))
        [cp-cached cache-ref] (claim-cached-cl cp-jars)
        cl-cached (when cache-ref
                    (.get cache-ref))
        cl-cached-updated (if cl-cached
                            (add-to-dcl cl-cached (set/difference cp-desired cp-cached cp-dirs))
                            (new-dcl cp-jars))
        _ (assert (instance? ClassLoader cl-cached-updated))
        final-cl (new-url-classloader- cp-dirs cl-cached-updated)]
    (cache-classloader cp-jars cl-cached-updated)
    final-cl))
