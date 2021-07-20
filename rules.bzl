load("//rules:jar.bzl", _clojure_jar_impl = "clojure_jar_impl")

clojure_library = rule(
    doc = "Define a clojure library",
    attrs = {
        "srcs": attr.label_list(default = [], allow_files = True),
        "deps": attr.label_list(default = [], providers = [[JavaInfo]]),
        "runtime_deps": attr.label_list(default = [], providers = [[JavaInfo]]),
        "data": attr.label_list(default = [], allow_files = True),
        "resources": attr.label_list(default=[], allow_files=True),
        "aot": attr.string_list(default = [], doc = "namespaces to be compiled"),
        "resource_strip_prefix": attr.string(default = ""),
        "compiledeps": attr.label_list(default = []),
        "javacopts": attr.string_list(default = [], allow_empty = True, doc = "Optional javac compiler options"),
        "worker": attr.label(default=Label("@rules_clojure//java/rules_clojure:ClojureWorker"), executable = True, cfg="host"),
        ## shimdandy-impl and anything that would pull in Clojure
        ## are not allowed to be on the startup classpath of
        ## ClojureWorker, so build these separately and pull them
        ## in at runtime
        "_worker_runtime": attr.label_list(default=[Label("@rules_clojure_maven//:org_projectodd_shimdandy_shimdandy_impl"),
                                                    Label("@rules_clojure//src/rules_clojure:jar-lib")], cfg="host")
    },
    provides = [JavaInfo],
    toolchains = ["@rules_clojure//:toolchain_type"],
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

def clojure_test(name, *, test_ns, deps=[], runtime_deps=[], **kwargs):
    # ideally the library name and the bin name would be the same. They can't be.
    # clojure src files would like to depend on `foo_test`, so mangle the test binary, not the src jar name

    native.java_test(name=name,
                     runtime_deps = deps + runtime_deps + ["@rules_clojure//src/rules_clojure:testrunner"],
                     use_testrunner = False,
                     main_class="clojure.main",
                     jvm_flags=["-Dclojure.main.report=stderr"],
                     args = ["-m", "rules-clojure.testrunner", test_ns],
                     **kwargs)
