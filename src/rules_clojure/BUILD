package(default_visibility = ["//visibility:public"])

exports_files(glob(["*.clj"]))

load("//:rules.bzl", "clojure_namespace", "clojure_library")

clojure_namespace(
    name= "gen_build",
    srcs= {"gen_build.clj": "/rules_clojure/gen_build.clj"},
    deps=["@rules_clojure_maven//:org_clojure_clojure",
          "@rules_clojure_maven//:org_clojure_tools_namespace",
          "@rules_clojure_maven//:org_clojure_tools_deps_alpha"])

clojure_library(name="gen_build_jar",
                srcs=[":gen_build"])

clojure_namespace(
    name="jar",
    srcs= {"jar.clj": "/rules_clojure/jar.clj"},
    deps=["@rules_clojure_maven//:org_clojure_clojure"])

clojure_namespace(name = "testrunner",
                  srcs = {"testrunner.clj": "/rules_clojure/testrunner.clj"})

clojure_library(name = "testrunner_jar",
                srcs = ["testrunner"])

## files needed for the clj toolchain
filegroup(name="toolchain_files",
          srcs= glob(["*.clj"]) + ["//:deps.edn"])
