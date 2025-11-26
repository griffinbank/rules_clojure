;; Forked from tools.namespace.file, to reduce dependencies and avoid conflicts with user code being compiled

;; Copyright (c) Stuart Sierra, 2012. All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution. By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license. You must not
;; remove this notice, or any other, from this software.

(ns
  ^{:author "Stuart Sierra",
     :doc "Search for namespace declarations in directories and JAR files."}
  rules-clojure.namespace.find
  (:require [clojure.java.io :as io]
            [rules-clojure.java.classpath :as classpath]
            [rules-clojure.namespace.file :as file]
            [rules-clojure.namespace.parse :as parse])
  (:import (java.io File PushbackReader)
           (java.util.jar JarFile JarEntry)))

(def ^{:added "0.3.0"}
  clj
  "Platform definition of file extensions and reader options for
  Clojure (.clj and .cljc) source files."
  {:read-opts parse/clj-read-opts
   :extensions file/clojure-extensions})

(def ^{:added "0.3.0"}
  cljs
  "Platform definition of file extensions and reader options for
  ClojureScript (.cljs and .cljc) source files."
  {:read-opts parse/cljs-read-opts
   :extensions file/clojurescript-extensions})

(defmacro ^:private ignore-reader-exception
  "If body throws an exception caused by a syntax error (from
  tools.reader), returns nil. Rethrows other exceptions."
  [& body]
  `(try ~@body
        (catch Exception e#
          (if (= :reader-exception (:type (ex-data e#)))
            nil
            (throw e#)))))

(def file? (partial instance? java.io.File))
(def jarfile? (partial instance? JarFile))

;;; Finding namespaces in a directory tree

(defn find-files-in-dir
  "Searches recursively under dir for source files. Returns a sequence
  of File objects

  second argument is either clj (default) or cljs, both
  defined in clojure.tools.namespace.find."
  {:added "0.3.0"}
  ([^File dir platform]
   {:pre [platform]}
   (let [{:keys [extensions]} platform]
     (->> (file-seq dir)
          (filter #(file/file-with-extension? % extensions))))))

(defn find-sources-in-dir
  "Searches recursively under dir for source files. Returns a sequence
  of File objects, in breadth-first sort order.

  Optional second argument is either clj (default) or cljs, both
  defined in clojure.tools.namespace.find."
  [^File dir platform]
  (->> (find-files-in-dir dir platform)
       (map slurp)))

(defn find-ns-decls-in-dir-
  "Searches dir recursively for (ns ...) declarations in Clojure
  source files; returns the unevaluated ns declarations."
  {:added "0.2.0"}
  ([dir platform]
   {:pre [(string? dir)]}
   (keep #(ignore-reader-exception
            (file/read-file-ns-decl % (:read-opts platform)))
         (find-files-in-dir (io/file dir) platform))))

(def find-ns-decls-in-dir (memoize (fn
                                     [dir platform]
                                     {:pre [(instance? java.io.File dir)]}
                                     (find-ns-decls-in-dir- (str dir) platform))))

;;; Finding namespaces in JAR files

(defn- ends-with-extension
  [^String filename extensions]
  (some #(.endsWith filename %) extensions))

(defn sources-in-jar
  "Returns a sequence of JarEntries

  Optional second argument platform is either clj (default) or cljs,
  both defined in clojure.tools.namespace.find."
  {:added "0.3.0"}
  [^JarFile jar-file platform]
  {:pre [(jarfile? jar-file) platform]}
  (let [{:keys [extensions]} platform]
    (->> jar-file
         (.entries)
         (enumeration-seq)
         (filter #(not (.isDirectory ^JarEntry %)))
         (filter #(ends-with-extension (.getRealName %) extensions)))))

(defn filenames-in-jar [jarfile platform]
  (->> (sources-in-jar jarfile platform)
       (map (fn [entry]
              (.getRealName entry)))))

(defn read-ns-decl-from-jarfile-entry
  "Attempts to read a (ns ...) declaration from the named entry in the
  JAR file, and returns the unevaluated form. Returns nil if read
  fails due to invalid syntax or if a ns declaration cannot be found.

  Optional third argument platform is either clj (default) or cljs,
  both defined in clojure.tools.namespace.find."
  [^JarFile jarfile ^JarEntry entry platform]
  (let [{:keys [read-opts]} (or platform clj)]
    (with-open [rdr (PushbackReader.
                     (io/reader
                      (.getInputStream jarfile entry)))]
      (ignore-reader-exception
       (parse/read-ns-decl rdr read-opts)))))

(defn slurp-from-jarfile-entry
  "Return the file contents as a string"
  [^JarFile jarfile ^String entry-name]
  (->> entry-name
       (.getEntry jarfile)
       (.getInputStream jarfile)
       (slurp)))

(defn find-ns-decls-in-jarfile
  "Searches the JAR file for source files containing (ns ...)
  declarations; returns the unevaluated ns declarations.

  Optional second argument platform is either clj (default) or cljs,
  both defined in clojure.tools.namespace.find."
  [^JarFile jarfile platform]
  {:pre [(jarfile? jarfile)]}
  (keep #(read-ns-decl-from-jarfile-entry jarfile % platform)
        (sources-in-jar jarfile platform)))

(defn find-sources-in-jarfile
  "Searches the JAR file for source files returns the file contents

  Optional second argument platform is either clj (default) or cljs,
  both defined in clojure.tools.namespace.find."
  [^File jar platform]
  {:pre [(file? jar)]}
  (let [jarfile (JarFile. jar)]
    (keep #(slurp-from-jarfile-entry jarfile %)
          (sources-in-jar jarfile platform))))

(defn find-ns-decls-in-jar-
  [^String jar-path platform]
  (find-ns-decls-in-jarfile (-> jar-path io/file JarFile.) platform))

(def find-ns-decls-in-jar (memoize find-ns-decls-in-jar-))

;;; Finding namespaces

(defn find-ns-decls
  "Searches a sequence of java.io.File objects (both directories and
  JAR files) for platform source files containing (ns...)
  declarations. Returns a sequence of the unevaluated ns declaration
  forms. Use with clojure.java.classpath to search Clojure's
  classpath.

  Optional second argument platform is either clj (default) or cljs,
  both defined in clojure.tools.namespace.find."
  [files platform]
  (concat
   (mapcat #(find-ns-decls-in-dir % platform)
           (filter #(.isDirectory ^File %) files))
   (mapcat #(find-ns-decls-in-jar % platform)
           (filter classpath/jar-file? files))))

(defn ->input-stream [x]
  (cond
    (instance? File x) (io/input-stream x)
    (instance? JarEntry x) (.getInputStream x)))

(defn find-files
  "Return a lazy seq of Files and JarEntries of source files on the classpath"
  [files platform]
  (concat
   (->> files
        (filter #(.isDirectory ^File %))
        (mapcat #(find-files-in-dir % platform)))
   (->> files
        (filter classpath/jar-file?)
        (map JarFile/new)
        (mapcat (fn [jar-file]
                  (sources-in-jar jar-file platform))))))

(defn find-sources
  "returns a lazy sequence of strings, each one being the contents of a platform source file on the classpath"
  [files platform]
  {:pre [(every? file? files)]}
  (->> (find-files files platform)
       (map ->input-stream)
       (map slurp)))

(defn find-namespaces
  "Searches a sequence of java.io.File objects (both directories and
  JAR files) for platform source files containing (ns...)
  declarations. Returns a sequence of the symbol names of the declared
  namespaces. Use with clojure.java.classpath to search Clojure's
  classpath.

  second argument platform is either find/clj or find/cljs"
  [files platform]
  (map parse/name-from-ns-decl (find-ns-decls files platform)))
