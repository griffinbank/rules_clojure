(ns rules-clojure.fs-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [rules-clojure.fs :as fs]))

(defn count-lines [f]
  (-> (shell/sh "wc" "-l" f)
                 :out
                 str/trim
                 first
                 str
                 Long/parseLong))

(deftest spit-file
  (let [tmp-file "/tmp/spit-test.txt"]
    (spit tmp-file "test with spit")
    (is (= 0 (count-lines tmp-file)))
    (fs/spit-file tmp-file "test with fs/spit-file")
    (is (= 1 (count-lines tmp-file)))))
