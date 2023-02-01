(ns rules-clojure.jar-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [rules-clojure.jar :as jar]
            [rules-clojure.util :as util]))

(deftest script
  (is (jar/get-compilation-script {:classes-dir "classes"
                                   :output-jar "test.jar"
                                   :srcs ["rules_clojure/compile.jar"]
                                   :src-dir ""
                                   :classpath (map io/file (util/classpath))}
                                  ["clojure.core"])))
