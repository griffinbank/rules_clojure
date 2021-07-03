load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

RULES_JVM_EXTERNAL_TAG = "4.0"
RULES_JVM_EXTERNAL_SHA = "31701ad93dbfe544d597dbe62c9a1fdd76d81d8a9150c2bf1ecf928ecdf97169"

def rules_clojure_dependencies():

    if not native.existing_rule("rules_proto"):
        http_archive(
            name = "rules_proto",
            sha256 = "602e7161d9195e50246177e7c55b2f39950a9cf7366f74ed5f22fd45750cd208",
            strip_prefix = "rules_proto-97d8af4dc474595af3900dd85cb3a29ad28cc313",
            urls = [
                "https://mirror.bazel.build/github.com/bazelbuild/rules_proto/archive/97d8af4dc474595af3900dd85cb3a29ad28cc313.tar.gz",
                "https://github.com/bazelbuild/rules_proto/archive/97d8af4dc474595af3900dd85cb3a29ad28cc313.tar.gz",
            ],
        )

    if not native.existing_rule("rules_jvm_external"):
        http_archive(
            name = "rules_jvm_external",
            strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
            sha256 = RULES_JVM_EXTERNAL_SHA,
            url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG)
