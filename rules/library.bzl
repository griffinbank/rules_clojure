def _clojure_library_impl(ctx):
    output = ctx.actions.declare_directory("%s-output" % ctx.label.name)
    zipargs = ctx.actions.declare_file("%s-zipargs" % ctx.label.name)
    manifest = ctx.actions.declare_file("%s-MANIFEST.MF" % ctx.label.name)

    cmd = """
        set -e;
        rm -rf {output}
        mkdir -p {output}
        {java} -cp {classpath} -Dclojure.compile.path={output} -Dclojure.compile.aot={aot} clojure.main {compiler} {sources}
        echo \"Manifest-Version: 1.0\" > {manifest}
        echo \"META-INF/MANIFEST.MF={manifest}\" > {zipargs}
        find {output} -name '*.*' | awk '{{print $1\"=\"$1}}' | sed 's:^{output}/::' >> {zipargs}
        {zip} c {jarout} @{zipargs}
    """.format(
        output = output.path,
        java = ctx.attr._jdk[java_common.JavaRuntimeInfo].java_executable_exec_path,
        classpath = ":".join([f.path for f in ctx.files._clojure + ctx.files.deps + [output]]),
        aot = ",".join(ctx.attr.aot),
        compiler = ctx.file._compile.path,
        sources = " ".join([f.path for f in ctx.files.srcs]),
        manifest = manifest.path,
        zipargs = zipargs.path,
        zip = ctx.executable._zipper.path,
        jarout = ctx.outputs.jar.path
    )

    ctx.actions.run_shell(
        command = cmd,
        outputs = [output, zipargs, manifest, ctx.outputs.jar],
        inputs = ctx.files.srcs + ctx.files.deps + ctx.files._clojure + ctx.files._compile + ctx.files._jdk,
        tools = [ctx.executable._zipper],
        mnemonic = "ClojureLibrary",
        progress_message = "Building clojure library for %s" % ctx.label,
    )

    return JavaInfo(
        output_jar = ctx.outputs.jar,
        compile_jar = ctx.outputs.jar,
        source_jar = ctx.outputs.jar,
        deps = [dep[JavaInfo] for dep in ctx.attr._clojure + ctx.attr.deps],
    )

clojure_library = rule(
    _clojure_library_impl,
    attrs = {
        "srcs": attr.label_list(default = [], allow_files = [".clj"]),
        "deps": attr.label_list(default = [], providers = [JavaInfo]),
        "aot": attr.string_list(default = []),
        "_clojure": attr.label_list(default = [
            "@org_clojure//jar",
            "@org_clojure_spec_alpha//jar",
            "@org_clojure_core_specs_alpha//jar",
        ]),
        "_compile": attr.label(
            default = "//rules:compile.clj",
            allow_single_file = True,
        ),
        "_jdk": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            providers = [java_common.JavaRuntimeInfo],
        ),
        "_zipper": attr.label(
            default = "@bazel_tools//tools/zip:zipper",
            executable = True,
            single_file = True,
            cfg = "host",
        ),
    },
    outputs = {
        "jar": "lib%{name}.jar",
    },
)
