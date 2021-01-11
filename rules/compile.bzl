def clojure_java_library_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    classes = ctx.actions.declare_directory("%s.classes" % ctx.label.name)
    jar = ctx.actions.declare_file("%s.jar" % ctx.label.name)

    deps = depset(
        direct = toolchain.files.runtime,
        transitive = [dep[JavaInfo].transitive_runtime_deps for dep in ctx.attr.deps],
    )

    cmd = """
        set -e;
        rm -rf {classes}
        mkdir -p {classes}
        {java} -cp {classpath} -Dclojure.compile.path={classes} -Dclojure.compile.jar={jar} clojure.main {script} {namespaces}
    """.format(
        java = toolchain.java,
        classpath = ":".join([f.path for f in deps.to_list() + [classes]]),
        classes = classes.path,
        jar = jar.path,
        script = toolchain.scripts["compile.clj"].path,
        namespaces = " ".join(ctx.attr.namespaces),
    )

    ctx.actions.run_shell(
        outputs = [jar]
        command = cmd,
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
