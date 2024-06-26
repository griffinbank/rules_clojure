load("//:rules.bzl", "clojure_library", "clojure_test")

package(default_visibility = ["//visibility:public"])

##
ClojureJars=["@rules_clojure_maven_deps//:org_clojure_clojure",
             "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
             "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
             "@rules_clojure_maven_deps//:org_clojure_tools_deps_alpha"]

clojure_library(name="test-utils",
                aot=["rules-clojure.test-utils"],
                srcs=["test_utils.clj"],
                resource_strip_prefix="test",
                deps=["//src/rules_clojure:libfs",
                      "@bazel_tools//tools/java/runfiles"])

## java_library is special when it comes to runfiles. Without the filegroup, the jars won't appear as runfiles, despite us placing them in `data`.
filegroup(name="clojure",
          srcs=ClojureJars)

clojure_library(name="test-deps",
                resources=glob(["*.clj"]),
                resource_strip_prefix="test/",
                deps=["//src/rules_clojure:libworker",
                      "//src/rules_clojure:libcompile",
                      "//src/rules_clojure:srcjar",
                      ":test-utils"] + ClojureJars)

filegroup(name="clojure-old",
          srcs=["@clojure_old//:org_clojure_clojure"])

clojure_test(name="worker-test",
             deps=[":test-deps"],
             data=["@clojure_old//:org_clojure_clojure",
                   "//src/rules_clojure:bootstrap-deps.jar",
                   ":clojure"],
             env={"TEST_JARS": "$(rlocationpaths :clojure) $(rlocationpaths //src/rules_clojure:bootstrap-deps.jar)"},
             test_ns = "rules-clojure.worker-test")

clojure_test(name="persistent-classloader-test",
             deps=[":test-deps"],
             data=["//src/rules_clojure:libcompile",
                   "clojure",
                   ":clojure-old"],
             env={"CLOJURE_JARS": "$(rlocationpaths :clojure) $(rlocationpaths //src/rules_clojure:libcompile)",
                  "CLOJURE_OLD": "$(rlocationpaths :clojure-old)"},
             test_ns = "rules-clojure.persistent-classloader-test")

clojure_test(name="compile-test",
             deps=[":test-deps"],
             test_ns = "rules-clojure.compile-test")

clojure_test(name="fs-test",
             deps=[":test-deps"],
             test_ns = "rules-clojure.fs-test")
