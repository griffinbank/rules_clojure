(ns rules-clojure.util
  (:require [clojure.spec.alpha :as s])
  (:import java.net.URLClassLoader
           java.security.MessageDigest))

(set! *warn-on-reflection* true)

(defn validate! [spec val]
  (let [explain (s/explain-data spec val)]
    (if explain
      (throw (ex-info "validation failed:" explain))
      val)))

(defn print-err [& args]
  (binding [*out* *err*]
    (let [str (apply print-str args)]
      (locking true
        (println str)))))

(defmacro with-context-classloader [cl & body]
  `(let [old-cl# (.getContextClassLoader (Thread/currentThread))]
     (try
       (.setContextClassLoader (Thread/currentThread) ~cl)
       ~@body
       (finally
         (.setContextClassLoader (Thread/currentThread) old-cl#)))))

(defn shim-init [^ClassLoader cl]
  (with-context-classloader cl
    (let [rt (.loadClass cl "clojure.lang.RT")
         init-m (.getDeclaredMethod rt "init" (into-array Class []))]
     (.invoke init-m rt (into-array Object [])))))

(defn shim-var [^ClassLoader cl ns name]
  (with-context-classloader cl
    (let [Clj (.loadClass cl "clojure.java.api.Clojure")
          m (.getDeclaredMethod Clj "var" (into-array Class [Object Object]))]
      (.invoke m Clj (into-array Object [ns name])))))

(defn shim-invoke
  [^ClassLoader cl ns name & args]
  (with-context-classloader cl
    (let [v (shim-var cl ns name)
          ifn (.loadClass cl "clojure.lang.IFn")
          m (.getDeclaredMethod ifn "invoke" (into-array Class (take (count args) (repeat Object))))]
      (assert m)
      (.invoke m v (into-array Object args)))))

(defn shim-deref [^ClassLoader cl ns name]
  (with-context-classloader cl
    (let [v-class (.loadClass cl "clojure.lang.Var")
          m (.getDeclaredMethod v-class "deref" (into-array Class []))
          v (shim-var cl ns name)]
      (.invoke m v (into-array Object [])))))

(defn shim-require [cl ns]
  (shim-invoke cl "clojure.core" "require" ns))

(defn shim-eval [^ClassLoader cl s]
  (with-context-classloader cl
    (let [script (try
                   (shim-invoke cl "clojure.core" "read-string" (str s))
                   (catch Exception e
                     (throw (ex-info "while reading" {:script s} e))))]
      (try
        (shim-invoke cl "clojure.core" "eval" script)
        (catch Exception e
          (throw (ex-info "while evaling" {:script s} e)))))))

(defn invoker-1
  "given a classloader and a function name, return a function that
invokes f in the classloader, efficiently"
  [^ClassLoader classloader ns name]
  (shim-eval classloader (str `(require (symbol ~ns))))
  (let [loaded-var (shim-var classloader ns name)
        ifn (.loadClass classloader "clojure.lang.IFn")
        invoke-method (.getDeclaredMethod ifn "invoke" (into-array Class [Object]))]
    (assert invoke-method)
    (fn [arg]
      (.invoke invoke-method loaded-var (into-array Object [arg])))))

(defn bind-compiler-loader [^ClassLoader cl]
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
            (mapcat (fn [x] (when (instance? java.net.URLClassLoader x) (seq (.getURLs ^URLClassLoader x))))
                    (take-while identity (iterate (fn [^ClassLoader cl]
                                                    (.getParent cl)) (clojure.lang.RT/baseLoader)))))
           (system-classpath))
       (map str)))

(defn shasum [^bytes bs]
  {:pre [(seq bs)]}
  (let [digest (MessageDigest/getInstance "SHA-1")
        hexer (java.util.HexFormat/of)]
    (-> bs
        (#(.digest digest %))
        (#(.formatHex hexer %)))))
