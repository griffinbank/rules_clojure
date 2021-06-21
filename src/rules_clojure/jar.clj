(ns rules-clojure.jar
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.io BufferedOutputStream FileOutputStream]
           [java.util.jar Manifest JarEntry JarFile JarOutputStream]
           [java.nio.file Files Path Paths FileSystem FileSystems]
           java.nio.file.attribute.FileAttribute))

(def manifest
  (let [m (Manifest.)]
    (doto (.getMainAttributes m)
      (.putValue "Manifest-Version" "1.0"))
    m))

(defn put-next-entry! [target name]
  (.putNextEntry target (doto (JarEntry. name) (.setTime 0))))

(defn ->path [& dirs]
  (let [[d & dr] dirs
        d (if
            (string? d) (Paths/get d (into-array String []))
            d)]
    (reduce (fn [^Path p dir] (.resolve p dir)) d (rest dirs))))

(defn path->file [p]
  (.toFile p))

(defn absolute [path]
  (.toAbsolutePath path))

(defn exists? [path]
  (Files/exists path (into-array java.nio.file.LinkOption [])))

(defn path-relative-to
  [a b]
  (.relativize (absolute a) (absolute b)))

(defn mkdir [path]
  (Files/createDirectory path (into-array FileAttribute [])))

(defn compile! [json]
  (let [args (json/read-str json :key-fn keyword)
        {:keys [src_dir srcs aot classes_dir output_jar]} args
        classes-dir (->path classes_dir)
        src-dir (->path src_dir)
        output-jar (->path output_jar)]
    (when (seq aot)
      (binding [*compile-path* (str classes-dir "/")]
        (mkdir classes-dir)

        (doseq [ns aot]
          (compile (symbol ns))
          ;; (non-transitive-compile input-dir (symbol ns))
          )))

    (with-open [jar-os (-> output-jar .toFile FileOutputStream. BufferedOutputStream. JarOutputStream.)]
      (put-next-entry! jar-os JarFile/MANIFEST_NAME)
      (.write manifest jar-os)
      (.closeEntry jar-os)
      (doseq [src srcs
              :let [full-path (->path src-dir src)]]
        (assert (exists? full-path) (str full-path))
        (assert (-> full-path .toFile .isFile))

        (put-next-entry! jar-os src)
        (io/copy (.toFile full-path) jar-os)
        (.closeEntry jar-os))
      (doseq [file (-> classes-dir .toFile file-seq)
              :when (.isFile file)
              :let [path (.toPath file)
                    name (str (path-relative-to classes-dir path))]]
        (put-next-entry! jar-os name)
        (io/copy file jar-os)
        (.closeEntry jar-os)))))

(defn -main
  ":src-dir a path. This should already be on the classpath, so compilation works
  :srcs a seq of files, relative to :src-dir"
  [& args]
  (let [args (apply hash-map (map read-string args))
        {:keys [src-dir srcs aot classes-dir output-jar]} args
        src-dir (->path src-dir)
        output-jar (->path output-jar)
        classes-dir (->path classes-dir)]
    (assert src-dir)
    (mkdir classes-dir)
    (compile! {:src-dir src-dir
               :srcs srcs
               :classes-dir classes-dir
               :aot aot
               :output-jar output-jar})))
