(require '[clojure.java.io :as io])
(import (java.io BufferedOutputStream FileOutputStream))
(import (java.util.jar Manifest JarEntry JarFile JarOutputStream))

(def compile-dir (-> "clojure.compile.path" System/getProperty io/file))
(def compile-jar (-> "clojure.compile.jar" System/getProperty io/file))

(def namespaces (map symbol *command-line-args*))

(doseq [namespace namespaces]
  (compile namespace))

(def manifest
  (let [m (Manifest.)]
    (doto (.getMainAttributes m)
      (.putValue "Manifest-Version" "1.0"))
    m))

(defn put-next-entry! [target name]
  (.putNextEntry target (doto (JarEntry. name) (.setTime 0))))

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
