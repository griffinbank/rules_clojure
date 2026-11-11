(ns rules-clojure.export-symbols-fixture
  "Only ever read as source, never loaded: `export-symbols` is not defined
  here. See rules-clojure.compile-test/ns-deps-includes-export-symbols-sources."
  (:require [clojure.set :as set]))

(export-symbols clojure.string
                join
                split)

(tech.v3.datatype.export-symbols/export-symbols clojure.walk
                                                postwalk)

;; (export-symbols no.such.namespace.exists foo)
