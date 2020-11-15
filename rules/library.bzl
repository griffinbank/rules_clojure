def clojure_library_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    jar = ctx.actions.declare_file("%s.jar" % ctx.label.name)

    cmd = "{java} -cp {classpath} -Dclojure.compile.jar={jar} clojure.main {script} {sources}".format(
        java = toolchain.java,
        classpath = ":".join([f.path for f in toolchain.files.runtime]),
        jar = jar.path,
        script = toolchain.scripts["library.clj"].path,
        sources = " ".join([f.path for f in ctx.files.srcs]),
    )

    ctx.actions.run_shell(
        command = cmd,
        outputs = [jar],
        inputs = ctx.files.srcs + toolchain.files.runtime + toolchain.files.scripts + toolchain.files.jdk,
        mnemonic = "ClojureLibrary",
        progress_message = "Building clojure library for %s" % ctx.label,
    )

    return [
        DefaultInfo(
            files = depset([jar]),
        ),
        JavaInfo(
            output_jar = jar,
            compile_jar = jar,
            source_jar = jar,
            deps = [dep[JavaInfo] for dep in ctx.attr.deps],
        ),
    ]
