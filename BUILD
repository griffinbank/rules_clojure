load(":toolchains.bzl", "clojure_toolchain")
load(":rules.bzl", "clojure_repl")

package(default_visibility = ["//visibility:public"])
exports_files(glob(["deps.edn", "src/main/resources/**/*.clj"]))

toolchain_type(
    name = "toolchain_type",
    visibility = ["//visibility:public"])

clojure_toolchain(
    name = "default_clojure_toolchain")

toolchain(
    name = "rules_clojure_default_toolchain",
    toolchain = ":default_clojure_toolchain",
    toolchain_type = ":toolchain_type")
