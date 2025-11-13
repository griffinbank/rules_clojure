(ns rules-clojure.compile-test
  (:refer-clojure :exclude [agent await await-for send])
  (:require [clojure.data]
            [clojure.test :refer :all]
            [rules-clojure.compile :as c]
            [rules-clojure.fs :as fs]))

(definterface Testing
  (foo [x]))

(deftest require->nses
  (are [args nses] (do
                     (when-not (= nses (c/require->nses nil args))
                       (println (clojure.data/diff nses (c/require->nses nil args))))
                     (= nses (c/require->nses nil args)))
    '[clojure.core] '[clojure.core]
    '[[rules-clojure.compile :as c]] '[rules-clojure.compile]
    '[[instaparse.auto-flatten-seq :as afs] [instaparse.util :refer [throw-illegal-argument-exception]]] '[instaparse.auto-flatten-seq instaparse.util]
    '[[clojure.core :as clj]
      [manifold.deferred :as d]
      [potemkin.types :refer [deftype+]]
      [clj-commons.primitive-math :as p]
      [manifold.utils :as utils]
      [manifold.time :as time]
      [manifold.stream
       [core :as core]
       [default :as default]
       async
       random-access
       iterator
       queue
       seq
       deferred]
      [clojure.tools.logging :as log]] '[clojure.core
                                         manifold.deferred
                                         potemkin.types
                                         clj-commons.primitive-math
                                         manifold.utils
                                         manifold.time
                                         manifold.stream.core
                                         manifold.stream.default
                                         manifold.stream.async
                                         manifold.stream.random-access
                                         manifold.stream.iterator
                                         manifold.stream.queue
                                         manifold.stream.seq
                                         manifold.stream.deferred
                                         clojure.tools.logging]

    '[[foo.bbq :as-alias bbq]] '[] ;; as-alias does not load
    ))

(deftest dependencies []
  (let [graph '{a #{b}
                b #{c d}
                c #{d}}]
    (is (= '#{b c d} (c/transitive-dependencies graph 'a)))))

(deftest cycle-detection []
  (is (not (c/cycle? '{a #{b}
                       b #{c d}
                       c #{d}} 'e 'f)))
  (is (c/cycle? '{a #{b}} 'b 'a))
  (is (c/cycle? '{fipp.ednize #{fipp.ednize.instant}} 'fipp.ednize.instant 'fipp.ednize))
  (is (c/cycle? '{a #{b}
                  b #{c}} 'c 'a))
  (is (c/cycle? '{a #{b}
                  b #{c}
                  c #{d}} 'd 'b)))


(deftest compile-test
  (let [compile-path (fs/new-temp-dir "classes")]
    (is (not (seq (fs/ls compile-path))))
    (c/compile! (str compile-path) ["example.core"] *out*)
    (is (seq (fs/ls compile-path)))))

(deftest load->ns
  (are [in out] (= out (c/load->ns in))
    "/clojure/tools/deps/alpha/extensions/maven" 'clojure.tools.deps.alpha.extensions.maven))
