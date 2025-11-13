load("@rules_clojure//:rules.bzl", "clojure_library", "clojure_binary")

package(default_visibility = ["//visibility:public"])

java_library(name="src",
             resources=glob(["*.clj"]),
             resource_strip_prefix="src")

clojure_library(name="core",
                srcs=["core.clj"],
                resource_strip_prefix="src",
                # resources=[],
                aot=["example.core"],
                deps=["@deps//:org_clojure_clojure"])

java_binary(name="uberjar",
            runtime_deps=["core"])


## Build using:
# bazel build //src/example:uberjar_deploy.jar

# asking for just uberjar.jar creates a normal jar. Building `foo_deploy.jar` will create the uberjar

# java -cp bazel-bin/src/examples/uberjar_deploy.jar example.core
