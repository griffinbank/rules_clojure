load("@rules_clojure//rules:library.bzl", _clojure_library_impl = "clojure_library_impl")
load("@rules_clojure//rules:repl.bzl", _clojure_repl_impl = "clojure_repl_impl")
load("@rules_clojure//rules:test.bzl", _clojure_test_impl = "clojure_test_impl")

clojure_library = rule(
    doc = "Builds a jar for given sources with ahead-of-time compilation.",
    attrs = {
        "srcs": attr.label_list(default = [], allow_files = [".clj"], doc = "clj source files."),
        "deps": attr.label_list(default = [], providers = [JavaInfo], doc = "Libraries to link into this library."),
        "aots": attr.string_list(default = [], doc = "Namespaces to be compiled."),
    },
    provides = [JavaInfo],
    toolchains = ["@rules_clojure//:toolchain"],
    implementation = _clojure_library_impl,
)

clojure_repl = rule(
    doc = "Runs REPL with given dependencies in classpath.",
    attrs = {
        "deps": attr.label_list(default = [], providers = [JavaInfo], doc = "Libraries available in REPL."),
        "ns": attr.string(mandatory = False, doc = "Namespace to start REPL in."),
    },
    executable = True,
    toolchains = ["@rules_clojure//:toolchain"],
    implementation = _clojure_repl_impl,
)

clojure_test = rule(
    doc = "Runs clojure.test for given sources.",
    attrs = {
        "srcs": attr.label_list(default = [], allow_files = [".clj"], doc = "clj source files with test cases."),
        "deps": attr.label_list(default = [], providers = [JavaInfo], doc = "Libraries to link into this library."),
    },
    test = True,
    toolchains = ["@rules_clojure//:toolchain"],
    implementation = _clojure_test_impl,
)
