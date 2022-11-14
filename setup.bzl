load("@rules_clojure_maven_deps//:defs.bzl", "pinned_maven_install")

def rules_clojure_setup():
    pinned_maven_install()