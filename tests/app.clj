(ns tests.app
    (:gen-class :name aot.CompiledAppClass)
    (:require [tests.library :as lib])
    (:use clojure.test))

(defn echo [message] (str "app " (lib/echo message)))

(defn -main [& args] (println "app main" (lib/echo (apply str args))))
