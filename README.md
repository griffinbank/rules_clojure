# Yet Another Clojure rules for [Bazel](https://bazel.build)

Status: pre-release.

## Features
- tools.deps support
- native JVM libraries
- fine-grained dependency analysis
- directory layout flexibility

## Setup

Add the following to your `WORKSPACE`:

```skylark


RULES_CLOJURE_SHA = $CURRENT_SHA1
http_archive(name = "rules_clojure",
             strip_prefix = "rules_clojure-%s" % RULES_CLOJURE_SHA,
             url = "https://github.com/griffinbank/rules_clojure/archive/%s.zip" % RULES_CLOJURE_SHA)

load("@rules_clojure//:repositories.bzl", "rules_clojure_dependencies")
rules_clojure_dependencies()

load("@rules_clojure//:toolchains.bzl", "rules_clojure_default_toolchain")
rules_clojure_default_toolchain()
```

Differs from [rules_clojure](https://github.com/simuons/rules_clojure) that it uses `java_library` and `java_binary` as much as possible.

`clojure_binary`, `clojure_repl` and `clojure_test` are all macros that delegate to `java_binary`. `clojure_library` is new code.

For fast compilation, `clojure_library` is a Bazel persistent worker, which uses protobufs.

```
clojure_library(
    name = "libbbq",
    srcs = ["bbq.clj"],
    deps = ["foo"],
    resource_strip_prefix = ["src"],
    aot = ["foo.core"])
```

It is likely you're interested in using Bazel because you have large projects with long compile and or test steps. In general, rules_clojure attempts to AOT as much as possible, for speed.

`clojure_library` produces a jar.

- `srcs` are present on the classpath while AOTing, but the `.clj` is not added to the jar (class files resulting from the AOT will be added to the jar).
- `deps` may be `clojure_library` or any bazel JavaInfo target (`java_library`, etc).
- `aot` is a list of namespaces to compile.
- `resources` are unconditionally added to the jar. `rules_java` expects all code to follow the maven directory layout, and does not support building jars from source files in other locations. To avoid Clojure projects being forced into the maven directory layout, use `resource_strip_prefix`, which behaves the same as in `java_library`.

Because of clojure's general lack of concern about the difference between runtime and compile-time (e.g. AOT), all clojure rules accept `deps` only. Output jars will use `deps` in both compile time and runtime dependencies (because a downstream library might depend on the library, and whether it's a `dep` or `runtime_dep` depends on whether the downstream library is AOTing or not.

### clojure_repl

```
clojure_repl(
    name = "foo_repl",
    deps = [":foo"])
```

Behaves as you'd expect. Delegates to `java_binary` with `main_class clojure.main`.

### clojure_test

```
clojure_test(name = "bar_test.test",
	test_ns = "foo.bar-test",
	srcs = ["bar_test"])
```

Delegates to `java_test`, using `rules-clojure.testrunner`. Note that bazel defines a test as a script that returns exit code 0, so startup time is relevant. `clojure_test` runs all tests in a single namespace.

## tools.deps dependencies (optional)
```
load("@rules_clojure//rules:tools_deps.bzl", "clojure_tools_deps", "install_clojure_tools_deps")

rules_clojure_dependencies()
rules_clojure_toolchains()

clojure_tools_deps(name = "deps",
                   clj_version = "1.10.1.763",
                   deps_edn = "//:deps.edn",
                   aliases = ["dev", "test"])
```

`clojure_tools_deps` will call `clojure` to resolve dependencies from a deps.edn file and write BUILD files containing `java_import` targets for all dependencies. Targets follow the same naming rules as `rules_jvm_external`, i.e. `@deps//:org_clojure_clojure`.

## BUILD generation (optional)

In a BUILD file,
```
load("@rules_clojure//rules:tools_deps.bzl", "clojure_gen_srcs")

clojure_gen_srcs(
    name = "gen_srcs",
    deps_edn = "//:deps.edn",
    aliases = ["dev", "test"],
    deps_repo_tag = "@deps")
```

`gen_srcs` defines a target which behaves similarly to [bazel-gazelle](https://github.com/bazelbuild/bazel-gazelle). When run, it introspects all directories under deps.edn `:paths`, and generates a BUILD.bazel file in each directory. `gen_src` defines `clojure_library` and `clojure_test` targets. Creates a library per namespace, with AOT on by default.

Run `gen_srcs` again any time the ns declarations in the source tree change.

### Tests

For files with paths matching `_test.clj`, gen-src defines both a `clojure_library` and `clojure_test`:

```
clojure_library(name = "bar_test",
	srcs = ["bar_test.clj"],
	deps = [...],
	testonly = True)

clojure_test(name = "bar_test.test",
	test_ns = "foo.bar-test",
	deps = ["bar_test"])

```

Because Bazel requires target names to be unique within the same directory, the namespace target always matches the `ns`, while the `test` target is `$ns.test`, so the binary test target is `foo_test.test`. ¯\_(ツ)_/¯

If a test namespace declaration contains any of the following metadata:
- `:bazel.test/tags`
- `:bazel.test/size`
- `:bazel.test/timeout`

those attributes will be added to the `clojure_test` target.
```
(ns foo.bar-test
  {:bazel.test/tags [:integration]
   :bazel.test/timeout :long}
  (:require [...])


### extra deps

Sometimes the dependency graph isn't complete, for example when using JVM libraries with native libraries, `:gen-class`, or some APIs that don't utilize `require`, e.g. [cognitect aws api](https://github.com/cognitect-labs/aws-api).

```clojure
:bazel {:extra-deps {"@deps//:com_cognitect_aws_api" {:deps ["@deps//:com_cognitect_aws_endpoints"]}
                      "//src/foo/foo_s3" {:deps ["@deps//:com_cognitect_aws_s3"]}}
        :no-aot '#{foo.bar}}
```

put `:bazel {:extra-deps {}}` at the top level of your deps.edn file. `:extra-deps` will be merged in when running `gen_srcs`. Deps are a map of bazel labels to a map of extra fields to merge into `clojure_namespace` and imported deps. `:no-aot` is also supported.

## Toolchains

Rules require `@rules_clojure//:toolchain` type.

Default is registered with `rules_clojure_toolchains` from [@rules_clojure//:repositories.bzl](repositories.bzl)

Custom toolchain can be defined with `clojure_toolchain` rule from [@rules_clojure//:toolchains.bzl](toolchains.bzl)

Please see [example](examples/setup/custom) of custom toolchain.

# Thanks

- Forked from https://github.com/simuons/rules_clojure
- Additional inspiration from https://github.com/markdingram/bazel-clojure
