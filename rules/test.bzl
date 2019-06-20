def _clojure_test_impl(ctx):
    ctx.actions.write(
        output = ctx.outputs.executable,
        content = "{java} -cp {classpath} clojure.main scripts/test.clj {sources}".format(
            java = ctx.attr._jdk[java_common.JavaRuntimeInfo].java_executable_exec_path,
            classpath = ":".join([f.path for f in ctx.files._runtime] + [f.short_path for f in ctx.files.deps]),
            sources = " ".join([f.path for f in ctx.files.srcs]),
        ),
    )

    return DefaultInfo(
        runfiles = ctx.runfiles(files = ctx.files.srcs + ctx.files.deps + ctx.files._runtime + ctx.files._scripts + ctx.files._jdk)
    )

clojure_test = rule(
    _clojure_test_impl,
    attrs = {
        "srcs": attr.label_list(default = [], allow_files = [".clj"]),
        "deps": attr.label_list(default = [], providers = [JavaInfo]),
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
    test = True
)
