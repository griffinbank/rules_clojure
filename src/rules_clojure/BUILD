package(default_visibility = ["//visibility:public"])

exports_files(glob(["*.clj"]))

load("//:rules.bzl", "clojure_library", "clojure_binary")

clojure_library(
    name= "gen_build",
    srcs=["gen_build.clj"],
    resource_strip_prefix = "src",
    deps=["@rules_clojure_maven//:org_clojure_clojure",
                  "@rules_clojure_maven//:org_clojure_tools_namespace",
                  "@rules_clojure_maven//:org_clojure_tools_deps_alpha",
                  "@rules_clojure_maven//:org_clojure_data_json"],
    aot=["rules-clojure.gen-build"])

java_library(
    name="jar-lib",
    resources=["jar.clj"],
    resource_strip_prefix="src",
    runtime_deps=["@rules_clojure_maven//:org_clojure_clojure",
                  "@rules_clojure_maven//:org_clojure_spec_alpha",
                  "@rules_clojure_maven//:org_clojure_core_specs_alpha",
                  "@rules_clojure_maven//:org_clojure_tools_namespace",
                  "@rules_clojure_maven//:org_clojure_java_classpath",
                  "@rules_clojure_maven//:org_clojure_data_json"])

java_binary(
    name="worker",
    resources=["worker.clj"],
    resource_strip_prefix="src",
    main_class="clojure.main",
    jvm_flags=["-Dclojure.main.report=stderr"],
)

clojure_library(name= "testrunner",
                srcs=["testrunner.clj"],
                resource_strip_prefix="src",
                aot=["rules-clojure.testrunner"])

## files needed for the clj toolchain
filegroup(name="toolchain_files",
          srcs= glob(["*.clj"]) + ["//:deps.edn"])
