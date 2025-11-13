(ns fipp-test
  (:require [fipp.edn :as fipp]))

(defn main- [& args]
  (fipp/pprint [1 2 3]))
