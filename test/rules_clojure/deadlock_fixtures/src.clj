(ns rules-clojure.deadlock-fixtures.src
  "Exported by importer. Needs waiter loaded first, so loading it means
  waiting for waiter's compile."
  (:require [rules-clojure.deadlock-fixtures.waiter]))

(def answer 42)
