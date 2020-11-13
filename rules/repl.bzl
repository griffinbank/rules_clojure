def clojure_repl_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    deps = depset(
        direct = toolchain.files.runtime,
        transitive = [dep[JavaInfo].transitive_runtime_deps for dep in ctx.attr.deps],
    )

    ctx.actions.write(
        output = ctx.outputs.executable,
        content = "{java} -cp {classpath} clojure.main {args}".format(
            java = toolchain.java_runfiles,
            classpath = ":".join([f.short_path for f in deps.to_list()]),
            args = " ".join(["-e", """\"(require '[{ns}]) (in-ns '{ns}) (clojure.main/repl)\"""".format(ns = ctx.attr.ns)] if ctx.attr.ns else []),
        ),
    )

    return DefaultInfo(
        runfiles = ctx.runfiles(
            files = toolchain.files.scripts + toolchain.files.jdk,
            transitive_files = deps,
        ),
    )
