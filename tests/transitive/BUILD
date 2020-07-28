load("@rules_clojure//:rules.bzl", "clojure_library", "clojure_test")

clojure_library(
    name = "app",
    srcs = ["app.clj"],
    deps = ["//tests/transitive/greeter"],
    aots = ["tests.transitive.app"]
)

clojure_test(
    name = "test",
    srcs = ["test.clj"],
    deps = [":app"]
)
