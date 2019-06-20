(ns tests.library (:gen-class))

(defn echo [message] message)

(defn -main [& args] (println args))
