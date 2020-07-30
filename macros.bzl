load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_clojure//:rules.bzl", _library = "clojure_library", _repl = "clojure_repl")

def clojure_binary(name, srcs, aots, main_class, deps = []):
    lib = "_lib_%" % name

    _library(
        name = lib,
        srcs = srcs,
        aots = aots,
        deps = deps,
    )
    java_binary(
        name = name,
        main_class = main_class,
        runtime_deps = [lib],
    )

def clojure_repl(name, srcs, ns, deps = []):
    lib = "_lib_%" % name

    _library(
        name = lib,
        srcs = srcs,
        deps = deps,
    )

    _repl(
        name = name,
        deps = [lib],
        ns = ns,
    )
