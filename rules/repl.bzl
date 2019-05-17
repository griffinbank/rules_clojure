def clojure_repl(name, deps):
    java_binary(
      name = name,
      main_class = "clojure.main",
      runtime_deps = deps + [
       "@org_clojure//jar",
       "@org_clojure_spec_alpha//jar",
       "@org_clojure_core_specs_alpha//jar",
       ],
    )
