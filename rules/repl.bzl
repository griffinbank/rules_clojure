def _clojure_repl_impl(ctx):
    toolchain = ctx.toolchains["//rules:toolchain_type"]

    ctx.actions.write(
        output = ctx.outputs.executable,
        content = "{java} -cp {classpath} clojure.main {args}".format(
            java = toolchain.java,
            classpath = ":".join([f.short_path for f in toolchain.files.runtime + ctx.files.deps]),
            args = " ".join(["-e", """\"(require '[{ns}]) (in-ns '{ns}) (clojure.main/repl)\"""".format(ns = ctx.attr.ns)] if ctx.attr.ns else []),
        ),
    )

    return DefaultInfo(
        runfiles = ctx.runfiles(files = ctx.files.deps + toolchain.files.runtime + toolchain.files.scripts + toolchain.files.jdk)
    )

clojure_repl = rule(
    implementation = _clojure_repl_impl,
    attrs = {
        "deps": attr.label_list(default = [], providers = [JavaInfo]),
        "ns": attr.string(mandatory = False),
    },
    toolchains = ["//rules:toolchain_type"],
    executable = True
)
