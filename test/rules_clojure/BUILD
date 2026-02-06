load("//:rules.bzl", "clojure_library", "clojure_test")

package(default_visibility = ["//visibility:public"])

##
ClojureJars=["@rules_clojure_maven_deps//:org_clojure_clojure",
             "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
             "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
             "@rules_clojure_maven_deps//:org_clojure_tools_deps"]

clojure_library(name="test-utils",
                aot=["rules-clojure.test-utils"],
                srcs=["test_utils.clj"],
                resource_strip_prefix="test",
                deps=["//src/rules_clojure:libfs",
                      "@bazel_tools//tools/java/runfiles"] + ClojureJars)

## java_library is special when it comes to runfiles. Without the filegroup, the jars won't appear as runfiles, despite us placing them in `data`.
filegroup(name="clojure",
          srcs=ClojureJars)

clojure_library(name="test-deps",
                resources=glob(["*.clj"]),
                resource_strip_prefix="test/",
                deps=["//src/rules_clojure:libworker",
                      "//src/rules_clojure:libcompile",
                      "//src/rules_clojure:srcjar",
                      ":test-utils",
                      "@example-simple//src/example:src"])

filegroup(name="clojure-old",
          srcs=["@clojure_old//:org_clojure_clojure"])

java_library(name="fs-src",
                resources=["//src/rules_clojure:fs.clj"])

clojure_test(name="worker-test",
             deps=[":test-deps"],
             data=["//src/rules_clojure:libcompile",
                   "@example-simple//src/example:src",
                   ":clojure"],
             env={"TEST_JARS": "$(rlocationpaths //src/rules_clojure:libcompile) $(rlocationpaths :clojure) $(rlocationpaths @example-simple//src/example:src)"},
             test_ns = "rules-clojure.worker-test")

clojure_test(name="persistent-classloader-test",
             deps=[":test-deps"],
             runtime_deps=["//src/rules_clojure:bootstrap"],
             data=["//src/rules_clojure:libcompile",
                   "//src/rules_clojure:srcs",
                   ":test-deps",
                   "clojure",
                   ":clojure-old",
                   "@example-simple//src/example:src",
                   "@bazel_tools//tools/java/runfiles",
                   "//src/rules_clojure:bootstrap"],
             env={"CLOJURE_JARS": "$(rlocationpaths :clojure)",
                  "CLOJURE_OLD": "$(rlocationpaths :clojure-old)",
                  "COMPILE_CLASSPATH": "$(rlocationpaths :clojure) $(rlocationpaths //src/rules_clojure:bootstrap) $(rlocationpaths :test-deps) $(rlocationpaths @example-simple//src/example:src) $(rlocationpaths @bazel_tools//tools/java/runfiles)"},
             test_ns = "rules-clojure.persistent-classloader-test")

clojure_test(name="compile-test",
             deps=[":test-deps",
                   "@example-simple//src/example:src"],
             test_ns = "rules-clojure.compile-test",
             data=["//src/rules_clojure:bootstrap"],
             env={"TEST_JARS": "$(rlocationpaths //src/rules_clojure:bootstrap)"})
