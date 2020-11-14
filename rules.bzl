load("@rules_clojure//rules:binary.bzl", _clojure_binary_impl = "clojure_binary_impl")
load("@rules_clojure//rules:compile.bzl", _clojure_java_library_impl = "clojure_java_library_impl")
load("@rules_clojure//rules:library.bzl", _clojure_library_impl = "clojure_library_impl")
load("@rules_clojure//rules:repl.bzl", _clojure_repl_impl = "clojure_repl_impl")
load("@rules_clojure//rules:test.bzl", _clojure_test_impl = "clojure_test_impl")

clojure_binary = rule(
    doc = "Builds a wrapper shell script with the same name as the rule.",
    attrs = {
        "main": attr.string(mandatory = True, doc = "A namespace to find a -main function for execution."),
        "deps": attr.label_list(mandatory = True, allow_empty = False, providers = [JavaInfo], doc = "Libraries to link into this binary."),
    },
    executable = True,
    toolchains = ["@rules_clojure//:toolchain"],
    implementation = _clojure_binary_impl,
)

clojure_java_library = rule(
    doc = "Compiles given namespaces to java.",
    attrs = {
        "namespaces": attr.string_list(mandatory = True, allow_empty = False, doc = "Namespaces in classpath to compile."),
        "deps": attr.label_list(mandatory = True, allow_empty = False, providers = [JavaInfo], doc = "Dependencies to compile."),
    },
    provides = [JavaInfo],
    toolchains = ["@rules_clojure//:toolchain"],
    implementation = _clojure_java_library_impl,
)

clojure_library = rule(
    doc = "Builds a jar file from given sources with the paths corresponding to namespaces.",
    attrs = {
        "srcs": attr.label_list(mandatory = True, allow_empty = False, allow_files = [".clj"], doc = "clj source files."),
        "deps": attr.label_list(default = [], providers = [JavaInfo], doc = "Libraries to link into this library."),
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
        "srcs": attr.label_list(mandatory = True, allow_empty = False, allow_files = [".clj"], doc = "clj source files with test cases."),
        "deps": attr.label_list(default = [], providers = [JavaInfo], doc = "Libraries to link into this library."),
    },
    test = True,
    toolchains = ["@rules_clojure//:toolchain"],
    implementation = _clojure_test_impl,
)
