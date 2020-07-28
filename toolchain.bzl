def _clojure_toolchain_impl(ctx):
    return [platform_common.ToolchainInfo(
        runtime = ctx.attr._runtime,
        scripts = ctx.attr._scripts,
        jdk = ctx.attr._jdk,
        java = ctx.attr._jdk[java_common.JavaRuntimeInfo].java_executable_exec_path,
        java_runfiles = ctx.attr._jdk[java_common.JavaRuntimeInfo].java_executable_runfiles_path,
        files = struct(
            runtime = ctx.files._runtime,
            scripts = ctx.files._scripts,
            jdk = ctx.files._jdk,
        ),
    )]

clojure_toolchain = rule(
    implementation = _clojure_toolchain_impl,
    attrs = {
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
)
