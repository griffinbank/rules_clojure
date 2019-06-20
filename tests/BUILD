load("//rules:library.bzl", "clojure_library")
load("//rules:repl.bzl", "clojure_repl")
load("//rules:test.bzl", "clojure_test")
load(":library.bzl", library_test_suite = "test_suite")

clojure_library(
    name = "library",
    srcs = ["library.clj"],
    aots = ["tests.library"]
)

clojure_test(
    name = "test",
    srcs = ["test.clj"],
    deps = [":library"]
)

clojure_repl(
    name = "repl",
    deps = [":library"],
    ns = "tests.library"
)

java_binary(
    name = "binary",
    main_class = "tests.library",
    runtime_deps = [":library"]
)

library_test_suite()
