(ns rules-clojure.bootstrap-compiler
  (:require [rules-clojure.jar :as jar]
            [rules-clojure.fs :as fs]))

(def nses-to-compile
  '[rules-clojure.fs
    rules-clojure.compile
    rules-clojure.util])

(defn -main [compile-output-path]
  (fs/clean-directory (fs/->path "compile-classes"))

  (binding [*compile-path* "compile-classes"]
    (doseq [n nses-to-compile]
      (compile n)))

  (jar/create-jar {:aot-nses '[rules-clojure.compile]
                   :classes-dir (fs/->path "compile-classes")
                   :output-jar (fs/->path compile-output-path)}))
