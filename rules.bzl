load("@rules_clojure//rules:binary.bzl", _clojure_binary_impl = "clojure_binary_impl")
load("@rules_clojure//rules:jar.bzl", _clojure_jar_impl = "clojure_jar_impl")
load("@rules_clojure//rules:library.bzl", _clojure_library_impl = "clojure_library_impl")
load("@rules_clojure//rules:repl.bzl", _clojure_repl_impl = "clojure_repl_impl")

_clojure_library = rule(
    doc = "Create a jar containing clojure sources. Optionally AOTs. The output jar will contain: all files included in srcs, and all compiled classes from AOTing. The output jar will depend on the transitive dependencies of all srcs & deps",
    attrs = {
        "aot": attr.string_list(default = [], allow_empty = True, doc = "Namespaces in classpath to compile."),
        "srcs": attr.label_list(mandatory = False, allow_empty = True, allow_files = True, default = [], doc = "a list of targets to include in the jar"),
        "resources": attr.label_list(allow_files = True, default = []),
        "deps": attr.label_list(default = [], doc = "deps")
    },
    provides = [JavaInfo],
    toolchains = ["@rules_clojure//:toolchain"],
    implementation = _clojure_jar_impl,
)

def clojure_library(name, srcs = [], aot = [], resources=[], deps=[], **kwargs):
    testonly = False
    if "testonly" in kwargs:
        testonly = kwargs["testonly"]
        kwargs.pop("testonly")

    _clojure_library(name = name + ".cljsrc.jar",
                     srcs = srcs,
                     deps = deps,
                     resources = resources,
                     aot = aot,
                     testonly = testonly,
                     **kwargs)

    ## clojure libraries which have native library dependencies (eg libsodium) can't be
    ## defined via skylark rules, because the required provider,
    ## JavaNativeLibraryInfo, isn't constructable via
    ## skylark. Therefore, create a `java_library` that we can pass
    ## deps into
    native.java_library(name = name,
                        runtime_deps = deps + [":" + name + ".cljsrc.jar"],
                        testonly = testonly)

def clojure_binary(name, **kwargs):
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
    # todo: ideally the library name and the bin name would be the same. They can't be.
    # clojure src files would like to depend on `foo_test`, so mangle the test binary, not the src jar name
    jarname = name

    clojure_library(name=jarname, srcs = srcs, deps = deps, testonly = True)
    native.java_test(name=name + ".test",
                     runtime_deps = [jarname],
                     use_testrunner = False,
                     main_class="clojure.main",
                     jvm_flags=["-Dclojure.main.report=stderr"],
                     args = ["-e \"(do (require 'clojure.test) (require '%s) (clojure.test/run-tests '%s) (shutdown-agents))\"" % (test_ns, test_ns)],
                     **kwargs)
