def clojure_repl(name, deps = [], ns = None):
    native.java_binary(
      name = name,
      main_class = "clojure.main",
      args = ["-e", """\"(ns {ns}) (clojure.main/repl) (in-ns '{ns}) (clojure.core/use 'clojure.core)\"""".format(ns = ns)] if ns else [],
      runtime_deps = deps + [
       "@org_clojure//jar",
       "@org_clojure_spec_alpha//jar",
       "@org_clojure_core_specs_alpha//jar",
       ],
    )
