(require '[clojure.java.io :as io])
(import (java.io PushbackReader BufferedOutputStream FileOutputStream ByteArrayInputStream))
(import (java.util.jar Manifest JarEntry JarFile JarOutputStream))

(def output (-> "clojure.compile.path" (System/getProperty) (io/file)))

(def jar (-> "clojure.compile.jar" (System/getProperty) (io/file)))

(def aot (filter (fn [s] (not (empty? s))) (-> (or (System/getProperty "clojure.compile.aot")) (.split ",") seq)))

(def sources (map io/file *command-line-args*))

(defn namespace-of [file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (loop [form (read reader false ::done)]
      (if (and (list? form) (= 'ns (first form)))
        form
        (when-not (= ::done form) (recur reader))))))

(defn path [namespace]
  (-> namespace second str (.replace \- \_) (.replace \. \/)))

(defn target [file]
  (io/file *compile-path* (str (-> file namespace-of path) ".clj")))

(doseq [source sources]
  (let [target (target source)]
    (io/make-parents target)
    (io/copy source target)))

(doseq [namespace aot]
  (-> namespace symbol compile))

(defn put-next-entry! [target name]
  (.putNextEntry target (doto (JarEntry. name) (.setTime 0))))

(with-open [jar-os (-> jar FileOutputStream. BufferedOutputStream. JarOutputStream.)]
  (put-next-entry! jar-os JarFile/MANIFEST_NAME)
  (-> "Manifest-Version: 1.0" (.getBytes) (ByteArrayInputStream.) (Manifest.) (.write jar-os))
  (doseq [file (file-seq output)
          :when (.isFile file)
          :let [name (.replaceFirst (str file) (str output "/") "")]]
    (put-next-entry! jar-os name)
    (io/copy file jar-os)))
