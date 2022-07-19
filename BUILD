load(":rules.bzl", "clojure_repl", "clojure_toolchain")

package(default_visibility = ["//visibility:public"])
exports_files(glob(["deps.edn", "src/main/resources/**/*.clj"]))

toolchain_type(
    name = "toolchain_type",
    visibility = ["//visibility:public"])

clojure_toolchain(
    name = "default_toolchain")
