(ns rules-clojure.bootstrap-compiler
  (:require [rules-clojure.jar :as jar]
            [rules-clojure.fs :as fs]))

(def nses-to-compile
  '[rules-clojure.java.classpath
    rules-clojure.fs
    rules-clojure.tools.reader.reader-types
    rules-clojure.tools.reader.impl.utils
    rules-clojure.tools.reader.impl.errors
    rules-clojure.tools.reader.impl.commons
    rules-clojure.tools.reader.default-data-readers
    rules-clojure.compile
    rules-clojure.util])

(def classes-dir (fs/->path "classes"))

(defn -main [compile-output-path]
  (fs/clean-directory classes-dir)

  (binding [*compile-path* (str classes-dir)]
    (doseq [n nses-to-compile]
      (compile n)))

  (jar/create-jar {:aot-nses '[rules-clojure.compile]
                   :classes-dir classes-dir
                   :output-jar (fs/->path compile-output-path)}))
