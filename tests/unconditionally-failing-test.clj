(ns tests.unconditionally-failing-test (:use clojure.test))

(deftest must-fail (is false))
