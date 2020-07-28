load("@rules_clojure//:rules.bzl", "clojure_library", "clojure_repl", "clojure_test")

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

load(":library.bzl", library_test_suite = "test_suite")

library_test_suite()

load(":binary.bzl", binary_test_suite = "test_suite")

binary_test_suite()
