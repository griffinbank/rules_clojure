load(":library.bzl", library_test_suite = "test_suite")
load("//rules:test.bzl", "clojure_test")

library_test_suite()

clojure_test(
    name = "example_test",
    srcs = ["test.clj"]
)
