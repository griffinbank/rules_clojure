(ns rules-clojure.bootstrap-gazelle-server
  (:require [rules-clojure.bootstrap-gen-build :as bgb]
            [rules-clojure.jar :as jar]
            [rules-clojure.fs :as fs]))

(def nses-to-compile
  "gen-build's compile set plus the gazelle-server namespace itself. Reuses
  bootstrap-gen-build/nses-to-compile rather than duplicating the list, so
  adding a tools.deps / namespace-reader dependency in one place updates
  both deploy jars."
  (conj bgb/nses-to-compile 'rules-clojure.gazelle-server))

(def classes-dir "gazelle-server-classes")

(defn -main [jar-path]
  (fs/clean-directory (fs/->path classes-dir))

  (binding [*compile-path* classes-dir]
    (doseq [n nses-to-compile]
      ;; Wrap each compile so the genrule stack trace names the failing ns
      ;; instead of just pointing into clojure.core/load.
      (try
        (compile n)
        (catch Throwable t
          (throw (ex-info (str "compile failed for " n) {:ns n} t))))))

  (jar/create-jar {:aot-nses '[rules-clojure.gazelle-server]
                   :classes-dir (fs/->path classes-dir)
                   :output-jar (fs/->path jar-path)}))
