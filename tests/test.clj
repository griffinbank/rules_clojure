(ns tests.test
  (:require [tests.library :as lib])
  (:use clojure.test))

(deftest library
  (is (= "test" (lib/echo "test"))))
