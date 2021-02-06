(require '[clojure.java.io :as io])
(import (java.io PushbackReader BufferedOutputStream FileOutputStream))
(import (java.util.jar Manifest JarEntry JarFile JarOutputStream))

(defn ns-symbol [file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (loop [form (read reader false ::done)]
      (if (and (list? form) (= 'ns (first form)))
        (second form)
        (when-not (= ::done form) (recur reader))))))

(defn ns-path [file]
  (-> file ns-symbol name (.replace \- \_) (.replace \. \/) (str ".clj")))

(def compile-jar (-> "clojure.compile.jar" System/getProperty io/file))

(def sources (map io/file *command-line-args*))

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
  (doseq [file sources]
    (put-next-entry! jar-os (ns-path file))
    (io/copy file jar-os)
    (.closeEntry jar-os)))
