load("@rules_clojure//:toolchains.bzl", "clojure_toolchain")

toolchain_type(
    name = "toolchain",
    visibility = ["//visibility:public"],
)

clojure_toolchain(
    name = "default_clojure_toolchain",
    classpath = [
        "@rules_clojure_maven//:org_clojure_clojure",
        "@rules_clojure_maven//:org_clojure_spec_alpha",
        "@rules_clojure_maven//:org_clojure_core_specs_alpha",
    ],
)

toolchain(
    name = "clojure_toolchain",
    toolchain = ":default_clojure_toolchain",
    toolchain_type = "@rules_clojure//:toolchain",
)
