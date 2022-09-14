(ns rules-clojure.util
  (:require [clojure.spec.alpha :as s]))

(defn validate! [spec val]
  (let [explain (s/explain-data spec val)]
    (if explain
      (throw (ex-info "validation failed:" explain))
      val)))

(defn print-err [& args]
  (binding [*out* *err*]
    (apply println args)))

(defmacro with-context-classloader [cl & body]
  `(let [old-cl# (.getContextClassLoader (Thread/currentThread))]
     (try
       (.setContextClassLoader (Thread/currentThread) ~cl)
       ~@body
       (finally
         (.setContextClassLoader (Thread/currentThread) old-cl#)))))

(defn shim-init [cl]
  (with-context-classloader cl
    (let [rt (.loadClass cl "clojure.lang.RT")
         init-m (.getDeclaredMethod rt "init" (into-array Class []))]
     (.invoke init-m rt (into-array Object [])))))

(defn shim-var [cl ns name]
  (with-context-classloader cl
    (let [Clj (.loadClass cl "clojure.java.api.Clojure")
          m (.getDeclaredMethod Clj "var" (into-array Class [Object Object]))]
      (.invoke m Clj (into-array Object [ns name])))))

(defn shim-invoke
  [cl ns name & args]
  (with-context-classloader cl
    (let [v (shim-var cl ns name)
          ifn (.loadClass cl "clojure.lang.IFn")
          m (.getDeclaredMethod ifn "invoke" (into-array Class (take (count args) (repeat Object))))]
      (.invoke m v (into-array Object args)))))

(defn shim-deref [cl ns name]
  (with-context-classloader cl
    (let [v-class (.loadClass cl "clojure.lang.Var")
          m (.getDeclaredMethod v-class "deref" (into-array Class []))
          v (shim-var cl ns name)]
      (.invoke m v (into-array Object [])))))

(defn shim-require [cl ns]
  (shim-invoke cl "clojure.core" "require" ns))

(defn shim-eval [cl s]
  (with-context-classloader cl
    (let [script (shim-invoke cl "clojure.core" "read-string" s)]
      (shim-invoke cl "clojure.core" "eval" script))))

(defn bind-compiler-loader [cl]
  (with-context-classloader cl
    (let [compiler (.loadClass cl "clojure.lang.Compiler")
          var (.loadClass cl "clojure.lang.Var")
          loader-f (.getDeclaredField compiler "LOADER")
          loader (.get loader-f compiler)
          bind-root-m (.getDeclaredMethod var "bindRoot" (into-array Class [Object]))]
      (.invoke bind-root-m loader (into-array Object [cl])))))

(defn system-classpath
  "Returns a sequence of File paths from the 'java.class.path' system
  property."
  []
  (map #(java.io.File. ^String %)
       (.split (System/getProperty "java.class.path")
               (System/getProperty "path.separator"))))

(defn classpath []
  (->> (or (seq
            (mapcat (fn [x] (when (instance? java.net.URLClassLoader x) (seq (.getURLs x))))
                    (take-while identity (iterate #(.getParent %) (clojure.lang.RT/baseLoader)))))
           (system-classpath))
       (map str)))
