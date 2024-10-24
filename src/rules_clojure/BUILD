package(default_visibility = ["//visibility:public"])

load("//:rules.bzl", "clojure_library", "clojure_binary")

exports_files(glob(["*.clj"]))

java_binary(
    name="bootstrap-deps",
    resources=["bootstrap_compiler.clj",
               "bootstrap_worker.clj",
               "compile.clj",
               "fs.clj",
               "jar.clj",
               "worker.clj",
               "parse.clj",
               "persistent_classloader.clj",
               "persistentClassLoader.clj",
               "util.clj"],

    main_class="clojure.main",
    jvm_flags=["-Dclojure.main.report=stderr",
               "-cp $${CLASSPATH}:worker-classes"],
    resource_strip_prefix="src",
    runtime_deps=["@rules_clojure_maven_deps//:org_clojure_clojure",
                  "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
                  "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
                  "@rules_clojure_maven_deps//:org_clojure_core_cache",
                  "@rules_clojure_maven_deps//:org_clojure_data_json",
                  "@rules_clojure_maven_deps//:org_clojure_java_classpath",
                  "@rules_clojure_maven_deps//:org_clojure_tools_namespace"])

genrule(
    name="bootstrap-worker",
    tools=["bootstrap-deps"],
    cmd="""
    mkdir -p worker-classes
    $(location :bootstrap-deps) -m rules-clojure.bootstrap-worker $(location :libworker.jar)
    """,
    outs=["libworker.jar"])

genrule(
    name="bootstrap-compiler",
    tools=["bootstrap-deps"],
    cmd="""
    mkdir -p compiler-classes
    $(location :bootstrap-deps) -m rules-clojure.bootstrap-compiler $(location :libcompile.jar)
    """,
    outs=["libcompile.jar"])

java_import(name="libworker",
            jars=["libworker.jar"],
            runtime_deps=["@rules_clojure_maven_deps//:org_clojure_clojure",
                          "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
                          "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha"],
            data=[":bootstrap-worker"])

java_import(name="libcompile",
            jars=["libcompile.jar"],
            data=[":bootstrap-compiler"])

java_binary(name="worker",
            main_class="clojure.main",
            jvm_flags=["-Dclojure.main.report=stderr",
                       "-Xmx2g",
                       "-XX:MaxMetaspaceSize=4g"],
            runtime_deps=["libworker"])

clojure_library(name="libfs",
                srcs=["fs.clj"],
                resource_strip_prefix="src",
                resources=["fs.clj"],
                aot=["rules-clojure.fs"],
                deps=["@rules_clojure_maven_deps//:org_clojure_clojure",
                      "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
                      "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha"])

clojure_library(
    name= "libgen_build",
    srcs=["gen_build.clj", "parse.clj"],
    resource_strip_prefix = "src",
    deps=["@rules_clojure_maven_deps//:org_clojure_clojure",
          "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
          "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
          "@rules_clojure_maven_deps//:org_clojure_tools_namespace",
          "@rules_clojure_maven_deps//:org_clojure_tools_deps_alpha",
          "@rules_clojure_maven_deps//:org_clojure_data_json",
          "libfs"],
    aot=["clojure.java.classpath",
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
         "rules-clojure.parse"])

java_binary(name="gen_build",
            main_class="rules_clojure.gen_build",
            runtime_deps=[":libgen_build"],
            jvm_flags = ["-Xmx500m", "-XX:MaxMetaspaceSize=500m"])

clojure_library(name= "testrunner",
                srcs=["testrunner.clj"],
                resource_strip_prefix="src",
                aot=["rules-clojure.testrunner"],
                deps=["@rules_clojure_maven_deps//:org_clojure_clojure",
                      "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
                      "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha"])

java_library(name="srcjar",
             resources=glob(["*.clj"]),
             resource_strip_prefix="src")

## files needed for the clj toolchain
filegroup(name="toolchain_files",
          srcs= glob(["*.clj"]) + ["//:deps.edn"])
