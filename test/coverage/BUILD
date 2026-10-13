load("//:rules.bzl", "clojure_library", "clojure_test")

package(default_visibility = ["//visibility:public"])

clojure_library(
    name = "foo_core",
    srcs = ["foo/core.clj"],
    aot = ["coverage.foo.core"],
    resource_strip_prefix = "test",
    deps = ["@maven_deps//:org_clojure_clojure"],
)

clojure_library(
    name = "bar_core",
    srcs = ["bar/core.clj"],
    aot = ["coverage.bar.core"],
    resource_strip_prefix = "test",
    deps = ["@maven_deps//:org_clojure_clojure"],
)

clojure_library(
    name = "coverage_test_lib",
    resources = ["coverage_test.clj"],
    resource_strip_prefix = "test",
    deps = [
        ":foo_core",
        ":bar_core",
        "@maven_deps//:org_clojure_clojure",
    ],
)

clojure_test(
    name = "coverage_test",
    test_ns = "coverage.coverage-test",
    deps = [":coverage_test_lib"],
)

clojure_library(
    name = "jacoco_instrumenter_test_lib",
    resources = ["jacoco_instrumenter_test.clj"],
    resource_strip_prefix = "test",
    deps = [
        "//src/rules_clojure:libworker",
        "//test/rules_clojure:test-utils",
        "//tools/coverage:jacoco_instrumenter",
        "@maven_deps//:org_clojure_clojure",
    ],
)

clojure_test(
    name = "jacoco_instrumenter_test",
    data = [
        ":foo_core",
        "//test/rules_clojure:clojure",
        "@bazel_tools//tools/jdk:JacocoCoverage",
    ],
    env = {
        "CLOJURE_JARS": "$(rlocationpaths //test/rules_clojure:clojure)",
        "FOO_CORE_JAR": "$(rlocationpaths :foo_core)",
        "JACOCO_JARS": "$(rlocationpaths @bazel_tools//tools/jdk:JacocoCoverage)",
    },
    test_ns = "coverage.jacoco-instrumenter-test",
    deps = [":jacoco_instrumenter_test_lib"],
)
