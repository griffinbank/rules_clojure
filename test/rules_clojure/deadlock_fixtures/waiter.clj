(ns rules-clojure.deadlock-fixtures.waiter
  "Wants the lock, but only after importer has taken it. If importer isn't
  going to take it before waiter finishes, give up waiting after a few
  seconds and take it anyway."
  (:require [rules-clojure.deadlock-fixtures.lock :as lock])
  (:import [java.util.concurrent TimeUnit]))

(.await ^java.util.concurrent.CountDownLatch lock/lock-held 5 TimeUnit/SECONDS)

(.lock ^java.util.concurrent.locks.ReentrantLock lock/load-lib-lock)
(.unlock ^java.util.concurrent.locks.ReentrantLock lock/load-lib-lock)

(def ok true)
