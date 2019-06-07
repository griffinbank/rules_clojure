def clojure_runtime():
    native.maven_jar(
        name = "org_clojure",
        artifact = "org.clojure:clojure:1.10.1",
    )

    native.maven_jar(
        name = "org_clojure_spec_alpha",
        artifact = "org.clojure:spec.alpha:0.2.176",
    )

    native.maven_jar(
        name = "org_clojure_core_specs_alpha",
        artifact = "org.clojure:core.specs.alpha:0.2.44",
    )
