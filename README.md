# Yet Another Clojure rules for [Bazel](https://bazel.build)

## Features
- tools.deps support
- native JVM libraries
- fine-grained dependency analysis
- directory layout flexibility

## Setup

Add the following to your `WORKSPACE`:

```skylark

RULES_JVM_EXTERNAL_TAG = "4.0"
RULES_JVM_EXTERNAL_SHA = "31701ad93dbfe544d597dbe62c9a1fdd76d81d8a9150c2bf1ecf928ecdf97169"

http_archive(
    name = "rules_jvm_external",
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    sha256 = RULES_JVM_EXTERNAL_SHA,
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG)

load("@rules_jvm_external//:defs.bzl", "maven_install")

load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

git_repository(name = "rules_clojure",
               commit = $CURRENT_SHA1,
               remote = "https://github.com/griffinbank/rules_clojure.git")


load("@rules_clojure//:repositories.bzl", "rules_clojure_dependencies", "rules_clojure_toolchains")
load("@rules_clojure//:toolchains.bzl", "rules_clojure_toolchains")
```

Differs from [rules_clojure](https://github.com/simuons/rules_clojure) that it uses `java_library` and `java_binary` as much as possible.

`clojure_binary`, `clojure_repl` and `clojure_test` are all macros that delegate to `java_binary`. `clojure_library` is new code, but it delegates to `java_library` as much as possible.

To avoid Clojure projects being forced into the maven directory layout, the design of this is slightly different:

```
clojure_library(
    name = "libbbq",
    srcs = {"bbq.clj" : "/foo/bbq.clj"},
	deps = ["foo"],
    aot = ["foo.core"])
```

`clojure_library` produces a Jar. `srcs` is a map of files to their destination location on the classpath.
`deps` may be `clojure_library` or any bazel JavaInfo target (`java_library`, etc). `aot` is a list of namespaces to compile.

Because of clojure's general lack of concern about the difference between runtime and compile-time, all clojure rules use `deps` and don't pay attention to runtime_deps. These are passed to `runtime_deps` when calling java rules.

### clojure_repl

```
clojure_repl(
    name = "foo_repl",
    deps = [":foo"])
```

Behaves as you'd expect.

### clojure_test

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

For files with paths containing `_test.clj`, gen-src defines both a `namespace` and a `test`.

```
clojure_namespace(name = "bar_test",
	srcs = {"bar_test.clj" : "/foo/bar_test.clj"},
	deps = [...],
	testonly = True)

clojure_test(name = "bar_test.test",
	test_ns = "foo.bar-test",
	srcs = ["bar_test"])

```

Because Bazel requires target names to be unique within the same directory, the namespace target always matches the `ns`, while the `test` target is `$ns.test`, so the binary test target is `foo_test.test`. ¯\_(ツ)_/¯

### extra deps

Sometimes the dependency graph isn't complete, for example when using JVM libraries with native libraries, `:gen-class`, or some APIs that don't utilize `require`, e.g. [cognitect aws api](https://github.com/cognitect-labs/aws-api).

```clojure
:bazel {:extra-deps {"@deps//:com_cognitect_aws_api" {:deps ["@deps//:com_cognitect_aws_endpoints"]}
                      "//src/foo/foo_s3" {:deps ["@deps//:com_cognitect_aws_s3"]}
                      "@deps//:caesium_caesium" {:deps ["@griffin//native:libsodium"]
                      "//test/..." {:deps ["@deps//::some_dep_for_all_tests"]}
```

put `:bazel {:extra-deps {}}` at the top level of your deps.edn file. `:extra-deps` will be merged in when running `gen_srcs`

Deps are a map of bazel labels to a map of extra fields to merge into `clojure_namespace` and imported deps.

[Wildcard](https://docs.bazel.build/versions/master/guide.html#specifying-targets-to-build) rules are supported _only_ on the left hand side of extra-deps declarations. This is to ensure that overly broad dependencies aren't specified

## Resources

`gen_src` will automatically create a `clojure_library` target for `/resources`, relative to the deps.edn. If your project has more resources in the deps.edn file, using

```clojure
{:bazel {:resources ["resources" "dev-resources" "test-resources"]
```

## Toolchains

Rules require `@rules_clojure//:toolchain` type.

Default is registered with `rules_clojure_toolchains` from [@rules_clojure//:repositories.bzl](repositories.bzl)

Custom toolchain can be defined with `clojure_toolchain` rule from [@rules_clojure//:toolchains.bzl](toolchains.bzl)

Please see [example](examples/setup/custom) of custom toolchain.

# Thanks

- Forked from https://github.com/simuons/rules_clojure
- Additional inspiration from https://github.com/markdingram/bazel-clojure
