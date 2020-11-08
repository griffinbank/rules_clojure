def clojure_binary_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    ctx.actions.write(
        output = ctx.outputs.executable,
        content = "{java} -cp {classpath} clojure.main -m {main} $@".format(
            java = toolchain.java_runfiles,
            classpath = ":".join([f.short_path for f in toolchain.files.runtime + ctx.files.deps]),
            main = ctx.attr.main,
        ),
    )

    return DefaultInfo(
        runfiles = ctx.runfiles(files = ctx.files.deps + toolchain.files.runtime + toolchain.files.scripts + toolchain.files.jdk),
    )
