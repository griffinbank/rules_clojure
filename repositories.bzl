load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

def rules_clojure_dependencies():
    maven_install(name="rules_clojure_maven",
                  artifacts = [
                      maven.artifact(group="org.clojure",
                                     artifact="clojure",
                                     version="1.10.2",
                                     exclusions=["org.clojure:spec.alpha",
                                                 "org.clojure:core.specs.alpha"]),
                      maven.artifact(group="org.clojure",
                                     artifact="spec.alpha",
                                     version="0.2.194",
                                     exclusions=["org.clojure:clojure"]),
                      maven.artifact(group="org.clojure",
                                     artifact="core.specs.alpha",
                                     version="0.2.56",
                                     exclusions=["org.clojure:clojure",
                                                 "org.clojure:spec.alpha"]),
                      "org.clojure:java.classpath:1.0.0",
                      "org.clojure:tools.namespace:1.1.0",
                      "org.clojure:tools.deps.alpha:0.9.857",
                      "com.google.code.gson:gson:2.8.7",
                      "org.projectodd.shimdandy:shimdandy-api:1.2.1",
                      "org.projectodd.shimdandy:shimdandy-impl:1.2.1"],
                  repositories = ["https://repo1.maven.org/maven2",
                                  "https://repo.clojars.org/"])

    http_archive(
        name = "rules_proto",
        sha256 = "602e7161d9195e50246177e7c55b2f39950a9cf7366f74ed5f22fd45750cd208",
        strip_prefix = "rules_proto-97d8af4dc474595af3900dd85cb3a29ad28cc313",
        urls = [
            "https://mirror.bazel.build/github.com/bazelbuild/rules_proto/archive/97d8af4dc474595af3900dd85cb3a29ad28cc313.tar.gz",
            "https://github.com/bazelbuild/rules_proto/archive/97d8af4dc474595af3900dd85cb3a29ad28cc313.tar.gz",
        ],
    )
