(ns example.core-test
  (:require [clojure.test :refer :all]
            [example.core :as core]))

(deftest greet-test
  (is (= "hello world!" (core/greet "world"))))
