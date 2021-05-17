package(default_visibility = ["//visibility:public"])

exports_files(glob(["*.clj"]))

load("//:rules.bzl", "clojure_library")

clojure_library(
    name= "gen_build",
    srcs= {"gen_build.clj": "/rules_clojure/gen_build.clj"},
    deps=["@rules_clojure_maven//:org_clojure_clojure",
          "@rules_clojure_maven//:org_clojure_tools_namespace",
          "@rules_clojure_maven//:org_clojure_tools_deps_alpha"])

clojure_library(name="jar",
                srcs= {"jar.clj": "/rules_clojure/jar.clj"},
                deps=["@rules_clojure_maven//:org_clojure_clojure",
                      "@rules_clojure_maven//:org_clojure_tools_namespace"],
                compiledeps=[], # prevent it from depending on itself
                aot=["rules-clojure.jar"])

clojure_library(name= "testrunner",
                srcs = {"testrunner.clj": "/rules_clojure/testrunner.clj"},
                aot = ["rules-clojure.testrunner"])

## files needed for the clj toolchain
filegroup(name="toolchain_files",
          srcs= glob(["*.clj"]) + ["//:deps.edn"])
