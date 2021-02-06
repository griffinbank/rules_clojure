def _clojure_toolchain(ctx):
    return [platform_common.ToolchainInfo(
        runtime = ctx.attr.classpath,
        scripts = {s.basename: s for s in ctx.files._scripts},
        jdk = ctx.attr._jdk,
        java = ctx.attr._jdk[java_common.JavaRuntimeInfo].java_executable_exec_path,
        java_runfiles = ctx.attr._jdk[java_common.JavaRuntimeInfo].java_executable_runfiles_path,
        files = struct(
            runtime = ctx.files.classpath,
            scripts = ctx.files._scripts,
            jdk = ctx.files._jdk,
        ),
    )]

clojure_toolchain = rule(
    implementation = _clojure_toolchain,
    attrs = {
        "classpath": attr.label_list(
            doc = "List of JavaInfo dependencies which will be implictly added to library/repl/test/binary classpath. Must contain clojure.jar",
            providers = [JavaInfo],
        ),
        "_scripts": attr.label(
            default = "//scripts",
        ),
        "_jdk": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            providers = [java_common.JavaRuntimeInfo],
        ),
    },
    provides = [platform_common.ToolchainInfo],
)

def rules_clojure_toolchains():
    native.register_toolchains("@rules_clojure//:clojure_toolchain")
