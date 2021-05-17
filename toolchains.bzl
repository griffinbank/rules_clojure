def _clojure_toolchain(ctx):
    return [platform_common.ToolchainInfo(
        runtime = ctx.attr.classpath,
        scripts = {s.basename: s for s in ctx.files._scripts},
        jdk = ctx.attr.jdk,
        java = ctx.attr.jdk[java_common.JavaRuntimeInfo].java_executable_exec_path,
        java_runfiles = ctx.attr.jdk[java_common.JavaRuntimeInfo].java_executable_runfiles_path,
        files = struct(
            runtime = ctx.files.classpath,
            scripts = ctx.files._scripts,
            jdk = ctx.files.jdk,
        ))]

clojure_toolchain = rule(
    implementation = _clojure_toolchain,
    attrs = {
        "classpath": attr.label_list(
            doc = "List of JavaInfo dependencies which will be implictly added to library/repl/test/binary classpath. Must contain clojure.jar",
            providers = [JavaInfo],
            default = [
                "@rules_clojure_maven//:org_clojure_clojure",
                "@rules_clojure_maven//:org_clojure_spec_alpha",
                "@rules_clojure_maven//:org_clojure_core_specs_alpha",
            ]),
        "_scripts": attr.label(
            default = "//src/rules_clojure:toolchain_files",
        ),
        "jdk": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            providers = [java_common.JavaRuntimeInfo],
        ),
    }
)

def rules_clojure_default_toolchain():
    native.register_toolchains("@rules_clojure//:rules_clojure_default_toolchain")
