load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_clojure//:rules.bzl", "clojure_library", "clojure_repl", "clojure_test")
load(":library.bzl", library_test_suite = "test_suite")
load(":binary.bzl", binary_test_suite = "test_suite")

clojure_library(
    name = "library",
    srcs = ["library.clj"],
    aots = ["tests.library"],
)

clojure_test(
    name = "test",
    srcs = ["test.clj"],
    deps = [":library"],
)

clojure_repl(
    name = "repl",
    ns = "tests.library",
    deps = [":library"],
)

java_binary(
    name = "binary",
    main_class = "tests.library",
    runtime_deps = [":library"],
)

library_test_suite()

binary_test_suite()
