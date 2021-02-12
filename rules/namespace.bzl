load("@rules_clojure//rules:common.bzl", "CljInfo")

def clojure_ns_impl(ctx):
    runfiles = ctx.runfiles()

    clj_srcs = []
    java_deps = []

    for dep in ctx.attr.srcs.keys() + ctx.attr.deps:
        if DefaultInfo in dep:
            runfiles = runfiles.merge(dep[DefaultInfo].default_runfiles)
            runfiles = runfiles.merge(dep[DefaultInfo].data_runfiles)
        if CljInfo in dep:
            clj_srcs.append(dep[CljInfo])
        if JavaInfo in dep:
            java_deps.append(dep[JavaInfo])

    transitive_clj_srcs = {}
    transitive_clj_srcs.update(ctx.attr.srcs)

    transitive_java_deps = java_deps

    for d in clj_srcs:
        transitive_clj_srcs.update(d.transitive_clj_srcs)
        transitive_java_deps.extend(d.transitive_java_deps)

    return [
        DefaultInfo(
            files = depset(ctx.files.srcs),
            runfiles = runfiles,
        ),
        CljInfo(srcs = ctx.attr.srcs,
                transitive_clj_srcs = transitive_clj_srcs,
                transitive_java_deps = transitive_java_deps,
                deps = ctx.attr.deps)]
