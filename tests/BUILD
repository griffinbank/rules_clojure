load(":library.bzl", library_test_suite = "test_suite")
load("//rules:repl.bzl", "clojure_repl")
load("//rules:test.bzl", "clojure_test")

library_test_suite()

clojure_test(
    name = "example_test",
    srcs = ["test.clj"]
)

clojure_repl(
    name = "repl"
)

clojure_repl(
    name = "repl-in-ns",
    deps = [":library_under_test"],
    ns = "tests.library"
)
