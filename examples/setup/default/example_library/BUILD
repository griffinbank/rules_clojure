load("@rules_clojure//:rules.bzl", "clojure_library")

clojure_library(
    name = "lib1",
    srcs = ["src/example/core.clj"],
    aot = ["example.core"],
    resource_strip_prefix = "example_library/src",
)
