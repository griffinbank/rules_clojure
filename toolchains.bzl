load("@rules_proto//proto:repositories.bzl", "rules_proto_dependencies", "rules_proto_toolchains")
load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")

def _clojure_toolchain(ctx):
    return [platform_common.ToolchainInfo(
        runtime = ctx.attr.classpath,
        scripts = {s.basename: s for s in ctx.files._scripts},
        jdk = ctx.attr.jdk,
        java = ctx.attr.jdk[java_common.JavaRuntimeInfo].java_executable_exec_path,
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
            default = "//java/rules_clojure:ClojureWorker",
        ),
        "jdk": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            providers = [java_common.JavaRuntimeInfo],
        ),
    }
)

def rules_clojure_default_toolchain():
    rules_proto_dependencies()
    rules_proto_toolchains()
    native.register_toolchains("@rules_clojure//:rules_clojure_default_toolchain")
    maven_install(name="rules_clojure_maven",
                  artifacts = [
                      maven.artifact(group="org.clojure",
                                     artifact="clojure",
                                     version="1.10.3",
                                     exclusions=["org.clojure:spec.alpha",
                                                 "org.clojure:core.specs.alpha"]),
                      maven.artifact(group="org.clojure",
                                     artifact="spec.alpha",
                                     version="0.2.194",
                                     exclusions=["org.clojure:clojure"]),
                      maven.artifact(group="org.clojure",
                                     artifact="core.specs.alpha",
                                     version="0.2.56",
                                     exclusions=["org.clojure:clojure",
                                                 "org.clojure:spec.alpha"]),
                      "org.clojure:java.classpath:1.0.0",
                      "org.clojure:tools.namespace:1.1.0",
                      "org.clojure:tools.deps.alpha:0.12.1071",
                      "com.google.code.gson:gson:2.8.7",
                      "org.projectodd.shimdandy:shimdandy-api:1.2.1",
                      "org.projectodd.shimdandy:shimdandy-impl:1.2.1",
                      maven.artifact(group="cider",
                                     artifact="cider-nrepl",
                                     version="0.27.4",
                                     exclusions=["org.clojure:clojure",
                                                 "org.clojure:spec.alpha",
                                                 "org.clojure:core.specs.alpha"])],
                  repositories = ["https://repo1.maven.org/maven2",
                                  "https://repo.clojars.org/"])
