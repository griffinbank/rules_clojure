(ns rules-clojure.bootstrap
  (:require [rules-clojure.jar :as jar]
            [rules-clojure.fs :as fs]))

(def nses-to-compile '[clojure.core.cache
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
                       rules-clojure.persistent-classloader
                       rules-clojure.jar
                       rules-clojure.fs
                       rules-clojure.worker])

(defn -main [compile-path output-path]
  (doseq [n nses-to-compile]
    (compile n))
  (jar/create-jar {:aot-nses nses-to-compile
                   :classes-dir (fs/->path "classes")
                   :src-dir (-> compile-path fs/->path fs/dirname fs/dirname)
                   :resources [(fs/->path "rules_clojure/compile.clj")]
                   :output-jar (fs/->path output-path)}))
