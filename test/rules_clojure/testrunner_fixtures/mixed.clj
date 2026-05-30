(ns rules-clojure.testrunner-fixtures.mixed
  "Fixture namespace driven by testrunner-test: one passing test, one assertion
   failure, and one test that throws. Not run as a target of its own."
  (:require [clojure.test :refer [deftest is]]))

(deftest a-pass (is true))

(deftest a-fail (is (= 1 2)))

(deftest an-error (throw (ex-info "boom <&>" {})))
