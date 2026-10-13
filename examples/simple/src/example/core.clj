(ns example.core
  (:require [example.util :as util])
  (:gen-class))

(defn greet [name]
  (util/exclaim (str "hello " name)))

(defn farewell [name]
  (util/exclaim (str "goodbye " name)))

(defn -main []
  (println (greet "world")))
