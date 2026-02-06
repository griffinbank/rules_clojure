(ns rules-clojure.bootstrap-gen-build
  (:require [rules-clojure.jar :as jar]
            [rules-clojure.fs :as fs]))

(def nses-to-compile
  '[clojure.tools.deps.extensions,
    clojure.tools.deps.util.session,
    clojure.tools.deps.util.io,
    clojure.tools.deps.util.dir,
    clojure.tools.deps.util.concurrent,
    clojure.tools.deps,
    rules-clojure.tools.reader.default-data-readers,

    rules-clojure.tools.reader.impl.inspect,
    rules-clojure.tools.reader.impl.utils,

    rules-clojure.tools.reader.reader-types,
    rules-clojure.tools.reader.impl.errors,
    rules-clojure.tools.reader.impl.commons,
    rules-clojure.tools.reader,

    rules-clojure.java.classpath,
    rules-clojure.namespace.file,
    rules-clojure.namespace.find,
    rules-clojure.gen-build])

(def classes-dir "gen-build-classes")

(defn -main [jar-path]
  (fs/clean-directory (fs/->path classes-dir))

  (binding [*compile-path* classes-dir]
    (doseq [n nses-to-compile]
      (compile n)))

  (jar/create-jar {:aot-nses '[rules-clojure.gen-build]
                   :classes-dir (fs/->path classes-dir)
                   :output-jar (fs/->path jar-path)}))
