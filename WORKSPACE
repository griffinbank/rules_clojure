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
            artifact = "clojure",
            exclusions = [
                "org.clojure:spec.alpha",
                "org.clojure:core.specs.alpha",
            ],
            group = "org.clojure",
            version = "1.11.1",
        ),
        maven.artifact(
            artifact = "spec.alpha",
            exclusions = ["org.clojure:clojure"],
            group = "org.clojure",
            version = "0.3.218",
        ),
        maven.artifact(
            artifact = "core.specs.alpha",
            exclusions = [
                "org.clojure:clojure",
                "org.clojure:spec.alpha",
            ],
            group = "org.clojure",
            version = "0.2.62",
        ),
        "org.clojure:data.json:2.4.0",
        "org.clojure:java.classpath:1.0.0",
        "org.clojure:tools.namespace:1.1.0",
        "org.clojure:tools.deps.alpha:0.14.1212",
        "cloverage:cloverage:1.2.4",
    ],
    fail_if_repin_required = True,
    maven_install_json = "@//:frozen_deps_install.json",
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://repo.clojars.org/",
    ],
)

load("@frozen_deps//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

load("//:repositories.bzl", "rules_clojure_deps")

rules_clojure_deps()

load("//:setup.bzl", "rules_clojure_setup")

rules_clojure_setup()

# used for testing
maven_install(
    name = "clojure_old",
    artifacts = [
        "org.clojure:clojure:1.8.0",
    ],
    fail_if_repin_required = True,
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://repo.clojars.org/",
    ],
)
