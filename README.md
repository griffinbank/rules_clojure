# Yet Another Clojure rules for [Bazel](https://bazel.build)

## Features
- tools.deps support
- native JVM libraries
- fine-grained dependency analysis
- directory layout flexibility

## Setup

Add the following to your `WORKSPACE`:

```skylark
load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

git_repository(name = "rules_clojure",
               commit = "8ae46519293f41075a7f5ec253ac5ad4321d309c",
               remote = "https://github.com/griffinbank/rules_clojure.git")


load("@rules_clojure//:repositories.bzl", "rules_clojure_dependencies", "rules_clojure_toolchains")
load("@rules_clojure//:toolchains.bzl", "rules_clojure_toolchains")
```

Differs from [rules_clojure](https://github.com/simuons/rules_clojure) that it uses `java_library` and `java_binary` as much as possible. `clojure_binary`, `clojure_repl` and `clojure_test` are all macros that delegate to `java_binary`. `clojure_library` is new code, but it delegates to `java_library` as much as possible.

To avoid Clojure projects being forced into the maven directory layout, the design of rules_clojure is slightly different:

```
clojure_namespace(name = "bbq",
	srcs = {"bbq.clj" : "/foo/bbq.clj"},
	aot = ["foo.bbq"]
	deps = ["@deps//:org_clojure_clojure","//resources","@deps//:org_clojure_spec_alpha"])

clojure_library(
    name = "libbbq",
    srcs = ["bbq"],
    aot = ["foo.core"])
```

`clojure_namespace` declares a namespace, but produces no output on its own. `srcs` is a map of files to their destination location on the classpath.
`deps` may be `clojure_namespaces`, `clojure_library` or any bazel Java target. `aot` is a list of namespaces that _must_ be AOT'd in a library that includes this namespace.

`clojure_library` produces a jar. `srcs` is a list of targets to include in the jar. `aot` is a list of namespaces that should be aot'd, in addition to any mandatory AOTs from `clojure_namespace`.

`clojure_repl`

```
clojure_repl(
    name = "foo_repl",
    deps = [":foo"])
```

Behaves as you'd expect.

`clojure_test`

```
clojure_test(name = "bar_test.test",
	test_ns = "foo.bar-test",
	srcs = ["bar_test"])
```

Delegates to `java_test`, using `rules-clojure.testrunner`

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

`clojure_tools_deps` will call `clojure` to resolve dependencies from a deps.edn file and define `java_import` targets for all dependencies. Targets follow the same naming rules as `rules_jvm_external`, i.e. `@deps//:org_clojure_clojure`.

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

`gen_srcs` defines a target which behaves similarly to [bazel-gazelle](https://github.com/bazelbuild/bazel-gazelle). When run, it introspects all directories under deps.edn `:paths`, and generates a BUILD.bazel file in each directory. `gen_src` defines `clojure_namespace` and `clojure_test` targets. `repl` and `library` targets must be defined by hand.

Run `gen_srcs` again any time the ns declarations in the source tree change.

### Tests

For files containing `_test.clj`, gen-src defines both a `namespace` and a `test`. Because

```
clojure_namespace(name = "bar_test",
	srcs = {"bar_test.clj" : "/foo/bar_test.clj"},
	deps = [...],
	testonly = True)

clojure_test(name = "bar_test.test",
	test_ns = "foo.bar-test",
	srcs = ["bar_test"])

```

Bazel requires target names to be unique within the same directory, so the namespace target always matches the `ns`, while the `test` target is `$ns.test`, so `foo_test.test`. ¯\_(ツ)_/¯

### extra deps

Sometimes the dependency graph isn't complete, for example when using JVM libraries with native libraries, `:gen-class`, or some APIs that don't utilize `require`, e.g. [cognitect aws api](https://github.com/cognitect-labs/aws-api).

```clojure
:bazel {:extra-deps {"@deps//:com_cognitect_aws_api" {:deps ["@deps//:com_cognitect_aws_endpoints"]}
                      "//src/foo/foo_s3" {:deps ["@deps//:com_cognitect_aws_s3"]}
                      "@deps//:caesium_caesium" {:deps ["@griffin//native:libsodium"]}
```

put `:bazel {:extra-deps {}}` at the top level of your deps.edn file. `:extra-deps` will be merged in when running `gen_srcs`

Deps are a map of bazel labels to a map of extra fields to merge into `clojure_namespace` and imported deps.

## Toolchains

Rules require `@rules_clojure//:toolchain` type.

Default is registered with `rules_clojure_toolchains` from [@rules_clojure//:repositories.bzl](repositories.bzl)

Custom toolchain can be defined with `clojure_toolchain` rule from [@rules_clojure//:toolchains.bzl](toolchains.bzl)

Please see [example](examples/setup/custom) of custom toolchain.


# Thanks

Forked from https://github.com/simuons/rules_clojure
Additional inspiration from https://github.com/markdingram/bazel-clojure
