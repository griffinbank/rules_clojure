load(":rules.bzl", "clojure_repl")
# Bazel 9 stubs the native java_* rules with a reduced attribute set
# (no main_class/runtime_deps/jvm_flags/resources); they must come from rules_java.
load("@rules_java//java:defs.bzl", "java_binary")

package(default_visibility = ["//visibility:public"])

exports_files(["deps.edn"])

java_binary(name="repl",
            main_class="clojure.main",
            args=["-r"],
            runtime_deps=["//src/rules_clojure:libworker",
                          "//test/rules_clojure:test-deps"])