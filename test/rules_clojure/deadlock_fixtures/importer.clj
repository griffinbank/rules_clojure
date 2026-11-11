(ns rules-clojure.deadlock-fixtures.importer
  "Loads rules-clojure.deadlock-fixtures.src from its body, under the lock."
  (:require [rules-clojure.deadlock-fixtures.lock :refer [export-symbols]]))

(export-symbols rules-clojure.deadlock-fixtures.src
                answer)
