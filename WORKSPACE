workspace(name = "rules_clojure")

RULES_JVM_EXTERNAL_TAG = "6.6"
RULES_JVM_EXTERNAL_SHA = "3afe5195069bd379373528899c03a3072f568d33bd96fe037bd43b1f590535e7"

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "rules_jvm_external",
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    sha256 = RULES_JVM_EXTERNAL_SHA,
    url = "https://github.com/bazel-contrib/rules_jvm_external/releases/download/%s/rules_jvm_external-%s.tar.gz" % (RULES_JVM_EXTERNAL_TAG, RULES_JVM_EXTERNAL_TAG)
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
            version = "1.11.1",
            exclusions = [
                "org.clojure:spec.alpha",
                "org.clojure:core.specs.alpha"
            ]
        ),
        maven.artifact(
            group = "org.clojure",
            artifact = "spec.alpha",
            version = "0.3.218",
            exclusions = ["org.clojure:clojure"]
        ),
        maven.artifact(
            group = "org.clojure",
            artifact = "core.specs.alpha",
            version = "0.2.62",
            exclusions = [
                "org.clojure:clojure",
                "org.clojure:spec.alpha"
            ]
        ),
        "org.clojure:data.json:2.4.0",
        "org.clojure:java.classpath:1.0.0",
        "org.clojure:tools.namespace:1.1.0",
        "org.clojure:tools.deps.alpha:0.14.1212"
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

load ("//:repositories.bzl", "rules_clojure_deps")
rules_clojure_deps()

load("//:setup.bzl", "rules_clojure_setup")
rules_clojure_setup()


# used for testing
maven_install(
    name = "clojure_old",
    artifacts = [
        "org.clojure:clojure:1.8.0",],
    fail_if_repin_required = True,
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://repo.clojars.org/"
    ])
