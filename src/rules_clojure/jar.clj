(ns rules-clojure.jar
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.parse :as parse])
  (:import [java.io BufferedOutputStream FileOutputStream]
           [java.util.jar Manifest JarEntry JarFile JarOutputStream]
           [java.nio.file Files Path Paths FileSystem FileSystems]))

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

(defn non-transitive-compile
  [input-dir ns]
  (assert (symbol? ns))
  (->> (-> input-dir absolute path->file)
       (find/find-ns-decls-in-dir)
       (filter (fn [ns-decl]
                 (let [found-ns (-> ns-decl second)]
                   (assert (symbol? found-ns))
                   (= ns found-ns))))
       (first)
       ((fn [ns-decl]
          (let [deps (parse/deps-from-ns-decl ns-decl)]
            (doseq [d deps]
              (require d))
            (compile ns))))))

(defn -main [& args]
  (let [args (apply hash-map (map read-string args))
        {:keys [input-dir aot classes-dir output-jar]} args
        input-dir (->path input-dir)
        output-jar (->path output-jar)
        classes-dir (->path *compile-path*)]

    (when (seq aot)
      (set! *compile-path* (str classes-dir "/"))
      (assert (exists? (->path *compile-path*)))

      (doseq [ns aot]
        (non-transitive-compile input-dir (symbol ns))))

    (with-open [jar-os (-> output-jar .toFile FileOutputStream. BufferedOutputStream. JarOutputStream.)]
      (put-next-entry! jar-os JarFile/MANIFEST_NAME)
      (.write manifest jar-os)
      (.closeEntry jar-os)
      (doseq [file (-> input-dir .toFile file-seq)
              :when (.isFile file)
              :let [path (.toPath file)
                    name (str (path-relative-to input-dir path))]]
        (put-next-entry! jar-os name)
        (io/copy file jar-os)
        (.closeEntry jar-os))
      (doseq [file (-> classes-dir .toFile file-seq)
              :when (.isFile file)
              :let [path (.toPath file)
                    name (str (path-relative-to classes-dir path))]]
        (put-next-entry! jar-os name)
        (io/copy file jar-os)
        (.closeEntry jar-os)))))
