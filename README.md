# Yet Another Clojure rules for [Bazel](https://bazel.build)

Differs from [https://github.com/simuons/rules_clojure] that it uses `java_library` and `java_binary` as much as possible. `clojure_library` builds jars

Includes a script that generates BUILD files from clj namespaces, similar to [bazel-gazelle](https://github.com/bazelbuild/bazel-gazelle)

## Features
- tools.deps support
- native JVM libraries
- fine-grained dependency analysis

## Setup

Add the following to your `WORKSPACE`:

```skylark
load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

git_repository(name = "rules_clojure",
               commit = "8ae46519293f41075a7f5ec253ac5ad4321d309c",
               remote = "https://github.com/griffinbank/rules_clojure.git")


load("@rules_clojure//:repositories.bzl", "rules_clojure_dependencies", "rules_clojure_toolchains")
load("@rules_clojure//:toolchains.bzl", "rules_clojure_toolchains")
load("@rules_clojure//rules:tools_deps.bzl", "clojure_tools_deps", "install_clojure_tools_deps")

rules_clojure_dependencies()

rules_clojure_toolchains()

install_clojure_tools_deps()
```

**Note**: Update commit and sha256 as needed.

## Rules

Load rules in your `BUILD` files from [@rules_clojure//:rules.bzl](rules.bzl)

- [clojure_binary](docs/rules.md#clojure_binary)
- [clojure_library](docs/rules.md#clojure_library)
- [clojure_repl](docs/rules.md#clojure_repl)
- [clojure_test](docs/rules.md#clojure_test)

## Dependencies

Dependencies are resolved via `tools.deps`, in a repository rule

```clojure

clojure_tools_deps(
    name = "deps",
    deps_edn = "//:deps.edn",
    aliases = ["dev", "test"])
```

At a terminal:

```
bazel run @deps//scripts:gen_build
```

This will resolve dependencies, and generate a BUILD file under `@deps`, one for each jar. It will also generate a BUILD file for each source directory in the clojure source path. The build files contain fine-grained dependencies on .clj and .jar files. Run `gen_build` again any time the ns declarations in the source tree change.

### extra deps

Sometimes the complete ns dependency graph isn't complete, for example when using native libraries, `:gen-class`, or some APIs that don't require a `require`, e.g. [cognitect aws api](https://github.com/cognitect-labs/aws-api).

```clojure
:bazel {:extra-deps {"@deps//:com_cognitect_aws_api" {:deps ["@deps//:com_cognitect_aws_endpoints"]}
                      "//src/foo/foo_s3" {:deps ["@deps//:com_cognitect_aws_s3"]}
                      "@deps//:caesium_caesium" {:deps ["@griffin//native:libsodium.dylib"]}
```

put `:bazel {:extra-deps {}}` at the top level of your deps.edn file. `:extra-deps` will be merged in when running `gen_build`

Deps are a map of bazel labels to a map of extra fields to merge into the `clojure_library` declaration.


## Toolchains

Rules require `@rules_clojure//:toolchain` type.

Default is registered with `rules_clojure_toolchains` from [@rules_clojure//:repositories.bzl](repositories.bzl)

Custom toolchain can be defined with `clojure_toolchain` rule from [@rules_clojure//:toolchains.bzl](toolchains.bzl)

Please see [example](examples/setup/custom) of custom toolchain.


# Thanks

Forked from https://github.com/simuons/rules_clojure
Inspiration from https://github.com/markdingram/bazel-clojure
