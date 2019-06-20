(require '[clojure.java.io :as io])
(import (java.io PushbackReader BufferedOutputStream FileOutputStream))
(import (java.util.jar Manifest JarEntry JarFile JarOutputStream))

(def output (-> "clojure.compile.path" (System/getProperty) (io/file)))

(def jar (-> "clojure.compile.jar" (System/getProperty) (io/file)))

(def aot (filter (fn [s] (not (empty? s))) (-> (or (System/getProperty "clojure.compile.aot")) (.split ",") seq)))

(def sources (map io/file *command-line-args*))

(defn ns-symbol [file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (loop [form (read reader false ::done)]
      (if (and (list? form) (= 'ns (first form)))
        (second form)
        (when-not (= ::done form) (recur reader))))))

(defn ns-path [namespace]
  (-> namespace name (.replace \- \_) (.replace \. \/)))

(defn target [file]
  (io/file *compile-path* (str (-> file ns-symbol ns-path) ".clj")))

(doseq [source sources]
  (let [target (target source)]
    (io/make-parents target)
    (io/copy source target)))

(doseq [namespace aot]
  (-> namespace symbol compile))

(def manifest
  (let [m (Manifest.)]
    (doto (.getMainAttributes m)
      (.putValue "Manifest-Version" "1.0"))
    m))

(defn put-next-entry! [target name]
  (.putNextEntry target (doto (JarEntry. name) (.setTime 0))))

(with-open [jar-os (-> jar FileOutputStream. BufferedOutputStream. JarOutputStream.)]
  (put-next-entry! jar-os JarFile/MANIFEST_NAME)
  (.write manifest jar-os)
  (.closeEntry jar-os)
  (doseq [file (file-seq output)
          :when (.isFile file)
          :let [name (.replaceFirst (str file) (str output "/") "")]]
    (put-next-entry! jar-os name)
    (io/copy file jar-os)
    (.closeEntry jar-os)))
