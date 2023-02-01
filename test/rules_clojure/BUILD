load("//:rules.bzl", "clojure_library", "clojure_test")

package(default_visibility = ["//visibility:public"])

clojure_library(name="test-deps",
                resources=glob(["*.clj"]),
                resource_strip_prefix="test/",
                deps=["//src/rules_clojure:libworker",
                      "//src/rules_clojure:libcompile",
                      "//src/rules_clojure:srcjar"],
                data=["@rules_clojure_maven_deps//:org_clojure_clojure",
                      "@rules_clojure_maven_deps//:org_clojure_spec_alpha",
                      "@rules_clojure_maven_deps//:org_clojure_core_specs_alpha",
                      "@rules_clojure_maven_deps//:org_clojure_tools_deps_alpha"])

clojure_test(name="worker-test",
             deps=[":test-deps"],
             test_ns = "rules-clojure.worker-test")

clojure_test(name="persistent-classloader-test",
             deps=[":test-deps"],
             test_ns = "rules-clojure.persistent-classloader-test")

clojure_test(name="compile-test",
             deps=[":test-deps"],
             test_ns = "rules-clojure.compile-test")

clojure_test(name="jar-test",
             deps=[":test-deps"],
             test_ns = "rules-clojure.jar-test")