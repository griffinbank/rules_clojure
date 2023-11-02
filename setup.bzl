load("@rules_jvm_external//:repositories.bzl", "rules_jvm_external_deps")
load("@rules_clojure_maven_deps//:defs.bzl", "pinned_maven_install")

def rules_clojure_setup():
    rules_jvm_external_deps()
    pinned_maven_install()
