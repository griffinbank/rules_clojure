(ns tests.library-test
  (:require [tests.library :as lib])
  (:use clojure.test))

(deftest library
  (is (= (lib/echo "test")) "library test"))
