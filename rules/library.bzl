def clojure_library_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//rules:toolchain_type"]

    output = ctx.actions.declare_directory("%s.library" % ctx.label.name)

    cmd = """
        set -e;
        rm -rf {output}
        mkdir -p {output}
        {java} -cp {classpath} -Dclojure.compile.path={output} -Dclojure.compile.jar={jar} -Dclojure.compile.aot={aot} clojure.main {script} {sources}
    """.format(
        java = toolchain.java,
        classpath = ":".join([f.path for f in toolchain.files.runtime + ctx.files.deps + [output]]),
        output = output.path,
        jar = ctx.outputs.jar.path,
        aot = ",".join(ctx.attr.aots),
        script = [f for f in toolchain.files.scripts if f.basename == "library.clj"][0].path,
        sources = " ".join([f.path for f in ctx.files.srcs]),
    )

    ctx.actions.run_shell(
        command = cmd,
        outputs = [output, ctx.outputs.jar],
        inputs = ctx.files.srcs + ctx.files.deps + toolchain.files.runtime + toolchain.files.scripts + toolchain.files.jdk,
        mnemonic = "ClojureLibrary",
        progress_message = "Building clojure library for %s" % ctx.label,
    )

    return JavaInfo(
        output_jar = ctx.outputs.jar,
        compile_jar = ctx.outputs.jar,
        source_jar = ctx.outputs.jar,
        deps = [dep[JavaInfo] for dep in toolchain.runtime + ctx.attr.deps],
    )
