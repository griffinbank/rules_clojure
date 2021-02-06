
CLJ_VERSIONS = {
    "1.10.1.763": ("https://download.clojure.org/install/clojure-tools-1.10.1.763.tar.gz", "2a3ec8a6c3639035c2bba10945ae9007ab0dc9136766b95d2161f354e62a4d10")
}

CLJ_EXTRACT_DIR = "bin/clj"

def _download_deps(repository_ctx):
    clj_version = repository_ctx.attr.clj_version

    url, sha256 = CLJ_VERSIONS[clj_version]

    repository_ctx.download_and_extract(
        auth = {},
        url = url,
        output = CLJ_EXTRACT_DIR,
        sha256 = sha256)

    repository_ctx.file("clj_info", content = """# url {url}
# sha256: {sha256}
""".format(url = url,
           sha256=sha256))


def _install_tools_deps_impl(repository_ctx):
    _download_deps(repository_ctx)
    repository_ctx.file("BUILD.bazel", content = """ """)

install_clojure_tools_deps_rule = repository_rule(
    _install_tools_deps_impl,
    attrs = {"clj_version": attr.string(default = "1.10.1.763")})

def install_clojure_tools_deps(**kwargs):
    install_clojure_tools_deps_rule(name = "install_clojure_tools_deps", **kwargs)


def _add_deps_edn(repository_ctx):
    repository_ctx.symlink(
        repository_ctx.path(repository_ctx.attr.deps_edn),
        repository_ctx.path("deps.edn"))

def _add_clj_info(repository_ctx):
    # depend on the clj_info file to trigger rebuilds if it changes
    repository_ctx.symlink(
        Label("@install_clojure_tools_deps//:clj_info"),
        repository_ctx.path("_clj_info"))

def _add_clj_script(repository_ctx):
    # the wrapper script for `clj`
    repository_ctx.file("_clj_install",
                        content = """#!/usr/bin/env bash
set -e
clojure -Srepro -Sdeps '{:mvn/local-repo "deps"}' -P""",
                        executable = True)


def _add_gen_build_script(repository_ctx):
    repository_ctx.file(repository_ctx.path("scripts/gen_build.sh"),
                        content = """#!/usr/bin/env bash
set -euxo pipefail;
cd external/rules_clojure/scripts;
clojure -Srepro -J-Dclojure.main.report=stderr -X gen-build/-main :deps-edn-path '"{deps_edn_path}"' :deps-out-dir '"{deps_out_dir}"' :deps-repo-tag '"{deps_repo_tag}"' :aliases '{aliases}'

""".format(deps_repo_tag = "@" + repository_ctx.attr.name,
           deps_edn_path = repository_ctx.path(repository_ctx.attr.deps_edn),
           deps_out_dir = repository_ctx.path(""),
           aliases = repository_ctx.attr.aliases),
                        executable = True)
    repository_ctx.file("scripts/BUILD", content="""
package(default_visibility = ["//visibility:public"])

sh_binary(name="gen_build",
          srcs=["gen_build.sh"],
          data=["@rules_clojure//scripts"]
)""")


def _run_tools_deps_impl(repository_ctx):
    _add_deps_edn(repository_ctx)
    _add_clj_info(repository_ctx)
    _add_clj_script(repository_ctx)
    _add_gen_build_script(repository_ctx)
    # repository_ctx.execute(["_clj_install"])
    # repository_ctx.symlink(Label("//scripts:gen_build.clj"), repository_ctx.path("scripts/gen_build.clj"))
    # repository_ctx.execute([repository_ctx.path("scripts/gen_build.sh")], quiet = False, working_directory = "scripts")

clojure_tools_deps = repository_rule(
    _run_tools_deps_impl,
    attrs = {"deps_edn": attr.label(allow_single_file = True),
             "aliases": attr.string_list(default = [], doc = "extra aliases in deps.edn to merge in while resolving deps")})
