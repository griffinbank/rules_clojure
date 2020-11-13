(ns tests.app-test
    (:require [tests.app :as app])
    (:use clojure.test))

(deftest app
    (is (= (app/echo "message") "app library message")))
