load(":toolchains.bzl", "clojure_toolchain")
load(":rules.bzl", "clojure_repl")

package(default_visibility = ["//visibility:public"])
exports_files(glob(["deps.edn", "src/main/resources/**/*.clj"]))

toolchain_type(
    name = "toolchain_type",
    visibility = ["//visibility:public"])

clojure_toolchain(
    name = "default_toolchain")

java_binary(name="repl",
            main_class="clojure.main",
            args=["-r"],
            runtime_deps=["//src/rules_clojure:worker-lib",
                          "//test/rules_clojure:worker"])
