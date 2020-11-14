load("@rules_clojure//:rules.bzl", "clojure_library", "clojure_test")

clojure_library(
    name = "app",
    srcs = ["app.clj"],
    deps = ["//tests/transitive/greeter"],
)

clojure_test(
    name = "test",
    size = "small",
    srcs = ["test.clj"],
    deps = [":app"],
)
