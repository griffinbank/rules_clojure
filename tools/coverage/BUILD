load("@rules_java//java:java_library.bzl", "java_library")

java_library(
    name = "jacoco_instrumenter",
    srcs = ["JacocoInstrumenter.java"],
    visibility = ["//visibility:public"],
    deps = [
        "@bazel_tools//tools/jdk:JacocoCoverage",
    ],
)
