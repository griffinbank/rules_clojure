(ns rules-clojure.jar
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [rules-clojure.fs :as fs]
            [rules-clojure.util :as util])
  (:import [java.io BufferedOutputStream FileOutputStream]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute FileTime]
           [java.time LocalDateTime ZoneId]
           [java.util.jar JarEntry JarFile JarOutputStream Manifest]))

(def manifest
  (let [m (Manifest.)]
    (doto (.getMainAttributes m)
      (.putValue "Manifest-Version" "1.0"))
    m))

(def default-file-modified-time-millis
  ;; this timestamp should always be newer than 'now', in case we
  ;; include .clj from 3rd party jars, which have real last modified
  ;; times.
  (-> (LocalDateTime/of 2038 1 1 0 0 0)
      (.atZone (ZoneId/of "UTC"))
      (.toInstant)
      (.toEpochMilli)))

(def default-class-file-modified-time-millis
  (+ default-file-modified-time-millis 2000))

(defn put-next-entry! [^JarOutputStream target ^String name]
  ;; We want reproducible builds and so must use fixed file modification times.
  ;; When both the .class and .clj are present, Clojure loads the one with the
  ;; newer file modification time, so always give class files newer modification times.
  ;;
  ;; Follows the same convention as bazel
  ;; https://github.com/bazelbuild/bazel/blob/d1fdc5303fd3cc22c5091aa4ce7df02eef09d922/src/java_tools/buildjar/java/com/google/devtools/build/buildjar/jarhelper/JarHelper.java#L114-L129
  (let [last-modified-time (if (str/ends-with? name ".class")
                             default-class-file-modified-time-millis
                             default-file-modified-time-millis)]
    (.putNextEntry target
                   (doto (JarEntry. name)
                     (.setLastModifiedTime (FileTime/fromMillis last-modified-time))))))

;; directory, root where all src and resources will be found
(s/def ::src-dir fs/path?)

;; path to the file, relative to workspace root. Path inside the jar will be relative-to src-dir
(s/def ::resource ::fs/path)

(s/def ::resources (s/coll-of ::resource))

;; seq of namespaces to AOT
(s/def ::aot-nses (s/coll-of symbol?))

;; path to `set!` *compile-path* to
(s/def ::classes-dir fs/path?)

(s/def ::output-jar fs/path?)

;; Doesn't take `::srcs`, assumes they are already on the classpath
(s/def ::compile (s/keys :req-un [::aot-nses ::classes-dir ::output-jar] :opt-un [::resources ::src-dir]))

(defn compile! [cl {:keys [aot-nses classes-dir] :as args}]
  (util/shim-eval cl `(require 'rules-clojure.compile))
  (let [aot-arr (into-array String (map str aot-nses))]
    (try
      (util/shim-invoke cl "rules-clojure.compile" "compile!" classes-dir aot-arr *out*)
      (catch Throwable t
        (util/print-err "jar/compile!:" args t)))))

(defn create-jar [{:keys [src-dir classes-dir output-jar resources aot-nses] :as args}]
  (s/assert ::compile args)
  (let [temp (Files/createTempFile (fs/dirname output-jar) (fs/filename output-jar) "jar" (into-array FileAttribute []))
        aot-files (->> classes-dir fs/ls-r (filter (fn [p] (-> p fs/path->file .isFile))) sort)
        resources (->> resources (map (fn [r] (fs/->path src-dir r))) (map (fn [p] (fs/path-relative-to src-dir p))) sort)]

    (when (and (seq aot-nses) (not (seq aot-files)))
      (assert false (print-str "create-jar" output-jar "aot-nses" aot-nses "but no aot output files:" classes-dir)))

    (with-open [jar-os (-> temp fs/path->file FileOutputStream. BufferedOutputStream. JarOutputStream.)]
      (put-next-entry! jar-os JarFile/MANIFEST_NAME)
      (.write ^Manifest manifest jar-os)
      (.closeEntry jar-os)
      (doseq [r resources
              :let [^Path full-path (fs/->path src-dir r)
                    file (.toFile full-path)
                    name (str (fs/path-relative-to src-dir full-path))]]
        (assert (fs/exists? full-path) (str full-path))
        (assert (.isFile file))
        (put-next-entry! jar-os name)
        (io/copy file jar-os)
        (.closeEntry jar-os))
      (doseq [^Path path aot-files
              :let [file (.toFile path)
                    name (str (fs/path-relative-to classes-dir path))]]
        (put-next-entry! jar-os name)
        (io/copy file jar-os)
        (.closeEntry jar-os)))
    (fs/mv temp output-jar)
    (assert (fs/exists? output-jar) (print-str "jar not found:" output-jar))))

(defn create-jar-json [json]
  (let [{:keys [src-dir resources aot-nses classes-dir output-jar classpath] :as args} json
        _ (when (seq resources) (assert src-dir))
        aot-nses (map symbol aot-nses)
        classes-dir (fs/->path classes-dir)
        resources (map fs/->path resources)
        output-jar (fs/->path output-jar)]
    (str
     (create-jar (merge
                  {:aot-nses aot-nses
                   :classes-dir classes-dir
                   :classpath classpath
                   :resources resources
                   :output-jar output-jar}
                  (when src-dir
                    {:src-dir (fs/->path src-dir)}))))))
