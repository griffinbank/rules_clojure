(ns rules-clojure.bootstrap
  (:require [rules-clojure.jar :as jar]
            [rules-clojure.fs :as fs]))

(def worker-nses-to-compile
  '[clojure.core.cache
    clojure.data.json
    clojure.java.classpath
    clojure.tools.namespace.file
    clojure.tools.namespace.find
    clojure.tools.namespace.parse
    clojure.tools.reader.default-data-readers,
    clojure.tools.reader.impl.commons,
    clojure.tools.reader.impl.inspect,
    clojure.tools.reader.impl.errors,
    clojure.tools.reader.impl.utils,
    clojure.tools.reader.reader-types,
    clojure.tools.reader,
    clojure.tools.namespace.parse,
    clojure.tools.namespace.dependency,
    clojure.tools.namespace.track,
    clojure.tools.namespace.file,
    clojure.tools.namespace.find
    rules-clojure.persistentClassLoader
    rules-clojure.persistent-classloader
    rules-clojure.jar
    rules-clojure.fs
    rules-clojure.worker])

(defn -main [worker-output-path compile-output-path]
  (binding [*compile-path* "worker-classes"]
    (doseq [n worker-nses-to-compile]
      (compile n)))
  (binding [*compile-path* "compile-classes"]
    (compile 'rules-clojure.compile))

  (jar/create-jar {:aot-nses worker-nses-to-compile
                   :classes-dir (fs/->path "worker-classes")
                   :output-jar (fs/->path worker-output-path)})
  (jar/create-jar {:aot-nses '[rules-clojure.compile]
                   :classes-dir (fs/->path "compile-classes")
                   :output-jar (fs/->path compile-output-path)}))
