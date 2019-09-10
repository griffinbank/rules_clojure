def clojure_test_impl(ctx):
    toolchain = ctx.toolchains["//rules:toolchain_type"]

    ctx.actions.write(
        output = ctx.outputs.executable,
        content = "{java} -cp {classpath} clojure.main {script} {sources}".format(
            java = toolchain.java,
            classpath = ":".join([f.short_path for f in toolchain.files.runtime + ctx.files.deps]),
            script = [f for f in toolchain.files.scripts if f.basename == "test.clj"][0].path,
            sources = " ".join([f.path for f in ctx.files.srcs]),
        ),
    )

    return DefaultInfo(
        runfiles = ctx.runfiles(files = ctx.files.srcs + ctx.files.deps + toolchain.files.runtime + toolchain.files.scripts + toolchain.files.jdk)
    )
