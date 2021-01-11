load("@rules_clojure//rules:binary.bzl", _clojure_binary_impl = "clojure_binary_impl")
load("@rules_clojure//rules:jar.bzl", _clojure_jar_impl = "clojure_jar_impl")
load("@rules_clojure//rules:library.bzl", _clojure_library_impl = "clojure_library_impl")
load("@rules_clojure//rules:repl.bzl", _clojure_repl_impl = "clojure_repl_impl")

clojure_library = rule(
    doc = "Create a jar containing clojure sources. Optionally AOTs. The output jar will contain: all files included in srcs, and all compiled classes from AOTing. The output jar will depend on the transitive dependencies of all srcs & deps",
    attrs = {
        "aot": attr.string_list(default = [], allow_empty = True, doc = "Namespaces in classpath to compile."),
        "srcs": attr.label_list(mandatory = False, allow_empty = True, allow_files = True, default = [], doc = "a list of targets to include in the jar"),
        "resources": attr.label_list(allow_files = True, default = []),
        "deps": attr.label_list(default = [], doc = "runtime deps")
    },
    provides = [JavaInfo],
    toolchains = ["@rules_clojure//:toolchain"],
    implementation = _clojure_jar_impl,
)

def clojure_binary(name, srcs=[], **kwargs):
    native.java_binary(name=name,
                       **kwargs)

def clojure_repl(name, runtime_deps=[], ns=None, **kwargs):
    args = []
    if ns:
        args.extend(["-e", """\"(require '[{ns}]) (in-ns '{ns})\"""".format(ns = ns)])
    args.extend(["-e", "(clojure.main/repl)"])
    native.java_binary(name=name,
                       runtime_deps=runtime_deps,
                       main_class = "clojure.main",
                       args = args,
                       **kwargs)

def clojure_test(name, test_ns, srcs=[], deps=[], **kwargs):
    # todo: ideally the library name and the bin name would be the same.
    jarname = "_" + name
    clojure_library(name=jarname, srcs = srcs, deps = deps, testonly = True)
    native.java_test(name=name,
                     runtime_deps = [jarname],
                     use_testrunner = False,
                     main_class="clojure.main",
                     jvm_flags=["-Dclojure.main.report=stderr"],
                     args = ["-e \"(do (require 'clojure.test) (require '%s) (clojure.test/run-tests '%s))\"" % (test_ns, test_ns)],
                     **kwargs)
