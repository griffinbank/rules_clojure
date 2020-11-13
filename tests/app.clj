(ns tests.app
    (:require [tests.library :as lib])
    (:use clojure.test))

(defn echo [message] (str "app " (lib/echo message)))

(defn -main [& args] (print "app main" (lib/echo (apply str args))))
