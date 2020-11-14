(ns tests.app-java-test
    (:import aot.CompiledAppClass)
    (:use clojure.test))

(deftest app-java
    (is (= (.getName CompiledAppClass) "aot.CompiledAppClass"))
    (is (= (.getSuperclass CompiledAppClass) java.lang.Object)))
