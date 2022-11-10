load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")

def rules_clojure_maven():
    maven_install(
        name="rules_clojure_maven",
        artifacts = [
            maven.artifact(group="org.clojure",
                           artifact="clojure",
                           version="1.11.1",
                           exclusions=["org.clojure:spec.alpha",
                                       "org.clojure:core.specs.alpha"]),
            maven.artifact(group="org.clojure",
                           artifact="spec.alpha",
                           version="0.3.218",
                           exclusions=["org.clojure:clojure"]),
            maven.artifact(group="org.clojure",
                           artifact="core.specs.alpha",
                           version="0.2.62",
                           exclusions=["org.clojure:clojure",
                                       "org.clojure:spec.alpha"]),
            "org.clojure:data.json:2.4.0",
            "org.clojure:java.classpath:1.0.0",
            "org.clojure:tools.namespace:1.1.0",
            "org.clojure:tools.deps.alpha:0.14.1212",
            "org.projectodd.shimdandy:shimdandy-api:1.2.1",
            "org.projectodd.shimdandy:shimdandy-impl:1.2.1"],
        maven_install_json = "@rules_clojure//:rules_clojure_maven_install.json",
        fail_if_repin_required = True,
        repositories = ["https://repo1.maven.org/maven2",
                        "https://repo.clojars.org/"]
    )
