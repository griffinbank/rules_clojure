# Yet Another Clojure rules for [Bazel](https://bazel.build)

Status: Stable. Griffin is using it production

# Why Bazel?

Bazel is a build tool for large projects, especially multi-language and monorepo projects. Bazel only builds 'dirty' targets, like make. Unlike make, it uses a sandbox to guarantee a target's dependencies are specified correctly.

## Testing

Bazel can cache test results, so bazel only executes tests that depend on files that have changed since the last time the test passed. Bazel also supports [Remote Build Execution](https://bazel.build/remote/rbe). The combination of test caching and RBE leads to dramatic speedup of CI times.

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

Differs from [simuons/rules_clojure](https://github.com/simuons/rules_clojure) that it uses `java_library` and `java_binary` as much as possible.

`clojure_binary`, `clojure_repl` and `clojure_test` are all macros that delegate to `java_binary`. `clojure_library` is new code.

For fast compilation, `clojure_library` is a Bazel persistent worker.

```
clojure_library(
    name = "libbbq",
    srcs = ["bbq.clj"],
    deps = ["foo"],
    resource_strip_prefix = ["src"],
    aot = ["foo.bbq"])
```

It is likely you're interested in using Bazel because you have large projects with long compile and or test steps. By default, rules_clojure attempts to AOT as much as possible, for speed.

`clojure_library` produces a jar.

- `srcs` are present on the classpath while AOTing, but the `.clj` is not added to the jar by default (.classfiles resulting from the AOT will be added to the jar).
- `deps` may be `clojure_library` or any bazel JavaInfo target (`java_library`, etc).
- `aot` is a list of namespaces to compile, non-transitively.
- `resources` are unconditionally added to the jar. `rules_java` expects all code to follow the maven directory layout, and does not support building jars from source files in other locations. To avoid Clojure projects being forced into the maven directory layout, use [resource_strip_prefix](https://docs.bazel.build/versions/main/be/java.html#java_library.resource_strip_prefix), which behaves the same as in `java_library`.

Note that `clojure_library` AOT is _non-transitive_. By default `(clojure.core/compile 'foo.bar)` will AOT foo.bar and all of its dependencies, which is non-deterministic over time if foo.bar's dependencies change. `clojure_library` `require`s all dependencies of foo.bar and then compiles, resulting in a jar containing only foo.bar .classfiles.

If you don't need to AOT, `clojure_library` isn't necessary, just use `java_library` with `resource_strip_prefix`.

Because of Clojure's general lack of concern about the difference between runtime and compile-time (e.g. AOT), prefer using `deps` over `runtime_deps`. A downstream library might depend on the library, and whether it's a `dep` or `runtime_dep` depends on whether the downstream library is AOTing or not.

### clojure_repl

```
clojure_repl(
  name = "foo_repl",
  deps = [":foo"])
```

Behaves as you'd expect. Delegates to `java_binary` with `main_class clojure.main`.

### clojure_test

```
clojure_test(
  name = "bar_test.test",
  test_ns = "foo.bar-test",
  srcs = ["bar_test"])
```

Delegates to `java_test`, using `rules-clojure.testrunner` as the main class. `clojure_test` uses `clojure.test` to run all tests in a single namespace. Note that bazel defines a test as a script that returns exit code 0, so each `clojure_test` is a separate JVM, which makes startup time relevant.

## tools.deps dependencies (optional)
In your WORKSPACE:
```
load("@rules_clojure//:repositories.bzl", "rules_clojure_dependencies")
rules_clojure_dependencies()

load("@rules_clojure//:toolchains.bzl", "rules_clojure_default_toolchain")
rules_clojure_default_toolchain()

load("@rules_clojure//rules:tools_deps.bzl", "clojure_tools_deps", "clojure_gen_srcs")

clojure_tools_deps(
  name = "deps",
  deps_edn = "//:deps.edn",
  aliases = ["dev", "test"])
```

`clojure_tools_deps` use `tools.deps` to resolve dependencies from a deps.edn file and write BUILD files containing `java_import` targets for all maven dependencies. Targets follow the same naming rules as `rules_jvm_external`, i.e. `@deps//:org_clojure_clojure`.

For each clojure namespace in the library, an additional target will be generated, which produces an AOT jar consisting of a non-transitive compile of just that namespace. The target has the name `@deps//:org_clojure_clojure_clojure_core`, i.e. `$packagename_$namespace`. libraries generated by `gen_src` (below), depend on the per-namespace targets. These per-namespace jars contain only .classfiles, and do not contain any resources in the original jar.

Note that tools.deps is only used for downloading jars, and creating the BUILD.bazel file with relationships between jars. Once the jars are downloaded, they behave like normal bazel java dependencies, and `clojure_library` participates in Bazel's normal java rules.

Since `clojure_tools_deps` only downloads jars and only includes them in targets that depend on them, there is no harm in including all `:aliases` in your project.

## BUILD generation (optional)

In a BUILD file,
```
load("@rules_clojure//rules:tools_deps.bzl", "clojure_gen_srcs")

clojure_gen_srcs(
    name = "gen_srcs")
```

`gen_srcs` defines a target which behaves similarly to [bazel-gazelle](https://github.com/bazelbuild/bazel-gazelle). When run, it introspects all directories under deps.edn `:paths`, and generates a BUILD.bazel file in each directory. `gen_src` defines `clojure_library` and `clojure_test` targets. Creates a library per namespace, with AOT.

Run `gen_srcs` again any time the ns declarations in the source tree change.

Adding

```
(ns foo.bbq
  {:bazel/clojure_library {:deps []}}
  (:require ...)
```

Adding the key `:bazel/clojure_library` to the namespace metadata will `merge` any fields into the generated `clojure_library` definition.

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

Because Bazel requires target names to be unique within the same directory, the namespace target always matches the `ns`, while the `test` target is `$ns.test`, so the binary test target is `foo_test.test`. ¯\\\_(ツ)_/¯

```
(ns foo.bar-test
  {:bazel/clojure_test {:jvm_flags []
                        :tags [:integration]
                        :timeout :long}}
  (:require ...)
```

Adding the key `:bazel/clojure_test` to the namespace metadata will `merge` any fields into the generated `clojure_test` definition.


### extra 3rd party deps

Prefer namespace metadata for specifying extra dependencies in your code. However, when deps.edn dependencies aren't complete, for example when using JVM libraries with native libraries, or some APIs that don't utilize `require`, e.g. [cognitect aws api](https://github.com/cognitect-labs/aws-api), those can be specified in deps.edn:

```clojure
:bazel {:deps {"@deps//:com_cognitect_aws_api" {:deps ["@deps//:com_cognitect_aws_endpoints"]}}}
```

put `:bazel {:deps {}}` at the top level of your deps.edn file. `:deps` will be merged in when running `gen_srcs`. Deps are a map of bazel labels to a map of extra fields to merge into the `clojure_library`.

### no AOT

```clojure
:bazel {:no-aot #{foo.bar}}
```

Instructs gen-build to not AOT that namespace.

### Coarse dependencies

Fine grained dependencies are ideal from an efficiency perspective, but it isn't always possible to make them work.

`gen_srcs` also creates a few extra targets in every directory on the deps.edn search path. It will produce `clojure_library` targets named `__clj_lib`  containing all source files in the directory (non-AOT'd), and all subpackages. ```//src:__clj_files``` includes all src files under `src`. These targets are useful for static analysis tools.

`__clj_lib` does not include dependencies. Use `@deps//:__all` to pull in all dependencies.

Use `__clj_lib`, `__clj_files` and `@deps//:__all` sparingly. By necessity they will be dirty any time _any_ src file or dependency changes, leading to increased build and test times.

## deps.edn options

### :ignore / Resources

You probably want to create your own java_library targets for `resources`.

By default, `resources` is on the tools.deps classpath. By default, `clojure_tools_deps` and `gen_srcs` operate on every directory under under `:paths`. When `clojure_tools_deps` runs, it will overwrite any existing BUILD.bazel files. To tell gen-build to ignore those libraries:

```clojure
:bazel {:ignore ["resources", "test-resources"]}
```

gen-build will not produce BUILD.bazel files for any path under `:ignore`

### :clojure_library and :clojure_test

```clojure
:bazel {:clojure_library {:deps ["//resources:data_readers"]}
        :clojure_test {:jvm_flags ["-Xmx=2g"]}}
```

In deps.edn, any fields under :clojure_library and :clojure_test will be added to _every_ library and test generated by gen_build

## CLJS support

```
clojure_library(
  name="bar",
  resources=["bar.cljs", "bar.cljc"],
  resource_strip_prefix="src/")

cljs_library(
  name="release",
  deps=["@deps//:foo",
        ":bar"],
  compile_opts_files=[":build.edn"],
  compile_opts_strs=["{:output-to \"$(BINDIR)/frontend/release/index.js\" :output-dir \"$(BINDIR)/frontend/release\"}"]
  data=["//:node_modules"],
  outs=["out/index.js"])
```

Uses `java_binary` and `cljs.main` to compile clojurescript. `deps` is JVM deps to put on the classpath. Supports `compile_opts_files` for build.edn files, and `compile_opts_strs` for EDN strings.

Currently the clojurescript compiler hardcodes the path `./node_modules`, so bazel-managed node modules isn't supported yet (CLJS-3327).

The clojurescript compiler loads `.clj` and `.cljs` (and `.js`) files using the standard java classpath mechanisms. Bazel only wants to deal with jars, therefore use `clojure_library` and `java_library` containing `:resources` to pull files into the CLJS compile. Note that putting `.cljs` files in a `clojure_library` does not run the CLJS compiler, only `cljs_library` does that.

When gen-build runs, if a directory contains both `foo.clj` and `foo.cljs`, they will both end up in the same `clojure_library(name="foo",...)`.

### CLJS Testing

```
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


## Toolchains

Rules require `@rules_clojure//:toolchain` type.

Default is registered with `rules_clojure_toolchains` from [@rules_clojure//:repositories.bzl](repositories.bzl)

Custom toolchain can be defined with `clojure_toolchain` rule from [@rules_clojure//:toolchains.bzl](toolchains.bzl)

Please see [example](examples/setup/custom) of custom toolchain.

# Known Issues

- builds are non-reproducible for two reasons:
  - Builds set the file modification time to current time. This is because clojure.lang.RT looks at file modification times when deciding whether to load a .clj or .class file. Setting the file modification time to the epoch for .class files would mean that if a source .clj is on the classpath with a non-zero modification time, it would always take precedence over an AOT .class file
  - there isn't a public API to reset the ID clojure uses for naming anonymous functions, which means anonymous AOT function names are non-deterministic
- When using gen-deps, I haven't found a way to identify :provided dependencies. Those have to be added by hand for now
- Do not use `user.clj`. If there is a user.clj at the root of your classpath, it will be loaded every time a new Clojure runtime is created, which can be many times during an AOT job. Additionally, dependencies in the user.clj are invisible to `gen-build`


# Thanks

- Forked from https://github.com/simuons/rules_clojure
- Additional inspiration from https://github.com/markdingram/bazel-clojure
