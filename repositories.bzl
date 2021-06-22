load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")

def rules_clojure_dependencies():
    maven_install(name="rules_clojure_maven",
    artifacts = [
        # `clojure` does something special here that maven doesn't like
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
        "org.clojure:data.json:2.3.1",
        "org.projectodd.shimdandy:shimdandy-api:1.2.1",
        "org.projectodd.shimdandy:shimdandy-impl:1.2.1",
        "com.google.code.gson:gson:2.8.7"


    ],
    repositories = ["https://repo1.maven.org/maven2",
                    "https://repo.clojars.org/"])
