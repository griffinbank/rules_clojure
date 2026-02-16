(ns rules-clojure.persistentClassLoader
  (:require [rules-clojure.util :as util])
  (:import [java.lang.reflect Method]
           [clojure.lang DynamicClassLoader])
  (:gen-class
   :state state
   :constructors {["[Ljava.net.URL;" "java.lang.ClassLoader"] ["[Ljava.net.URL;" "java.lang.ClassLoader"]
                  ["[Ljava.net.URL;"] ["[Ljava.net.URL;"]}
   :extends java.net.URLClassLoader
   :exposes-methods {findClass parentFindClass
                     findLoadedClass parentFindLoadedClass
                     getResource parentGetResource}))


;; Context

;; We want fast, correct, isolated builds

;; we want a jar per namespace, so builds are incremental

;; in a single run of the rules clojure worker, we could be asked to build jars that have incompatible library versions

;; we are sometimes asked to AOT a namespace after the namespace has been loaded in-memory

;; on the JVM classes are equal if their names are equal AND their
;; classloaders are the same. The same named class in two classloaders
;; are not=. This is a problem with e.g. defprotocol, because if we do `(defprotocol Foo)`
;; there might be two instances of it: one created by the AOT process,
;; and one from loading the AOT'd jar.

;;; in the JVM, classloaders form a tree. there are several system
;;; classloaders, and then the Application classloader, which is
;;; usually a URLClassloader. If you `java -cp A.jar:B.jar`, you end
;;; up with a URLClassloader with two jars in it.

;; when loading classes, classloaders always ask their parent to load
;; the class first, then if the parent doesn't have it, the child will
;; attempt to load. Once a class is loaded, it cannot be unloaded
;; until its owning classloader is GC'd

;; The naive solution, creating a separate classloader for each build
;; request, is hopelessly slow. Each time we load the same class from
;; a new classloader, we incur CPU time and memory usage. We really want
;; to minimize the number of classloaders active at any one time

;; When compiling, Clojure creates a new instance of
;; clojure.lang.DynamicClassloader. This classloader is a child of the
;; class that holds clojure.lang.RT


;; The Solution

;; when we are asked to load a class, special case the clojure.jar,
;; because it might contain AOT'd classes. If we are asked to
;; findClass("Foo"), determine if there is a clojure.jar on the
;; classpath. If there is, ask it to participate in findClass

(set! *warn-on-reflection* true)

(defn clojure-find-class [this name]
  (when-let [^Class rt-class (.parentFindClass this "clojure.lang.RT")]
    (let [^Method baseloader-method (.getDeclaredMethod rt-class "baseLoader" (into-array Class []))
          ^DynamicClassLoader loader (.invoke baseloader-method rt-class (into-array Object []))]

      (.findInMemoryClass loader name))))

(defn -findClass
  [this name]
  (locking this
    (or
     (.parentFindClass this name)
     (clojure-find-class this name))))

(defn -findLoadedClass [this name]
  (.parentFindLoadedClass this name))
