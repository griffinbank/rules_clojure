(ns rules-clojure.testrunner-fixtures.passing
  "Fixture namespace driven by testrunner-test: two passing tests, no failures
   or errors. Not run as a target of its own."
  (:require [clojure.test :refer [deftest is]]))

(deftest a-pass (is true))

(deftest another-pass (is (= 1 1)))
