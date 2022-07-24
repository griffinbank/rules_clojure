(ns rules-clojure.classloader
  (:require [clojure.java.io :as io])
  (:import [java.net.URLClassLoader URL])
  (:gen-class
   :init new))

(defn -addURL [this ^URL url]
  (.addURL cl url))
