(ns rules-clojure.bootstrap-worker
  (:require [rules-clojure.jar :as jar]
            [rules-clojure.fs :as fs]))

(def nses-to-compile
  '[clojure.core.cache
    clojure.data.json
    rules-clojure.java.classpath
    rules-clojure.tools.reader.default-data-readers,
    rules-clojure.tools.reader.impl.commons,
    rules-clojure.tools.reader.impl.inspect,
    rules-clojure.tools.reader.impl.errors,
    rules-clojure.tools.reader.impl.utils,
    rules-clojure.tools.reader.reader-types,
    rules-clojure.tools.reader,
    rules-clojure.persistentClassLoader
    rules-clojure.util
    rules-clojure.namespace.file
    rules-clojure.namespace.find
    rules-clojure.namespace.parse
    rules-clojure.persistent-classloader
    rules-clojure.jar
    rules-clojure.fs
    rules-clojure.worker])

(defn -main [worker-output-path ]
  (fs/clean-directory (fs/->path "worker-classes"))

  (binding [*compile-path* "worker-classes"]
    (doseq [n nses-to-compile]
      (compile n)))

  (jar/create-jar {:aot-nses nses-to-compile
                   :classes-dir (fs/->path "worker-classes")
                   :output-jar (fs/->path worker-output-path)}))
