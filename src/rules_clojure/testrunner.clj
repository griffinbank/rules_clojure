(ns rules-clojure.testrunner
  (:require [clojure.test])
  (:import [sun.misc Signal SignalHandler]
           [java.lang.management ManagementFactory])
  (:gen-class))

(def old-handler
  (Signal/handle
    (Signal. "TERM")
    (reify SignalHandler
      (handle [_ signal]
        (run! println (.dumpAllThreads (ManagementFactory/getThreadMXBean) true true))
        (when-not (#{SignalHandler/SIG_DFL SignalHandler/SIG_IGN} old-handler)
          (.handle old-handler signal))))))

(defn -main [& args]
  (assert (string? (first args)) (print-str "first argument must be a string, got" args))
  (let [the-ns (-> args first symbol)]
    (println "testing" the-ns)
    (try
      (require the-ns)
      (let [test-report (clojure.test/run-tests the-ns)]
        (println test-report)
        (if (and (zero? (:fail test-report))
                 (zero? (:error test-report)))
          (System/exit 0)
          (System/exit 1)))
      (catch Throwable t
        (println t)
        (System/exit 1)))))
