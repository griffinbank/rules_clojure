load("//rules:tools_deps.bzl", "clojure_tools_deps")
_install = tag_class(attrs = {"repo_name": attr.string(),
                              "aliases": attr.string_list(),
                              "clj_version": attr.string(default="1.11.1.1347"),
                              "deps_edn": attr.label(default = "//:deps.edn"),
                              "env": attr.string_dict(default = {})})

def _module_impl(ctx):
    repos = []
    root_module_name = None
    for mod in ctx.modules:
        if mod.is_root:
            root_module_name = mod.name
        for attr in mod.tags.install:
            repos.append(attr.repo_name)
            clojure_tools_deps(name = attr.repo_name,
                               repo_name = attr.repo_name,
                               aliases = attr.aliases,
                               clj_version = attr.clj_version,
                               deps_edn = attr.deps_edn,
                               env = attr.env,
                               root_module_name = root_module_name or "")
    return ctx.extension_metadata(root_module_direct_deps="all",
                                  root_module_direct_dev_deps=[])

deps = module_extension(
  implementation = _module_impl,
  tag_classes = {"install": _install}
)
