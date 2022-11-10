load("@rules_clojure_maven//:defs.bzl", "pinned_maven_install")

def rules_clojure_default_toolchain():
    pinned_maven_install()
