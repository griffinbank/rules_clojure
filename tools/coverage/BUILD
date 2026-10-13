load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_java//java:java_test.bzl", "java_test")

java_library(
    name = "jacoco_instrumenter",
    srcs = ["JacocoInstrumenter.java"],
    visibility = ["//visibility:public"],
    deps = [
        "@bazel_tools//tools/jdk:JacocoCoverage",
    ],
)

java_test(
    name = "jacoco_instrumenter_test",
    size = "small",
    srcs = ["JacocoInstrumenterTest.java"],
    test_class = "rules_clojure.coverage.JacocoInstrumenterTest",
    deps = [
        ":jacoco_instrumenter",
        "@maven_test_deps//:junit_junit",
        "@maven_test_deps//:org_ow2_asm_asm",
    ],
)
