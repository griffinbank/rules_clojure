def _clojure_library_impl(ctx):
    output = ctx.actions.declare_directory("%s.library" % ctx.label.name)

    cmd = """
        set -e;
        rm -rf {output}
        mkdir -p {output}
        {java} -cp {classpath} -Dclojure.compile.path={output} -Dclojure.compile.jar={jar} -Dclojure.compile.aot={aot} clojure.main {script} {sources}
    """.format(
        java = ctx.attr._jdk[java_common.JavaRuntimeInfo].java_executable_exec_path,
        classpath = ":".join([f.path for f in ctx.files._runtime + ctx.files.deps + [output]]),
        output = output.path,
        jar = ctx.outputs.jar.path,
        aot = ",".join(ctx.attr.aots),
        script = [f for f in ctx.files._scripts if f.basename == "library.clj"][0].path,
        sources = " ".join([f.path for f in ctx.files.srcs]),
    )

    ctx.actions.run_shell(
        command = cmd,
        outputs = [output, ctx.outputs.jar],
        inputs = ctx.files.srcs + ctx.files.deps + ctx.files._runtime + ctx.files._scripts + ctx.files._jdk,
        mnemonic = "ClojureLibrary",
        progress_message = "Building clojure library for %s" % ctx.label,
    )

    return JavaInfo(
        output_jar = ctx.outputs.jar,
        compile_jar = ctx.outputs.jar,
        source_jar = ctx.outputs.jar,
        deps = [dep[JavaInfo] for dep in ctx.attr._runtime + ctx.attr.deps],
    )

clojure_library = rule(
    implementation = _clojure_library_impl,
    attrs = {
        "srcs": attr.label_list(default = [], allow_files = [".clj"]),
        "deps": attr.label_list(default = [], providers = [JavaInfo]),
        "aots": attr.string_list(default = []),
        "_runtime": attr.label_list(default = [
            "@org_clojure//jar",
            "@org_clojure_spec_alpha//jar",
            "@org_clojure_core_specs_alpha//jar",
        ]),
        "_scripts": attr.label(
            default = "//scripts",
        ),
        "_jdk": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            providers = [java_common.JavaRuntimeInfo],
        ),
    },
    outputs = {
        "jar": "%{name}.jar",
    },
    provides = [JavaInfo]
)
