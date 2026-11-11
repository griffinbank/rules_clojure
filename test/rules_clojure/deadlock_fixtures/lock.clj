(ns rules-clojure.deadlock-fixtures.lock
  "Stands in for tech.v3.datatype.export-symbols: a global lock held around
  `load-lib` in the init of each exported var.

  Unlike the real macro, the exported value is resolved at run time, so the
  importing namespace compiles without its source already being loaded."
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.locks ReentrantLock]))

(def ^ReentrantLock load-lib-lock (ReentrantLock.))

;; Counted down once a namespace holds `load-lib-lock`, so the waiter can be
;; made to ask for the lock after the importer has taken it.
(def ^CountDownLatch lock-held (CountDownLatch. 1))

(defn load-locked
  "load-lib `src-ns` while holding `load-lib-lock`, as export-symbols does."
  [src-ns]
  (.lock load-lib-lock)
  (try
    (.countDown lock-held)
    (#'clojure.core/load-lib nil src-ns)
    (finally
      (.unlock load-lib-lock))))

(defmacro export-symbols
  [src-ns & syms]
  `(do
     ~@(for [sym syms]
         `(def ~sym
            (do
              (load-locked '~src-ns)
              (deref (resolve '~(symbol (name src-ns) (name sym)))))))))
