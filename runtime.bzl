load("@rules_clojure//:repositories.bzl", "rules_clojure_dependencies", "rules_clojure_toolchains")

def clojure_runtime():
    """deprecated

    Please replace this macro with:

    load("@rules_clojure//:repositories.bzl", "rules_clojure_dependencies", "rules_clojure_toolchains")
    rules_clojure_dependencies()
    rules_clojure_toolchains()
    """
    rules_clojure_dependencies()
    rules_clojure_toolchains()
