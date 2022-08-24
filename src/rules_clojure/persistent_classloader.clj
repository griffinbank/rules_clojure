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

(defn new-classloader-slow-naive [classpath]
  (new-classloader- classpath))

(defn add-url [cl p]
  (.addURL cl (-> p io/file .toURL)))

(def dirty-classloader (new-classloader- []))

(defn new-classloader-dirty-fast [classpath]
  (doseq [p classpath]
    (add-url dirty-classloader p))
  dirty-classloader)

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
                                   (dissoc cache key))
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

(defn clojure? [path]
  (or (re-find #"org/clojure/clojure/.*.jar$" path)
      (re-find #"org/clojure/spec.alpha/.*.jar$" path)
      (re-find #"org/clojure/core.specs.alpha/.*.jar$" path)))

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

;; (defn compiler-loader-bound? [cl]
;;   (let [compiler (.loadClass cl "clojure.lang.Compiler")
;;         var-class (.loadClass cl "clojure.lang.Var")
;;         loader-field (.getField compiler "LOADER")
;;         loader-var (.get loader-field compiler)
;;         bound-m (.getDeclaredMethod var-class "isBound" (into-array Class []))]
;;     (.invoke bound-m loader-var (into-array Object []))))

;; (defn force-rt-load
;;   "make sure clojure.lang.RT is loaded"
;;   [cl]
;;   (let [compiler (.loadClass cl "clojure.lang.RT")
;;         bool-f (.getField compiler "T")]
;;     (.get bool-f compiler)))

;; (defn compiler-bind-loader [cl]
;;   (let [dcl-c (.loadClass cl "clojure.lang.RT")
;;         dcl-constructor (.getConstructor dcl-c (into-array Class []))
;;         dcl (.newInstance dcl-constructor (into-array Object []))
;;         compiler-c (.loadClass cl "clojure.lang.Compiler")
;;         var-c (.loadClass cl "clojure.lang.Var")
;;         loader-f (.getField compiler-c "LOADER")
;;         loader (.get compiler-c)
;;         bindroot-m (.getDeclaredMethod var-c "bindRoot" (into-array Class [Object]))]
;;     (.invoke bindroot-m loader (into-array Object [dcl]))))

;; (defn ensure-loader-bound
;;   "Ensure the clojure.lang.Compiler/LOADER is bound"
;;   [cl]
;;   (if (not (dcl-bound? )))
;;   )

;; (defn dcl-get-loader
;;   "Return the c.l.DCL instance attached to c.l.Compiler/LOADER"
;;   [cl]
;;   {:post [(do (println "get-loader:" %) true)(instance? ClassLoader %)]}
;;   (force-rt-load cl)
;;   (let [compiler (.loadClass cl "clojure.lang.Compiler")
;;         var-class (.loadClass cl "clojure.lang.Var")
;;         loader-field (.getField compiler "LOADER")
;;         loader-var (.get loader-field compiler)
;;         deref-m (.getDeclaredMethod var-class "deref" (into-array Class []))]
;;     (.invoke deref-m loader-var (into-array Object []))))

;; (defn dcl-add-url [cl p]
;;   (force-rt-load cl)
;;   ;; (ensure-loader-bound cl)

;;   (let [dcl-class (.loadClass cl "clojure.lang.DynamicClassLoader")

;;         dcl (dcl-get-loader cl)
;;         add-url-m (.getDeclaredMethod dcl-class "addURL" (into-array Class [URL]))]
;;     (println "dcl:" dcl)
;;     (assert dcl)

;;     (.invoke add-url-m dcl (into-array Object [p]))))

;; (defn gav-map
;;   "given a seq of classpath entries, attempt to parse out Maven Group Artifact Version coordinates. Returns a map of GA to V, for successful parses"
;;   [classpath]
;;   )

;; (defn new-classloader-dcl [classpath]
;;   (println "new-classloader-dcl:" classpath)
;;   (let [cp-desired (set classpath)
;;         ;; dirs will never be cached, so exclude
;;         cp-clojure (set (filter clojure? classpath))
;;         cp-jars (set (filter (fn [p] (and (jar? p) (not (clojure? p)))) cp-desired))
;;         cp-dirs (set (filter (fn [p] (not (jar? p))) cp-desired))
;;         caches (->> @classloader-cache
;;                     (filter (fn [[k _ref]]
;;                               (= #{} (set/difference k cp-clojure)))))
;;         cp-parent (when (seq caches)
;;                     (let [[cp-parent parent-ref] (apply max-key (fn [[cp-cache cl-ref]]
;;                                                                   (count cp-cache)) caches)]
;;                       cp-parent))
;;         new-cache-cl (if-let [pcl (and cp-parent (.get (claim-classloader cp-parent)))]
;;                        pcl
;;                        (do
;;                          (println "new classloader" cp-clojure)
;;                          (new-classloader- cp-clojure)))]
;;     (doseq [p cp-jars]
;;       (dcl-add-url new-cache-cl p))
;;     (cache-classloader cp-jars new-cache-cl)
;;     (new-classloader- cp-dirs new-cache-cl)))

(def new-classloader new-classloader-cache)
