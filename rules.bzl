load("//rules:library.bzl", _clojure_library = "clojure_library")
load("//rules:repl.bzl", _clojure_repl = "clojure_repl")
load("//rules:test.bzl", _clojure_test = "clojure_test")

clojure_library = _clojure_library
clojure_repl = _clojure_repl
clojure_test = _clojure_test
