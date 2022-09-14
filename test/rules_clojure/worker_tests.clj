(ns rules-clojure.worker-test
  (:require [clojure.test :refer :all]
            [rules-clojure.worker :as worker]))


(deftest starts
  (worker/-main []))
