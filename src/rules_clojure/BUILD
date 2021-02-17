package(default_visibility = ["//visibility:public"])

exports_files(glob(["*.clj"]))

load("//:rules.bzl", "clojure_library", "clojure_namespace")

clojure_library(
    name= "gen_build",
    srcs= {"gen_build.clj": "/rules_clojure/gen_build.clj"},
    deps=["@rules_clojure_maven//:org_clojure_clojure",
          "@rules_clojure_maven//:org_clojure_tools_namespace",
          "@rules_clojure_maven//:org_clojure_tools_deps_alpha"])

clojure_namespace(name="jar_ns",
                  srcs= {"jar.clj": "/rules_clojure/jar.clj"})

clojure_library(
    name="jar",
    srcs=["jar_ns"],
    deps=["@rules_clojure_maven//:org_clojure_clojure"],
    aot=["rules-clojure.jar"],
    compiledeps=[]
)

clojure_namespace(name= "testrunner_ns",
                  srcs = {"testrunner.clj": "/rules_clojure/testrunner.clj"})

clojure_library(name = "testrunner",
                srcs = ["testrunner_ns"],
                aot = ["rules-clojure.testrunner"])

## files needed for the clj toolchain
filegroup(name="toolchain_files",
          srcs= glob(["*.clj"]) + ["//:deps.edn"])
