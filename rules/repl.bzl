def clojure_repl(name, deps = [], ns = None):
    native.java_binary(
      name = name,
      main_class = "clojure.main",
      args = ["-e", """\"(require '[{ns}]) (in-ns '{ns}) (clojure.main/repl)\"""".format(ns = ns)] if ns else [],
      runtime_deps = deps + [
       "@org_clojure//jar",
       "@org_clojure_spec_alpha//jar",
       "@org_clojure_core_specs_alpha//jar",
       ],
    )
