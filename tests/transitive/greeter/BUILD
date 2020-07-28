load("@rules_clojure//:rules.bzl", "clojure_library")

java_library(
    name = "greeter_java",
    srcs = ["HelloJava.java"],
)

clojure_library(
    name = "greeter",
    srcs = ["hello.clj"],
    visibility = ["//visibility:public"],
    deps = [":greeter_java"],
)
