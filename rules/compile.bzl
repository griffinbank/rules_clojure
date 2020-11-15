def clojure_java_library_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    output = ctx.actions.declare_directory("%s.classes" % ctx.label.name)
    jar = ctx.actions.declare_file("%s.jar" % ctx.label.name)

    deps = depset(
        direct = toolchain.files.runtime,
        transitive = [dep[JavaInfo].transitive_runtime_deps for dep in ctx.attr.deps],
    )

    cmd = """
        set -e;
        rm -rf {output}
        mkdir -p {output}
        {java} -cp {classpath} -Dclojure.compile.path={output} -Dclojure.compile.jar={jar} -Dclojure.compile.aot={aot} clojure.main {script}
    """.format(
        java = toolchain.java,
        classpath = ":".join([f.path for f in deps.to_list() + [output]]),
        output = output.path,
        jar = jar.path,
        aot = ",".join(ctx.attr.namespaces),
        script = toolchain.scripts["compile.clj"].path,
    )

    ctx.actions.run_shell(
        command = cmd,
        outputs = [output, jar],
        inputs = deps.to_list() + toolchain.files.scripts + toolchain.files.jdk,
        mnemonic = "ClojureJavaLibrary",
        progress_message = "Compiling %s" % ctx.label,
    )

    return [
        DefaultInfo(
            files = depset([jar]),
        ),
        JavaInfo(
            output_jar = jar,
            compile_jar = jar,
            source_jar = None,
            deps = [dep[JavaInfo] for dep in toolchain.runtime + ctx.attr.deps],
        ),
    ]
