load("//:rules.bzl", "clojure_library", "clojure_binary")

package(default_visibility = ["//visibility:public"])
exports_files(glob(["**/*.clj"]))

filegroup(name="srcs",
          srcs= glob(["*.clj"]) + ["//:deps.edn"])

java_library(
    name="bootstrap",
    resources=["bootstrap_compiler.clj",
               "bootstrap_gen_build.clj",
               "bootstrap_libfs.clj",
               "bootstrap_worker.clj",
               "compile.clj",
               "fs.clj",
               "jar.clj",
               "worker.clj",
               "gen_build.clj",
               "namespace/parse.cljc",
               "namespace/file.clj",
               "namespace/find.clj",
               "java/classpath.clj",
               "tools/reader.clj",
               "tools/reader/reader_types.clj",
               "tools/reader/default_data_readers.clj",
               "tools/reader/impl/commons.clj",
               "tools/reader/impl/errors.clj",
               "tools/reader/impl/inspect.clj",
               "tools/reader/impl/utils.clj",
               "persistent_classloader.clj",
               "persistentClassLoader.clj",
               "util.clj"],

    resource_strip_prefix="src",
    runtime_deps=["@rules_clojure_maven_deps//:org_clojure_clojure",
                  "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
                  "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
                  "@rules_clojure_maven_deps//:org_clojure_data_json",
                  "@rules_clojure_maven_deps//:org_clojure_tools_deps"])

java_binary(
    name="bootstrap-bin",
    main_class="clojure.main",
    runtime_deps=[":bootstrap"],
    jvm_flags=["-Dclojure.main.report=stderr",
               "-cp $${CLASSPATH}:worker-classes"])

genrule(
    name="bootstrap-worker",
    tools=[":bootstrap-bin"],
    cmd="""
    mkdir -p worker-classes
    $(location :bootstrap-bin) -m rules-clojure.bootstrap-worker $(location :libworker.jar)
    """,
    outs=["libworker.jar"])

genrule(
    name="bootstrap-compiler",
    tools=[":bootstrap-bin"],
    cmd="""
    mkdir -p compiler-classes
    $(location :bootstrap-bin) -m rules-clojure.bootstrap-compiler $(location :libcompile.jar)
    """,
    outs=["libcompile.jar"])

genrule(
    name="bootstrap-libfs",
    tools=[":bootstrap-bin"],
    cmd="""
    mkdir -p fs-classes
    $(location :bootstrap-bin) -m rules-clojure.bootstrap-libfs $(location :libfs.jar)
    """,
    outs=["libfs.jar"])

genrule(
    name="bootstrap-gen-build",
    tools=[":bootstrap-bin"],
    cmd="""
    mkdir -p gen-build-classes
    $(location :bootstrap-bin) -m rules-clojure.bootstrap-gen-build $(location :libgen_build.jar)
    """,
    outs=["libgen_build.jar"])

java_import(name="libworker",
            jars=["libworker.jar"],
            runtime_deps=["@rules_clojure_maven_deps//:org_clojure_clojure",
                          "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
                          "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha"],
            data=[":bootstrap-worker"])

java_import(name="libcompile",
            jars=["libcompile.jar"],
            data=[":bootstrap-compiler"])

java_import(name="libfs",
            jars=["libfs.jar"],
            data=[":bootstrap-libfs"])

java_import(name="libgen_build",
            jars=["libgen_build.jar"],
            runtime_deps = ["@rules_clojure_maven_deps//:org_clojure_clojure",
                            "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
                            "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
                            "@rules_clojure_maven_deps//:org_clojure_tools_deps",
                            ":libfs"],
            data=[":bootstrap-gen-build"])

java_binary(name="worker",
            main_class="rules_clojure.worker",
            jvm_flags=["-Dclojure.main.report=stderr",
                       "-Xmx2g",
                       "-XX:MaxMetaspaceSize=4g"],
            runtime_deps=["libworker"])

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
