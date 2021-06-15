package(default_visibility = ["//visibility:public"])

exports_files(glob(["*.clj"]))

load("//:rules.bzl", "clojure_library", "clojure_binary")

clojure_library(
    name= "gen_build",
    resources= ["gen_build.clj"],
    runtime_deps=["@rules_clojure_maven//:org_clojure_clojure",
                  "@rules_clojure_maven//:org_clojure_tools_namespace",
                  "@rules_clojure_maven//:org_clojure_tools_deps_alpha",
                  "@rules_clojure_maven//:org_clojure_data_json"])

# clojure_library(name="jar",
#                 resources= ["jar.clj"],
#                 runtime_deps=["@rules_clojure_maven//:org_clojure_clojure",
#                               "@rules_clojure_maven//:org_clojure_tools_namespace"],
#                 aot="rules-clojure.jar")

clojure_library(name= "testrunner",
                resources= ["testrunner.clj"],
                aot=["rules_clojure.testrunner"])

## files needed for the clj toolchain
filegroup(name="toolchain_files",
          srcs= glob(["*.clj"]) + ["//:deps.edn"])
