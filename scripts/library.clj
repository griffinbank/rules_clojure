(require '[clojure.java.io :as io])
(import (java.io BufferedOutputStream FileOutputStream))
(import (java.util.jar Manifest JarEntry JarFile JarOutputStream))

(load-file "scripts/ns.clj")

(def compile-dir (-> "clojure.compile.path" System/getProperty io/file))
(def compile-jar (-> "clojure.compile.jar" System/getProperty io/file))
(def compile-aot (-> "clojure.compile.aot" System/getProperty or (.split ",") (->> (filter not-empty) (map symbol))))

(def sources (map io/file *command-line-args*))

(def manifest
  (let [m (Manifest.)]
    (doto (.getMainAttributes m)
      (.putValue "Manifest-Version" "1.0"))
    m))

(defn put-next-entry! [target name]
  (.putNextEntry target (doto (JarEntry. name) (.setTime 0))))

(doseq [source sources]
  (let [target (io/file compile-dir (ns-path source))]
    (io/make-parents target)
    (io/copy source target)))

(doseq [namespace compile-aot]
  (compile namespace))

(with-open [jar-os (-> compile-jar FileOutputStream. BufferedOutputStream. JarOutputStream.)]
  (put-next-entry! jar-os JarFile/MANIFEST_NAME)
  (.write manifest jar-os)
  (.closeEntry jar-os)
  (doseq [file (file-seq compile-dir)
          :when (.isFile file)
          :let [name (.replaceFirst (str file) (str compile-dir "/") "")]]
    (put-next-entry! jar-os name)
    (io/copy file jar-os)
    (.closeEntry jar-os)))
