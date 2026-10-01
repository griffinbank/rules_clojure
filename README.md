# Yet Another Clojure rules for [Bazel](https://bazel.build)

Status: Stable. [Griffin](https://www.griffin.com) is using it in production

# Why Bazel?

Bazel is a build tool for large projects, especially multi-language and monorepo projects. It has support for [many languages](https://gist.github.com/thundergolfer/02f3a696459b968aa765376011858350). Bazel only builds 'dirty' targets, like `make`. Unlike `make`, it uses a sandbox to guarantee a target's dependencies are specified correctly.

## Testing

Bazel can cache test results, so bazel only executes tests that depend on files that have changed since the last time the test passed. Bazel also supports [Remote Build Execution](https://bazel.build/remote/rbe). The combination of test caching and RBE leads to dramatic speedup of CI times.

## Features
- tools.deps support
- native JVM libraries
- fine-grained dependency analysis
- directory layout flexibility

## Setup

Add the following to your `MODULE.bazel`:

```skylark
bazel_dep(name = "rules_clojure", version = "0.0")

RULES_CLOJURE_SHA = "$CURRENT_SHA"

archive_override(
    module_name = "rules_clojure",
    strip_prefix = "rules_clojure-%s" % RULES_CLOJURE_SHA,
    urls = ["https://github.com/griffinbank/rules_clojure/archive/%s.zip" % RULES_CLOJURE_SHA],
)

deps = use_extension("@rules_clojure//:extensions.bzl", "deps")
deps.install(
    aliases = [
        "dev",
        "test",
    ],
    deps_edn = "//:deps.edn",
    repo_name = "deps",
)

use_repo(deps, "deps")
```

`deps.install` also accepts `clj_version` (the Clojure CLI version used to resolve deps, default `1.11.1.1347`) and `env` (a dict of environment variables used while resolving, e.g. for private maven repo credentials).


```
load("@rules_clojure//:rules.bzl", "clojure_library")

clojure_library(
    name = "libbbq",
    srcs = ["bbq.clj"],
    deps = ["foo"],
    runtime_deps = ["bar"],
    resource_strip_prefix = "src",
    aot = ["foo.bbq"])
```

It is likely you're interested in using Bazel because you have large projects with long compile and/or test steps. By default, rules_clojure attempts to AOT as much as possible, for speed.

`clojure_library` produces a jar.

- `srcs` are files that should be on the classpath while AOTing. The resulting classfiles will be added to the jar, but `srcs` will not. If you want the .clj to be present in the final jar, add it in `resources`.
- `deps` may be `clojure_library` or any bazel JavaInfo target (`java_library`, etc).
- `runtime_deps` works the same as `java_library`
- `aot` is a list of namespaces to compile. Specifying `srcs` without `aot` is an error.
- `resources` are unconditionally added to the jar. `rules_java` expects all code to follow the maven directory layout, and does not support building jars from source files in other locations. To avoid Clojure projects being forced into the maven directory layout, use [resource_strip_prefix](https://docs.bazel.build/versions/main/be/java.html#java_library.resource_strip_prefix), which behaves the same as in `java_library`.

Note that `clojure_library` AOT is _non-transitive_. By default `(clojure.core/compile 'foo.bar)` will AOT foo.bar and all of its dependencies, which prevents incremental compilation. `clojure_library` `require`s all dependencies in the foo.bar ns declaration and then compiles, resulting in a jar containing only foo.bar .class files.

If you don't need to AOT, `clojure_library` isn't necessary, just use `java_library` with `resource_strip_prefix`.

Note that AOT will determine whether a library should appear in `deps` or `runtime_deps`. If a library is being AOT'd, everything that it loads at compile time will need to appear in `deps`. If it is not being AOT'd, dependencies should be listed in `runtime_deps`.

### clojure_repl

```
load("@rules_clojure//:rules.bzl", "clojure_repl")

clojure_repl(
  name = "foo_repl",
  main_class = "clojure.main",
  args = ["-e", "foo.main"],
  runtime_deps = [":foo", "@deps//:__all"],
  classpath_dirs = ["//:src", "//:dev", "//:test"],
  tags = ["no-sandbox"],
  data = [])
```

Like `java_binary`, the repl process runs from the bazel-bin package directory. Unlike the built-in java rules, it supports `classpath_dirs`, which allows adding directories to the classpath, like a conventional clojure repl.

### clojure_test

```
load("@rules_clojure//:rules.bzl", "clojure_test")

clojure_test(
  name = "bar_test.test",
  test_ns = "foo.bar-test",
  deps = [":bar_test"])
```

Delegates to `java_test`, using `rules_clojure.testrunner` as the main class. `clojure_test` uses `clojure.test` to run all tests in a single namespace. Note that bazel defines a test as a script that returns exit code 0, so each `clojure_test` is a separate JVM, which makes startup time relevant.

When bazel sets `XML_OUTPUT_FILE` (it does for every test action), the runner also writes a JUnit XML report there, with one `<testcase>` per `deftest` (including per-test timing) and `<failure>`/`<error>` detail. This means callers (e.g. CI) get structured, per-test results to display, rather than just relying on bazel's bare pass/fail exit code.

A `main_class` can be supplied, which must refer to a _class_ (see [`gen-class`](https://clojuredocs.org/clojure.core/gen-class)).
The main entrypoint will be called with one argument: The Clojure namespace to test.
It should write a JUnit XML report to `$XML_OUTPUT_FILE` and exit with 0 for a pass and non-zero for failure.
See the [default runner](https://github.com/griffinbank/rules_clojure/blob/main/src/rules_clojure/testrunner.clj) for inspiration.

## tools.deps dependencies (optional)

The `deps` module extension (`deps.install`, see Setup) uses `tools.deps` to resolve dependencies from a deps.edn file and generates the `@deps` repo, with BUILD files containing `java_import` targets for all maven dependencies. Targets follow the same naming rules as `rules_jvm_external`, i.e. `@deps//:org_clojure_clojure`.

For each clojure namespace in the library, an additional target will be generated, which produces an AOT jar consisting of a non-transitive compile of just that namespace. The target has the name `@deps//:ns_org_clojure_tools_logging_clojure_tools_logging`, i.e. `ns_$packagename_$namespace`. Namespaces that are already AOT'd in their jar, `clojure.core`, and namespaces listed in `:no-aot` (below) don't get a per-namespace target. Libraries generated by `gen_srcs` (below) depend on the per-namespace targets. These per-namespace jars contain only .classfiles, and do not contain any resources in the original jar.

Note that tools.deps is only used for downloading jars, and creating the BUILD.bazel file with relationships between jars. Once the jars are downloaded, they behave like normal bazel java dependencies, and `clojure_library` participates in Bazel's normal java rules.

Since the `@deps` repo only downloads jars and only includes them in targets that depend on them, there is no harm in including all `:aliases` in your project.

## BUILD generation (optional)

In a BUILD file,
```
load("@rules_clojure//rules:tools_deps.bzl", "clojure_gen_srcs")

clojure_gen_srcs(name = "gen_srcs")
```

`gen_srcs` defines a target which behaves similarly to [bazel-gazelle](https://github.com/bazelbuild/bazel-gazelle). When run (`bazel run //:gen_srcs`), it introspects all directories under deps.edn `:paths`, and generates a BUILD.bazel file in each directory. `gen_srcs` defines `clojure_library` and `clojure_test` targets. Creates a library per namespace, with AOT.

Run `gen_srcs` again any time the ns declarations in the source tree change.

Adding

```
(ns foo.bbq
  {:bazel/clojure_library {:deps []}}
  (:require ...)
```

Adding the key `:bazel/clojure_library` to the namespace metadata will `merge` any fields into the generated `clojure_library` definition. `{:bazel/clojure_library {:aot false}}` turns off AOT for that namespace.


```
(ns foo.bbq
  {:bazel/clojure_binary {}}
  (:require ...)
```

Will produce a `clojure_binary` target that can be run with `bazel run //src/foo:bbq.bin`. By default it runs `clojure.main -m foo.bbq`. The map may contain `:name` (overrides the `bbq.bin` target name), `:main_class`, `:jvm_flags` (appended to any `:jvm_flags` from the deps.edn `:clojure_library` settings), and any other `java_binary` attributes.

### Tests

For files with paths matching `_test.clj` or `_test.cljc`, `gen_srcs` defines both a `clojure_library` and `clojure_test`:

```
clojure_library(name = "bar_test",
	resources = ["bar_test.clj"],
	deps = [...])

clojure_test(name = "bar_test.test",
	test_ns = "foo.bar-test",
	deps = ["bar_test"])

```

Because Bazel requires target names to be unique within the same directory, the library target is named after the file's basename (`bar_test`), while the `test` target is `$basename.test`, so the binary test target is `bar_test.test`. ¯\\\_(ツ)_/¯

```
(ns foo.bar-test
  {:bazel/clojure_test {:jvm_flags []
                        :tags [:integration]
                        :size :large
                        :timeout :long}}
  (:require ...)
```

Adding the key `:bazel/clojure_test` to the namespace metadata will `merge` any fields into the generated `clojure_test` definition.


### extra 3rd party deps

Prefer namespace metadata for specifying extra dependencies in your code. However, when deps.edn dependencies aren't complete, for example when using JVM libraries with native libraries, or some APIs that don't utilize `require`, e.g. [cognitect aws api](https://github.com/cognitect-labs/aws-api), those can be specified in deps.edn:

```clojure
:bazel {:deps {"@deps//:com_cognitect_aws_api" {:deps ["@deps//:com_cognitect_aws_endpoints"]}}}
```

put `:bazel {:deps {}}` at the top level of your deps.edn file. `:deps` is a map of `@deps` labels to a map of extra fields. It is applied when the `@deps` repo is generated (not by `gen_srcs`):

- a library label, e.g. `@deps//:com_cognitect_aws_api`, merges the fields into that library's `java_import`
- a per-namespace label, e.g. `@deps//:ns_com_cognitect_aws_api_cognitect_aws_client_api`, merges the fields into that namespace's AOT `clojure_library`

`examples/stress/deps.edn` contains known examples of libraries that require extra annotations to compile under rules clojure.

### no AOT

```clojure
:bazel {:no-aot #{foo.bar}}
```

Instructs the `@deps` repo not to AOT that namespace. Note that this only applies to `deps` dependencies.

### Coarse dependencies

Fine grained dependencies are ideal from an efficiency perspective, but it isn't always possible to make them work.

`gen_srcs` also creates a few extra targets in every directory on the deps.edn search path. It will produce targets named `__clj_files` (containing all source files) and `__clj_lib`  containing all compiled libraries. `//src:__clj_files` includes all src files under `src`. These targets are useful for e.g. static analysis tools like clj-kondo.

Use `__clj_lib`, `__clj_files` and `@deps//:__all` sparingly. By necessity they will be dirty any time _any_ src file or dependency changes, leading to increased build and test times.

## deps.edn options

### :ignore / Resources

You probably want to create your own java_library targets for `resources`.

By default, `resources` is on the tools.deps classpath. By default, `gen_srcs` operates on every directory under `:paths`. When `gen_srcs` runs, it will overwrite any existing BUILD.bazel files. To tell `gen_srcs` to ignore those directories:

```clojure
:bazel {:ignore ["resources", "test-resources"]}
```

`gen_srcs` will not produce BUILD.bazel files for any `:paths` entry listed under `:ignore`

### :clojure_library and :clojure_test

```clojure
:bazel {:clojure_library {:deps ["//resources:data_readers"]}
        :clojure_test {:jvm_flags ["-Xmx2g"]}}
```

In deps.edn, any fields under :clojure_library and :clojure_test will be added to _every_ library and test generated by `gen_srcs`

## CLJS support

```
load("@rules_clojure//:rules.bzl", "cljs_library", "clojure_library")

clojure_library(
  name="bar",
  resources=["bar.cljs", "bar.cljc"],
  resource_strip_prefix="src/")

cljs_library(
  name="release",
  deps=["@deps//:foo",
        ":bar"],
  compile_opts_files=[":build.edn"],
  compile_opts_strs=["{:output-to \"$(BINDIR)/frontend/release/index.js\" :output-dir \"$(BINDIR)/frontend/release\"}"],
  data=["//:node_modules"],
  outs=["out/index.js"])
```

Uses `java_binary` and `cljs.main` to compile clojurescript. `deps` is JVM deps to put on the classpath. Supports `compile_opts_files` for build.edn files, and `compile_opts_strs` for EDN strings.

Currently the clojurescript compiler hardcodes the path `./node_modules`, so bazel-managed node modules isn't supported yet (CLJS-3327).

The clojurescript compiler loads `.clj` and `.cljs` (and `.js`) files using the standard java classpath mechanisms. Bazel only wants to deal with jars, therefore use `clojure_library` and `java_library` containing `:resources` to pull files into the CLJS compile. Note that putting `.cljs` files in a `clojure_library` does not run the CLJS compiler, only `cljs_library` does that.

When `gen_srcs` runs, if a directory contains both `foo.clj` and `foo.cljs`, they will both end up in the same `clojure_library(name="foo",...)`.

### CLJS Testing

```
load("@rules_clojure//:rules.bzl", "cljs_library")
load("@rules_clojure//rules:tools_deps.bzl", "clojure_gen_namespace_loader")

clojure_gen_namespace_loader(
  name="gen_cljs_all_tests",
  output_filename="test/frontend/all_test_namespaces.cljc",
  output_fn_name="all-namespaces",
  output_ns_name="frontend.all-test-namespaces",
  exclude_nses=["frontend.test-runner"],
  platform=":cljs",
  in_dirs=["test"],
  deps_edn="//:deps.edn")


cljs_library(
  name="karma",
  deps=["//test/frontend:test_runner"],
  compile_opts_files=[":build-karma.edn"],
  compile_opts_strs=["{:output-to \"$(BINDIR)/frontend/karma-out/index.js\" :output-dir \"$(BINDIR)/frontend/karma-out\"}"],
  data=["@frontend_npm//:node_modules"],
  outs=["karma-out/index.js"])
```


`clojure_gen_namespace_loader` generates a file with the specified filename and namespace. It `:requires` all namespaces found under `in_dirs`. The generated namespace defines a function `all-namespaces`. Your test runner can require that namespace.

# Known Issues

- builds are non-reproducible for one reason:
  - there isn't a public API to reset the ID clojure uses for naming anonymous functions, which means anonymous AOT function names are non-deterministic
- When generating the `@deps` repo, I haven't found a way to identify :provided dependencies. Those have to be added by hand for now

# Compatibility

rules_clojure requires JDK 21 or higher. It is currently tested with Bazel 8.6 and JDK21

# Thanks

- Forked from https://github.com/simuons/rules_clojure
- Additional inspiration from https://github.com/markdingram/bazel-clojure
- Contains vendored code from tools.namespace https://github.com/clojure/tools.namespace
- Contains vendored code from tools.reader https://github.com/clojure/tools.reader
- Contains vendored code from java.classpath https://github.com/clojure/java.classpath



Copyright and License
----------------------------------------

Copyright © 2026 Griffin Bank. All rights reserved. The use and
distribution terms for this software are covered by the
[Eclipse Public License 1.0] which can be found in the file
epl-v10.html at the root of this distribution. By using this software
in any fashion, you are agreeing to be bound by the terms of this
license. You must not remove this notice, or any other, from this
software.
