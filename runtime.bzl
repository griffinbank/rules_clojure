load("@rules_clojure//:repositories.bzl", "rules_clojure_dependencies", "rules_clojure_toolchains")

def clojure_runtime():
    rules_clojure_dependencies()
    rules_clojure_toolchains()
