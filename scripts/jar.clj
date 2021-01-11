;; create a jar

(require '[clojure.java.io :as io]
         '[clojure.string :as str])
(import [java.io BufferedOutputStream FileOutputStream]
        [java.util.jar Manifest JarEntry JarFile JarOutputStream]
        [java.nio.file Files Path Paths FileSystem FileSystems])


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

(def input-files (-> "bazel.jar.input-files" System/getProperty (str/split #",") (->> (map ->path))))
(def output-jar (-> "bazel.jar.output-jar" System/getProperty ->path))
(def nses-to-compile (-> "bazel.jar.aot" System/getProperty (str/split #",") (->> (filter identity) (filter seq))))
(def compile-dir (-> "clojure.compile.path" System/getProperty ->path))

(doseq [ns nses-to-compile]
  (println "compiling" ns (class ns))
  (compile (symbol ns)))

(with-open [jar-os (-> output-jar .toFile FileOutputStream. BufferedOutputStream. JarOutputStream.)]
  (put-next-entry! jar-os JarFile/MANIFEST_NAME)
  (.write manifest jar-os)
  (.closeEntry jar-os)
  (doseq [path input-files
          file (-> path .toFile file-seq)
          :when (.isFile file)
          :let [path (.toPath file)
                ;; FIXME hack. We're stripping off one directory, should be 'strip any leading `:path` in deps.edn`
                name (str (.subpath path 1 (.getNameCount path)))]]
    (put-next-entry! jar-os name)
    (io/copy file jar-os)
    (.closeEntry jar-os))
  (doseq [file (-> compile-dir .toFile file-seq)
          :when (.isFile file)
          :let [path (.toPath file)
                name (str (path-relative-to compile-dir path))]]
    (put-next-entry! jar-os name)
    (io/copy file jar-os)
    (.closeEntry jar-os)))
