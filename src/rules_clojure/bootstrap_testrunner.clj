(ns rules-clojure.bootstrap-testrunner
  (:require [rules-clojure.jar :as jar])
  (:import [java.nio.file Files Path Paths]))

(def nses-to-compile
  '[rules-clojure.testrunner])

(def classes-dir "compile-testrunner")

(defn ->path [& dirs]
  (let [[d & dr] dirs
        d (if (string? d)
            (Paths/get d (into-array String []))
            d)]
    (assert d (print-str "path does not exist:" d))
    (reduce (fn [^Path p ^String dir]
              (.resolve p dir)) d
            (rest (map str dirs)))))

(defn create-directories [path]
  (Files/createDirectories path (into-array java.nio.file.attribute.FileAttribute [])))

(defn -main [jar-dir]
  (assert (not= classes-dir jar-dir))
  (let [classes-path (->path classes-dir)]
    (create-directories classes-path)
    (binding [*compile-path* classes-dir]
      (doseq [n nses-to-compile]
        (compile n)))

    (jar/create-jar {:aot-nses '[rules-clojure.testrunner]
                     :classes-dir classes-path
                     :output-jar (->path jar-dir)})))
