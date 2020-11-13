(ns tests.library (:gen-class))

(defn echo [message] (str "library " message))

(defn -main [& args] (print "library main" (apply str args)))
