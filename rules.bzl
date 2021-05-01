load("//rules:jar.bzl", _clojure_jar_impl = "clojure_jar_impl")
load("//rules:namespace.bzl", _clojure_ns_impl = "clojure_ns_impl")

def _clojure_path_impl(ctx):
    return [DefaultInfo(files = depset([]))]

clojure_library = rule(
    doc = "Define a clojure library",
    attrs = {
        "srcs": attr.label_keyed_string_dict(mandatory = False, doc = "a map of the .clj{,c,s} source files to their destination on the classpath", allow_files = True, default = {}),
        "deps": attr.label_list(default = [], providers = [[JavaInfo]]),
        "aot": attr.string_list(default = [], doc = "namespaces to be compiled"),
        "compiledeps": attr.label_list(default = ["@rules_clojure//src/rules_clojure:jar"]),
        "javacopts": attr.string_list(default = [], allow_empty = True, doc = "Optional javac compiler options"),
    },
    provides = [JavaInfo],
    toolchains = ["@rules_clojure//:toolchain"],
    implementation = _clojure_jar_impl,
)

def clojure_binary(name, **kwargs):
    deps = []
    runtime_deps = []
    if "deps" in kwargs:
        deps = kwargs["deps"]
        kwargs.pop("deps")

    if "runtime_deps" in kwargs:
        runtime_deps = kwargs["runtime_deps"]
        kwargs.pop("runtime_deps")

    native.java_binary(name=name,
                       runtime_deps = deps + runtime_deps,
                       **kwargs)

def clojure_repl(name, deps=[], ns=None, **kwargs):
    args = []

    if ns:
        args.extend(["-e", """\"(require '[{ns}]) (in-ns '{ns})\"""".format(ns = ns)])

    args.extend(["-e", "(clojure.main/repl)"])

    native.java_binary(name=name,
                       runtime_deps=deps,
                       jvm_flags=["-Dclojure.main.report=stderr"],
                       main_class = "clojure.main",
                       args = args,
                       **kwargs)

def clojure_test(name, *, test_ns, deps=[], **kwargs):
    # ideally the library name and the bin name would be the same. They can't be.
    # clojure src files would like to depend on `foo_test`, so mangle the test binary, not the src jar name

    native.java_test(name=name,
                     runtime_deps = deps + ["@rules_clojure//src/rules_clojure:testrunner"],
                     use_testrunner = False,
                     main_class="clojure.main",
                     jvm_flags=["-Dclojure.main.report=stderr"],
                     args = ["-m", "rules-clojure.testrunner", test_ns],
                     **kwargs)
