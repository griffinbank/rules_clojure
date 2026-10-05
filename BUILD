load(":rules.bzl", "clojure_repl")
load("@rules_java//java:defs.bzl", "java_binary")

package(default_visibility = ["//visibility:public"])

exports_files(["deps.edn", "rules.bzl"])

java_binary(name="repl",
            main_class="clojure.main",
            args=["-r"],
            runtime_deps=["//src/rules_clojure:libworker",
                          "//test/rules_clojure:test-deps"])
