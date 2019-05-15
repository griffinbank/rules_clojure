
def clojure_repositories():
    native.maven_jar(
        name = "org_clojure",
        artifact = "org.clojure:clojure:1.10.0",
    )

    native.maven_jar(
        name = "org_clojure_spec_alpha",
        artifact = "org.clojure:spec.alpha:0.2.176",
    )

    native.maven_jar(
        name = "org_clojure_core_specs_alpha",
        artifact = "org.clojure:core.specs.alpha:0.2.44",
    )

def _clojure_library_impl(ctx):
    output = ctx.actions.declare_directory("%s-out" % ctx.label.name)
    zipout = ctx.actions.declare_file("%s-zip" % ctx.label.name)
    manifest = ctx.actions.declare_file("MANIFEST.MF")

    cmd = "set -e;\n"
    cmd += "rm -rf %s\n" % output.path
    cmd += "mkdir -p %s\n" % output.path

    java = ctx.attr._jdk[java_common.JavaRuntimeInfo].java_executable_exec_path
    classpath = ":".join([f.path for f in ctx.files._clojure + ctx.files.deps + [output]])
    sources = " ".join([f.path for f in ctx.files.srcs])
    cmd += """%s -cp %s -Dclojure.compile.path=%s -Dclojure.compile.aot=%s clojure.main %s %s\n""" % (
        java,
        classpath,
        output.path,
        ",".join(ctx.attr.aot),
        ctx.file._compile.path,
        sources
    )

    cmd += "echo \"Manifest-Version: 1.0\" > %s\n" % (manifest.path)
    cmd += "echo \"META-INF/MANIFEST.MF=%s\" > %s\n" % (manifest.path, zipout.path)
    cmd += "find %s -name '*.*' | awk '{print $1\"=\"$1}' | sed 's:^%s/::' >> %s\n" % (output.path, output.path, zipout.path)
    cmd += "%s c %s @%s" % (ctx.executable._zipper.path, ctx.outputs.jar.path, zipout.path)

    ctx.actions.run_shell(
        command = cmd,
        outputs = [output, zipout, manifest, ctx.outputs.jar],
        inputs = ctx.files.srcs + ctx.files.deps + ctx.files._clojure + ctx.files._compile + ctx.files._jdk,
        tools = [ctx.executable._zipper],
        mnemonic = "ClojureLibrary",
        progress_message = "Building clojure library for %s" % ctx.label,
    )

    return struct(
        providers = [
            DefaultInfo(
                files = depset([ctx.outputs.jar]),
            ),
            JavaInfo(
                output_jar = ctx.outputs.jar,
                compile_jar = ctx.outputs.jar,
                source_jar = ctx.outputs.jar,
                deps = [dep[JavaInfo] for dep in ctx.attr._clojure + ctx.attr.deps],
            ),
        ],
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

#def clojure_repl(name, deps):
#    java_binary(
#      name = name,
#      main_class = "clojure.main",
#      runtime_deps = deps + [
#       "@org_clojure//jar",
#       "@org_clojure_spec_alpha//jar",
#       "@org_clojure_core_specs_alpha//jar",
#       ],
#    )

#java_binary(
#    name = "hello-bin",
#    runtime_deps = [":hello"],
#    main_class = "Hello",
#)

