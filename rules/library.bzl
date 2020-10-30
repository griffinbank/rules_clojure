def clojure_library_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    output = ctx.actions.declare_directory("%s.library" % ctx.label.name)
    jar = ctx.actions.declare_file("%s.jar" % ctx.label.name)

    transitive_runtime_deps = depset(transitive = [dep[JavaInfo].transitive_runtime_deps for dep in ctx.attr.deps])

    cmd = """
        set -e;
        rm -rf {output}
        mkdir -p {output}
        {java} -cp {classpath} -Dclojure.compile.path={output} -Dclojure.compile.jar={jar} -Dclojure.compile.aot={aot} clojure.main {script} {sources}
    """.format(
        java = toolchain.java,
        classpath = ":".join([f.path for f in toolchain.files.runtime + transitive_runtime_deps.to_list() + [output]]),
        output = output.path,
        jar = jar.path,
        aot = ",".join(ctx.attr.aots),
        script = toolchain.scripts["library.clj"].path,
        sources = " ".join([f.path for f in ctx.files.srcs]),
    )

    ctx.actions.run_shell(
        command = cmd,
        outputs = [output, jar],
        inputs = ctx.files.srcs + transitive_runtime_deps.to_list() + toolchain.files.runtime + toolchain.files.scripts + toolchain.files.jdk,
        mnemonic = "ClojureLibrary",
        progress_message = "Building clojure library for %s" % ctx.label,
    )

    return [
        DefaultInfo(
            files = depset([jar]),
            runfiles = ctx.runfiles([jar]),  # TODO: This is needed only for //tests:library_content_test because :library.jar is not available anymore
        ),
        JavaInfo(
            output_jar = jar,
            compile_jar = jar,
            source_jar = jar,
            deps = [dep[JavaInfo] for dep in toolchain.runtime + ctx.attr.deps],
        ),
    ]
