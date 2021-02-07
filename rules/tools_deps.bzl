
CLJ_VERSIONS = {
    "1.10.1.763": ("https://download.clojure.org/install/clojure-tools-1.10.1.763.tar.gz", "2a3ec8a6c3639035c2bba10945ae9007ab0dc9136766b95d2161f354e62a4d10")
}

CLJ_EXTRACT_DIR = "bin/clj"

def _download_clj(repository_ctx):
    clj_version = repository_ctx.attr.clj_version

    url, sha256 = CLJ_VERSIONS[clj_version]

    repository_ctx.download_and_extract(
        auth = {},
        url = url,
        output = CLJ_EXTRACT_DIR,
        stripPrefix = "clojure-tools",
        sha256 = sha256)

def _install_clj(repository_ctx):
    _download_clj(repository_ctx)

def _add_deps_edn(repository_ctx):
    repository_ctx.symlink(
        repository_ctx.path(repository_ctx.attr.deps_edn),
        repository_ctx.path("deps.edn"))

def _add_gen_scripts(repository_ctx):
    repository_ctx.execute(["bash", "-c", """'ln -s ~/.m2/repository {dest}""".format(dest=repository_ctx.path("repository"))])

    repository_ctx.file(repository_ctx.path("scripts/gen_deps.sh"),
                        content = """#!/usr/bin/env bash
set -euxo pipefail;
cd {working_dir};

{clojure} -Srepro -J-Dclojure.main.report=stderr -X gen-build/deps :deps-edn-path '"{deps_edn_path}"' :deps-out-dir '"{deps_out_dir}"' :deps-build-dir '"{deps_build_dir}"' :deps-repo-tag '"{deps_repo_tag}"' :aliases '{aliases}'

""".format(clojure = repository_ctx.path("bin/clj/clojure"),
           deps_repo_tag = "@" + repository_ctx.attr.name,
           deps_edn_path = repository_ctx.path(repository_ctx.attr.deps_edn),
           deps_out_dir = repository_ctx.path("repository"),
           deps_build_dir = repository_ctx.path(""),
           working_dir = repository_ctx.path(repository_ctx.attr._scripts).dirname,
           aliases = repository_ctx.attr.aliases),
                        executable = True)

    repository_ctx.file(repository_ctx.path("scripts/gen_srcs.sh"),
                        content = """#!/usr/bin/env bash
set -euxo pipefail;
cd external/rules_clojure/scripts;
{clojure} -Srepro -J-Dclojure.main.report=stderr -X gen-build/srcs :deps-edn-path '"{deps_edn_path}"' :deps-out-dir '"{deps_out_dir}"' :deps-repo-tag '"{deps_repo_tag}"' :aliases '{aliases}'

""".format(clojure = repository_ctx.path("bin/clj/clojure"),
           deps_repo_tag = "@" + repository_ctx.attr.name,
           deps_edn_path = repository_ctx.path(repository_ctx.attr.deps_edn),
           deps_out_dir = repository_ctx.path("repository"),
           deps_build_dir = repository_ctx.path(""),
           aliases = repository_ctx.attr.aliases),
                        executable = True)
    repository_ctx.file("scripts/BUILD", content="""
package(default_visibility = ["//visibility:public"])

sh_binary(name="gen_srcs",
          srcs=["gen_srcs.sh"],
          data=["@rules_clojure//scripts"])""")


def _run_tools_deps_impl(repository_ctx):
    _install_clj(repository_ctx)
    _add_deps_edn(repository_ctx)
    _add_gen_scripts(repository_ctx)
    repository_ctx.execute(["scripts/gen_deps.sh"],
                           quiet = False)


clojure_tools_deps = repository_rule(
    _run_tools_deps_impl,
    attrs = {"deps_edn": attr.label(allow_single_file = True),
             "clj_version": attr.string(default = "1.10.1.763"),
             "aliases": attr.string_list(default = [], doc = "extra aliases in deps.edn to merge in while resolving deps"),
             "_scripts": attr.label(default = "@rules_clojure//scripts:deps.edn")})
