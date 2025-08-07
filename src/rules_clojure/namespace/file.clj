;; Forked from tools.namespace.file, to reduce dependencies and avoid conflicts with user code being compiled

;; Copyright (c) Stuart Sierra, 2012. All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution. By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license. You must not
;; remove this notice, or any other, from this software.

(ns ^{:author "Stuart Sierra"
      :doc "Read and track namespace information from files"}
  rules-clojure.namespace.file
  (:require [clojure.java.io :as io]
            [rules-clojure.namespace.parse :as parse])
  (:import (java.io PushbackReader)))

(defn read-file-ns-decl
  "Attempts to read a (ns ...) declaration from file, and returns the
  unevaluated form. Returns nil if ns declaration cannot be found.
  read-opts is passed through to tools.reader/read."
  ([file]
   (read-file-ns-decl file nil))
  ([file read-opts]
   {:pre [(instance? java.io.File file)]}
   (with-open [rdr (PushbackReader. (io/reader file))]
     (parse/read-ns-decl rdr read-opts))))

(defn file-with-extension?
  "Returns true if the java.io.File represents a file whose name ends
  with one of the Strings in extensions."
  {:added "0.3.0"}
  [^java.io.File file extensions]
  (and (.isFile file)
       (let [name (.getName file)]
         (some #(.endsWith name %) extensions))))

(def ^{:added "0.3.0"}
  clojure-extensions
  "File extensions for Clojure (JVM) files."
  (list ".clj" ".cljc"))

(def ^{:added "0.3.0"}
  clojurescript-extensions
  "File extensions for ClojureScript files."
  (list ".cljs" ".cljc"))

(defn clojure-file?
  "Returns true if the java.io.File represents a file which will be
  read by the Clojure (JVM) compiler."
  [^java.io.File file]
  (file-with-extension? file clojure-extensions))

(defn clojurescript-file?
  "Returns true if the java.io.File represents a file which will be
  read by the ClojureScript compiler."
  {:added "0.3.0"}
  [^java.io.File file]
  (file-with-extension? file clojurescript-extensions))
