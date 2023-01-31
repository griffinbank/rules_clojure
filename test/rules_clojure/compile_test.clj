(ns rules-clojure.compile-test
  (:require [clojure.test :refer :all]
            [rules-clojure.compile :as c]
            [rules-clojure.persistent-classloader :as pcl]))

(deftest contains-protocols
  (is (c/contains-protocols? 'rules-clojure.persistent-classloader))

  (is (not (c/contains-protocols? 'rules-clojure.compile-test))))
