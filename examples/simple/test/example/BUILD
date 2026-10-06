load("@rules_clojure//:rules.bzl", "clojure_library", "clojure_test")
load("@rules_java//java:defs.bzl", "java_library")

java_library(name="libcore_test",
                resources=["core_test.clj"],
                resource_strip_prefix="test",
                runtime_deps=["//src/example:core"])

clojure_test(name="core_test",
             test_ns="example.core-test",
             resource_strip_prefix="test",
             runtime_deps=[":libcore_test"])
