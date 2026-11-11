(ns rules-clojure.compile-test
  (:refer-clojure :exclude [agent await await-for send])
  (:require [clojure.data]
            [clojure.test :refer :all]
            [rules-clojure.compile :as c]))

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

(deftest export-symbols-deps-test
  (testing "finds the source namespace of each export-symbols form"
    (is (= '#{a.b c.d}
           (c/export-symbols-deps
            "(export-symbols a.b x y)\n(foo (export-symbols c.d z))"))))
  (testing "finds qualified calls"
    (is (= '#{a.b}
           (c/export-symbols-deps
            "(tech.v3.datatype.export-symbols/export-symbols a.b x)"))))
  (testing "ignores the macro definition and other uses of the name"
    (is (= #{}
           (c/export-symbols-deps
            "(defmacro export-symbols [src-ns & syms])\n(export-symbols-foo a.b)")))))

(deftest ns-deps-includes-export-symbols-sources
  (let [deps (#'c/ns-deps- 'rules-clojure.export-symbols-fixture)]
    (testing "declared deps are kept"
      (is (contains? deps 'clojure.set)))
    (testing "export-symbols sources are dependencies"
      (is (contains? deps 'clojure.string))
      (is (contains? deps 'clojure.walk)))
    (testing "namespaces without source on the classpath are dropped"
      (is (not (contains? deps 'no.such.namespace.exists))))
    (testing "the namespace is not its own dependency"
      (is (not (contains? deps 'rules-clojure.export-symbols-fixture))))))

(deftest load->ns
  (are [in out] (= out (c/load->ns in))
    "/clojure/tools/deps/extensions/maven" 'clojure.tools.deps.extensions.maven))

(deftest src-available?-test
  (testing "returns true for namespaces with source on the classpath"
    (is (true? (c/src-available? 'clojure.string)))
    (is (true? (c/src-available? 'clojure.set))))
  (testing "returns false for AOT-compiled namespace with no source on classpath"
    (is (c/compiled? 'rules-clojure.aot-only-fixture))
    (is (false? (c/src-available? 'rules-clojure.aot-only-fixture))))
  (testing "returns false for namespaces without source"
    (is (false? (c/src-available? 'no.such.namespace.exists)))))
