load("//:rules.bzl", "clojure_binary", "clojure_library")

package(default_visibility = ["//visibility:public"])

exports_files(glob(["*.clj"]))

java_library(
    name = "bootstrap",
    resource_strip_prefix = "src",
    resources = [
        "bootstrap_compiler.clj",
        "bootstrap_worker.clj",
        "compile.clj",
        "fs.clj",
        "jar.clj",
        "parse.clj",
        "persistentClassLoader.clj",
        "persistent_classloader.clj",
        "util.clj",
        "worker.clj",
    ],
    runtime_deps = [
        "@rules_clojure_maven_deps//:org_clojure_clojure",
        "@rules_clojure_maven_deps//:org_clojure_core_cache",
        "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
        "@rules_clojure_maven_deps//:org_clojure_data_json",
        "@rules_clojure_maven_deps//:org_clojure_java_classpath",
        "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
        "@rules_clojure_maven_deps//:org_clojure_tools_namespace",
    ],
)

java_binary(
    name = "bootstrap-bin",
    jvm_flags = [
        "-Dclojure.main.report=stderr",
        "-cp $${CLASSPATH}:worker-classes",
    ],
    main_class = "clojure.main",
    runtime_deps = [":bootstrap"],
)

genrule(
    name = "bootstrap-worker",
    outs = ["libworker.jar"],
    cmd = """
    mkdir -p worker-classes
    $(location :bootstrap-bin) -m rules-clojure.bootstrap-worker $(location :libworker.jar)
    """,
    tools = [":bootstrap-bin"],
)

genrule(
    name = "bootstrap-compiler",
    outs = ["libcompile.jar"],
    cmd = """
    mkdir -p compiler-classes
    $(location :bootstrap-bin) -m rules-clojure.bootstrap-compiler $(location :libcompile.jar)
    """,
    tools = [":bootstrap-bin"],
)

java_import(
    name = "libworker",
    data = [":bootstrap-worker"],
    jars = ["libworker.jar"],
    runtime_deps = [
        "@rules_clojure_maven_deps//:org_clojure_clojure",
        "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
        "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
    ],
)

java_import(
    name = "libcompile",
    data = [":bootstrap-compiler"],
    jars = ["libcompile.jar"],
)

java_binary(
    name = "worker",
    jvm_flags = [
        "-Dclojure.main.report=stderr",
        "-Xmx2g",
        "-XX:MaxMetaspaceSize=4g",
    ],
    main_class = "clojure.main",
    runtime_deps = ["libworker"],
)

clojure_library(
    name = "libfs",
    srcs = ["fs.clj"],
    aot = ["rules-clojure.fs"],
    resource_strip_prefix = "src",
    resources = ["fs.clj"],
    deps = [
        "@rules_clojure_maven_deps//:org_clojure_clojure",
        "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
        "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
    ],
)

clojure_library(
    name = "libgen_build",
    srcs = [
        "gen_build.clj",
        "parse.clj",
    ],
    aot = [
        "clojure.java.classpath",
        "clojure.tools.deps.alpha.extensions",
        "clojure.tools.deps.alpha.util.session",
        "clojure.tools.deps.alpha.util.io",
        "clojure.tools.deps.alpha.util.dir",
        "clojure.tools.deps.alpha.util.concurrent",
        "clojure.tools.deps.alpha",
        "clojure.tools.reader.default-data-readers",
        "clojure.tools.reader.impl.commons",
        "clojure.tools.reader.impl.inspect",
        "clojure.tools.reader.impl.errors",
        "clojure.tools.reader.impl.utils",
        "clojure.tools.reader.reader-types",
        "clojure.tools.reader",
        "clojure.tools.namespace.parse",
        "clojure.tools.namespace.dependency",
        "clojure.tools.namespace.track",
        "clojure.tools.namespace.file",
        "clojure.tools.namespace.find",
        "rules-clojure.gen-build",
        "rules-clojure.parse",
    ],
    resource_strip_prefix = "src",
    deps = [
        "libfs",
        "@rules_clojure_maven_deps//:org_clojure_clojure",
        "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
        "@rules_clojure_maven_deps//:org_clojure_data_json",
        "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
        "@rules_clojure_maven_deps//:org_clojure_tools_deps_alpha",
        "@rules_clojure_maven_deps//:org_clojure_tools_namespace",
    ],
)

java_binary(
    name = "gen_build",
    jvm_flags = [
        "-Xmx500m",
        "-XX:MaxMetaspaceSize=500m",
    ],
    main_class = "rules_clojure.gen_build",
    runtime_deps = [":libgen_build"],
)

clojure_library(
    name = "testrunner",
    srcs = ["testrunner.clj"],
    aot = ["rules-clojure.testrunner"],
    resource_strip_prefix = "src",
    deps = [
        "@rules_clojure_maven_deps//:cloverage_cloverage",
        "@rules_clojure_maven_deps//:org_clojure_clojure",
        "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
        "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
    ],
)

java_library(
    name = "srcjar",
    resource_strip_prefix = "src",
    resources = glob(["*.clj"]),
)

## files needed for the clj toolchain
filegroup(
    name = "toolchain_files",
    srcs = glob(["*.clj"]) + ["//:deps.edn"],
)
