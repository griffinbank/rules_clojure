load("@rules_clojure//:toolchain.bzl", "clojure_toolchain")

toolchain_type(name = "toolchain_type")

clojure_toolchain(
    name = "clojure_10_toolchain",
)

toolchain(
    name = "clojure_toolchain",
    toolchain = ":clojure_10_toolchain",
    toolchain_type = ":toolchain_type",
)
