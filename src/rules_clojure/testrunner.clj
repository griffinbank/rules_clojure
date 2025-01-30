(ns rules-clojure.testrunner
  (:require [clojure.test]
            [cloverage.coverage :as coverage])
  (:gen-class))

(defn -main [& args]
  (assert (string? (first args)) (print-str "first argument must be a string, got" args))
  (let [the-ns (-> args first symbol)]
    (println "testing" the-ns)
    (try
      (require the-ns)
      (coverage/-main (str the-ns) "--lcov" "--src-ns-path" "src")
      (catch Throwable t
        (println t)
        (System/exit 1)))))
