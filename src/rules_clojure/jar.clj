(ns rules-clojure.jar
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
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

(defn absolute [path]
  (.toAbsolutePath path))

(defn path-relative-to
  [a b]
  (.relativize (absolute a) (absolute b)))

(defn -main [& args]
  (let [args (apply hash-map (map read-string args))
        {:keys [input-dir aot classes-dir output-jar]} args
        input-dir (->path input-dir)
        output-jar (->path output-jar)
        classes-dir (->path classes-dir)]

    (System/setProperty "clojure.compile.path" (str classes-dir))
    (doseq [ns aot]
      (println "compiling" ns (class ns))
      (compile (symbol ns)))

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
