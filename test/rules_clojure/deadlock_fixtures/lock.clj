(ns rules-clojure.deadlock-fixtures.lock
  "Stands in for tech.v3.datatype.export-symbols: a global lock held around
  `load-lib` in the init of each exported var, and a `requiring-resolve` of the
  source namespace at macroexpansion time, outside the lock."
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
  ;; Like the real macro, load the source namespace while expanding. This is
  ;; why compiling the importer from source never deadlocks: by the time a
  ;; def's init takes the lock, `load-lib` has nothing left to load. Loading
  ;; an already-compiled importer skips macroexpansion, so it does.
  (doseq [sym syms]
    (when-not (requiring-resolve (symbol (name src-ns) (name sym)))
      (throw (ex-info "not found" {:ns src-ns :sym sym}))))
  `(do
     ~@(for [sym syms]
         `(def ~sym
            (do
              (load-locked '~src-ns)
              (deref (resolve '~(symbol (name src-ns) (name sym)))))))))
