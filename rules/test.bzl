def clojure_test_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//rules:toolchain_type"]

    transitive_runtime_deps = depset(transitive = [dep[JavaInfo].transitive_runtime_deps for dep in ctx.attr.deps])

    ctx.actions.write(
        output = ctx.outputs.executable,
        content = "{java} -cp {classpath} clojure.main {script} {sources}".format(
            java = toolchain.java_runfiles,
            classpath = ":".join([f.short_path for f in toolchain.files.runtime + ctx.files.deps + transitive_runtime_deps.to_list()]),
            script = toolchain.scripts["test.clj"].path,
            sources = " ".join([f.path for f in ctx.files.srcs]),
        ),
    )

    return DefaultInfo(
        runfiles = ctx.runfiles(
            files = ctx.files.srcs + ctx.files.deps + toolchain.files.runtime + toolchain.files.scripts + toolchain.files.jdk,
            transitive_files = transitive_runtime_deps,
        ),
    )
