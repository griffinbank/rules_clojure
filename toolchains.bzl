load("@rules_proto//proto:repositories.bzl", "rules_proto_dependencies", "rules_proto_toolchains")
load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")

def rules_clojure_default_toolchain():
    rules_proto_dependencies()
    rules_proto_toolchains()
    maven_install(name="rules_clojure_maven",
                  artifacts = [
                      maven.artifact(group="org.clojure",
                                     artifact="clojure",
                                     version="1.10.3",
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
                      "org.clojure:data.json:2.4.0",
                      "org.clojure:java.classpath:1.0.0",
                      "org.clojure:tools.namespace:1.1.0",
                      "org.clojure:tools.deps.alpha:0.14.1212",
                      "org.projectodd.shimdandy:shimdandy-api:1.2.1",
                      "org.projectodd.shimdandy:shimdandy-impl:1.2.1"],
                  repositories = ["https://repo1.maven.org/maven2",
                                  "https://repo.clojars.org/"])
