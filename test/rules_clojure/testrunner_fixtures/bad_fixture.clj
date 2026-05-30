(ns rules-clojure.testrunner-fixtures.bad-fixture
  "Fixture namespace driven by testrunner-test: a :once fixture that throws
   before any test runs, exercising the fixture-error path. Not run as a target
   of its own."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [_] (throw (ex-info "fixture boom" {}))))

(deftest never-runs (is true))
