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
                       exclusions=["org.clojure:clojure"]),
        "org.clojure:tools.namespace:1.1.0",
        "org.clojure:tools.deps.alpha:0.9.857"
    ],
    repositories = ["https://repo1.maven.org/maven2",
                    "https://repo.clojars.org/"])
