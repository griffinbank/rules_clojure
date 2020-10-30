load("@rules_clojure//:toolchain.bzl", "clojure_toolchain")

toolchain_type(
    name = "toolchain",
    visibility = ["//visibility:public"],
)

clojure_toolchain(
    name = "default_clojure_toolchain",
    classpath = [
        "@org_clojure",
        "@org_clojure_spec_alpha",
        "@org_clojure_core_specs_alpha",
    ],
)

toolchain(
    name = "clojure_toolchain",
    toolchain = ":default_clojure_toolchain",
    toolchain_type = "@rules_clojure//:toolchain",
)
