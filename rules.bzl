load("//rules:library.bzl", _clojure_library_impl = "clojure_library_impl")
load("//rules:repl.bzl", _clojure_repl_impl = "clojure_repl_impl")
load("//rules:test.bzl", _clojure_test_impl = "clojure_test_impl")

clojure_library = rule(
    attrs = {
        "srcs": attr.label_list(default = [], allow_files = [".clj"]),
        "deps": attr.label_list(default = [], providers = [JavaInfo]),
        "aots": attr.string_list(default = []),
    },
    outputs = {
        "jar": "%{name}.jar",
    },
    provides = [JavaInfo],
    toolchains = ["//rules:toolchain_type"],
    implementation = _clojure_library_impl
)

clojure_repl = rule(
    attrs = {
        "deps": attr.label_list(default = [], providers = [JavaInfo]),
        "ns": attr.string(mandatory = False),
    },
    executable = True,
    toolchains = ["//rules:toolchain_type"],
    implementation = _clojure_repl_impl
)

clojure_test = rule(
    attrs = {
        "srcs": attr.label_list(default = [], allow_files = [".clj"]),
        "deps": attr.label_list(default = [], providers = [JavaInfo]),
    },
    test = True,
    toolchains = ["//rules:toolchain_type"],
    implementation = _clojure_test_impl
)
