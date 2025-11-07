(ns rules-clojure.testrunner
  (:require [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :as stack]
            [clojure.test :as c.test])
  (:gen-class))

(defn pp-str [x]
  (with-out-str (pprint x)))

(defmulti
  ^{:doc "Prettier report printing method.
    Code is taken from clojure.test, with some added pretty-printing."}
  pretty-report :type)

(defmethod pretty-report :default [m]
  (c.test/with-test-out (prn m)))

(defmethod pretty-report :pass [_]
  (c.test/with-test-out (c.test/inc-report-counter :pass)))

(defmethod pretty-report :fail [m]
  (c.test/with-test-out
    (c.test/inc-report-counter :fail)
    (println "\nFAIL in" (c.test/testing-vars-str m))
    (when (seq c.test/*testing-contexts*) (println (c.test/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (print "expected:\n" (pp-str (:expected m)))
    (print "actual:\n" (pp-str (:actual m)))))

(defmethod pretty-report :error [m]
  (c.test/with-test-out
    (c.test/inc-report-counter :error)
    (println "\nERROR in" (c.test/testing-vars-str m))
    (when (seq c.test/*testing-contexts*) (println (c.test/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (print "expected:\n" (pp-str (:expected m)))
    (print "actual: ")
    (let [actual (:actual m)]
      (if (instance? Throwable actual)
        (stack/print-cause-trace actual c.test/*stack-trace-depth*)
        (prn actual)))))

(defmethod pretty-report :summary [m]
  (c.test/with-test-out
    (println "\nRan" (:test m) "tests containing"
             (+ (:pass m) (:fail m) (:error m)) "assertions.")
    (println (:fail m) "failures," (:error m) "errors.")))

(defmethod pretty-report :begin-test-ns [m]
  (c.test/with-test-out
    (println "\nTesting" (ns-name (:ns m)))))

;; Ignore these message types:
(defmethod pretty-report :end-test-ns [_])
(defmethod pretty-report :begin-test-var [_])
(defmethod pretty-report :end-test-var [_])

(defn -main [& args]
  (assert (string? (first args)) (print-str "first argument must be a string, got" args))
  (let [the-ns (-> args first symbol)]
    (println "testing" the-ns)
    (try
      (require the-ns)
      (binding [c.test/report pretty-report]
        (let [test-report (clojure.test/run-tests the-ns)]
          (println test-report)
          (if (and (zero? (:fail test-report))
                   (zero? (:error test-report)))
            (System/exit 0)
            (System/exit 1))))
      (catch Throwable t
        (println t)
        (System/exit 1)))))
