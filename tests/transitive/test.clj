(ns tests.transitive.test
  (:require [tests.transitive.app :as lib])
  (:use clojure.test))

(deftest transitive_deps
  (is (= "Hello Clojure Rules" (lib/greeting "Clojure" "Rules"))))
