package(default_visibility = ["//visibility:public"])

exports_files(glob(["*.clj"]))

load("//:rules.bzl", "clojure_namespace")

clojure_namespace(
    name= "gen_build",
    srcs= {"gen_build.clj": "/rules_clojure/gen_build.clj"},
    deps=["@rules_clojure_maven//:org_clojure_clojure",
          "@rules_clojure_maven//:org_clojure_tools_namespace",
          "@rules_clojure_maven//:org_clojure_tools_deps_alpha"])

clojure_namespace(
    name="jar",
    srcs= {"jar.clj": "/rules_clojure/jar.clj"},
    deps=["@rules_clojure_maven//:org_clojure_clojure"])

## files needed for the clj toolchain
filegroup(name="jar_files",
          srcs= ["jar.clj"])
