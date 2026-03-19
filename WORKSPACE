workspace(name = "rules_clojure")

RULES_JVM_EXTERNAL_TAG = "4.5"
RULES_JVM_EXTERNAL_SHA = "b17d7388feb9bfa7f2fa09031b32707df529f26c91ab9e5d909eb1676badd9a6"

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "rules_jvm_external",
    sha256 = RULES_JVM_EXTERNAL_SHA,
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG,
)

load("@rules_jvm_external//:repositories.bzl", "rules_jvm_external_deps")
rules_jvm_external_deps()

load("@rules_jvm_external//:setup.bzl", "rules_jvm_external_setup")
rules_jvm_external_setup()

load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")

maven_install(
    name = "frozen_deps",
    artifacts = [
        maven.artifact(
            group = "org.clojure",
            artifact = "clojure",
            version = "1.12.1",
            exclusions = [
                "org.clojure:spec.alpha",
                "org.clojure:core.specs.alpha"
            ]
        ),
        maven.artifact(
            group = "org.clojure",
            artifact = "spec.alpha",
            version = "0.5.238",
            exclusions = ["org.clojure:clojure"]
        ),
        maven.artifact(
            group = "org.clojure",
            artifact = "core.specs.alpha",
            version = "0.4.74",
            exclusions = [
                "org.clojure:clojure",
                "org.clojure:spec.alpha"
            ]
        ),
        "org.clojure:data.json:2.4.0",
        "org.clojure:tools.deps:0.28.1578"
    ],
    maven_install_json = "@//:frozen_deps_install.json",
    fail_if_repin_required = True,
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://repo.clojars.org/"
    ]
)

load("@frozen_deps//:defs.bzl", "pinned_maven_install")
pinned_maven_install()

# Go and Gazelle (for the Gazelle Clojure plugin)
http_archive(
    name = "io_bazel_rules_go",
    sha256 = "86d3dc8f59d253524f933aaf2f3c05896cb0b605fc35b460c0b4b039996124c6",
    urls = [
        "https://mirror.bazel.build/github.com/bazelbuild/rules_go/releases/download/v0.60.0/rules_go-v0.60.0.zip",
        "https://github.com/bazelbuild/rules_go/releases/download/v0.60.0/rules_go-v0.60.0.zip",
    ],
)

http_archive(
    name = "bazel_gazelle",
    sha256 = "675114d8b433d0a9f54d81171833be96ebc4113115664b791e6f204d58e93446",
    urls = [
        "https://mirror.bazel.build/github.com/bazelbuild/bazel-gazelle/releases/download/v0.47.0/bazel-gazelle-v0.47.0.tar.gz",
        "https://github.com/bazelbuild/bazel-gazelle/releases/download/v0.47.0/bazel-gazelle-v0.47.0.tar.gz",
    ],
)

load("@io_bazel_rules_go//go:deps.bzl", "go_register_toolchains", "go_rules_dependencies")
load("@bazel_gazelle//:deps.bzl", "gazelle_dependencies")

go_rules_dependencies()

go_register_toolchains(version = "1.22.0")

gazelle_dependencies()

load ("//:repositories.bzl", "rules_clojure_deps")
rules_clojure_deps()

load("//:setup.bzl", "rules_clojure_setup")
rules_clojure_setup()


# used for testing
maven_install(
    name = "clojure_old",
    artifacts = [
        "org.clojure:clojure:1.8.0"],
    fail_if_repin_required = True,
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://repo.clojars.org/"
    ])

local_repository(
    name = "example-simple",
    path = "examples/simple",
)
