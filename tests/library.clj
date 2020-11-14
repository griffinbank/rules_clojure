(ns tests.library (:gen-class))

(defn echo [message] (str "library " message))

(defn -main [& args] (println "library main" (apply str args)))
