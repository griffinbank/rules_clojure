(ns coverage.coverage-test
  (:require [clojure.test :refer :all]
            [coverage.foo.core :as foo]
            [coverage.bar.core :as bar]))

(deftest same-basename-sources-are-distinct
  (is (= "foo:x" (foo/greet "x")))
  (is (= "bar:y" (bar/greet "y"))))
