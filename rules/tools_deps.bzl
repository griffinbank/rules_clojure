load("@rules_clojure//:rules.bzl", "clojure_library", "clojure_binary")

CLJ_VERSIONS_MAC = {
    "1.10.1.763": ("https://download.clojure.org/install/clojure-tools-1.10.1.763.tar.gz", "2a3ec8a6c3639035c2bba10945ae9007ab0dc9136766b95d2161f354e62a4d10")
}

CLJ_VERSIONS_LINUX = {
    "1.10.1.763": ("https://download.clojure.org/install/linux-install-1.10.1.763.sh", "91421551872d421915c4a598741aefcc6749d3f4aafca9c08f271958e5456e2c"),
    "1.10.2.774": ("https://download.clojure.org/install/linux-install-1.10.2.774.sh", "6d39603e84ad2622e5ae601436f02a1ee4a57e4e35dc49098b01a7d142a13d4a")
}

clj_install_prefix = "tools.deps"
clj_path = clj_install_prefix + "/bin/clojure"

def _install_clj_mac(repository_ctx):
    clj_version = repository_ctx.attr.clj_version

    url, sha256 = CLJ_VERSIONS_MAC[clj_version]

    repository_ctx.download_and_extract(
        auth = {},
        url = url,
        output = "tools-deps",
        sha256 = sha256)

    repository_ctx.execute(["mkdir", repository_ctx.path(clj_install_prefix)],
                           quiet = False)
    repository_ctx.execute(["./install.sh", repository_ctx.path(clj_install_prefix)],
                           # bazel strips the environment, but the install assumes this is defined
                           environment={"HOMEBREW_RUBY_PATH": "/usr/bin/ruby"},
                           working_directory="tools-deps/clojure-tools/",
                           quiet = False)

def _install_clj_linux(repository_ctx):
    clj_version = repository_ctx.attr.clj_version

    url, sha256 = CLJ_VERSIONS_LINUX[clj_version]

    repository_ctx.download(
        auth = {},
        url = url,
        output = "install.sh",
        executable = True,
        sha256 = sha256)

    repository_ctx.execute(["./install.sh", "--prefix", repository_ctx.path(clj_install_prefix)],
                           quiet = False)

def _install_tools_deps(repository_ctx):
    fns = {"linux": _install_clj_linux,
           "mac os x": _install_clj_mac}
    f = fns[repository_ctx.os.name]
    f(repository_ctx)

def _add_deps_edn(repository_ctx):
    # repository_ctx.delete(repository_ctx.path("deps.edn"))
    repository_ctx.symlink(
        repository_ctx.path(repository_ctx.attr.deps_edn),
        repository_ctx.path("deps.edn"))

def aliases_str(aliases):
    return str("[" + " ".join([ (":%s" % (a)) for a in aliases]) + "]")

def _run_gen_build(repository_ctx):
    home = repository_ctx.os.environ["HOME"]
    repository_ctx.symlink("/"+ home + "/.m2/repository", repository_ctx.path("repository"))

    repository_ctx.file(repository_ctx.path("scripts/BUILD"),
                        content = """
package(default_visibility = ["//visibility:public"])

sh_binary(name="gen_deps",
          srcs=["gen_deps.sh"])

sh_binary(name="gen_srcs",
          srcs=["gen_srcs.sh"])
""")

    repository_ctx.file(repository_ctx.path("scripts/gen_deps.sh"),
                        executable = True,
                        content = """#!/usr/bin/env bash
 set -euxo pipefail;
 cd {working_dir};
 {clojure} -Srepro -J-Dclojure.main.report=stderr -m rules-clojure.gen-build deps deps-edn-path "{deps_edn_path}" deps-out-dir "{deps_out_dir}" deps-build-dir "{deps_build_dir}" :deps-repo-tag "{deps_repo_tag}" aliases "{aliases}"

 """.format(clojure = repository_ctx.path(clj_path),
            deps_repo_tag = "@" + repository_ctx.attr.name,
            deps_edn_path = repository_ctx.path(repository_ctx.attr.deps_edn),
            deps_out_dir = repository_ctx.path("repository"),
            deps_build_dir = repository_ctx.path(""),
            working_dir = repository_ctx.path(repository_ctx.attr._rc_deps_edn).dirname,
            aliases = aliases_str(repository_ctx.attr.aliases)))

    repository_ctx.file(repository_ctx.path("scripts/gen_srcs.sh"),
                        executable = True,
                        content = """#!/usr/bin/env bash
 set -euxo pipefail;
 cd {working_dir};
 {clojure} -Srepro -J-Dclojure.main.report=stderr -m rules-clojure.gen-build srcs deps-edn-path "{deps_edn_path}" deps-out-dir "{deps_out_dir}" deps-build-dir "{deps_build_dir}" :deps-repo-tag "{deps_repo_tag}" aliases "{aliases}" workspace-root ${{BUILD_WORKSPACE_DIRECTORY}}

 """.format(clojure = repository_ctx.path(clj_path),
            deps_repo_tag = "@" + repository_ctx.attr.name,
            deps_edn_path = repository_ctx.path(repository_ctx.attr.deps_edn),
            deps_out_dir = repository_ctx.path("repository"),
            deps_build_dir = repository_ctx.path(""),
            working_dir = repository_ctx.path(repository_ctx.attr._rc_deps_edn).dirname,
            aliases = aliases_str(repository_ctx.attr.aliases)))

    repository_ctx.execute(["scripts/gen_deps.sh", "deps"],
                           quiet = False)

def _tools_deps_impl(repository_ctx):
    _install_tools_deps(repository_ctx)
    _add_deps_edn(repository_ctx)
    _run_gen_build(repository_ctx)

clojure_tools_deps = repository_rule(
    _tools_deps_impl,
    attrs = {"deps_edn": attr.label(allow_single_file = True),
             "clj_version": attr.string(default = "1.10.1.763"),
             "aliases": attr.string_list(default = [], doc = "extra aliases in deps.edn to merge in while resolving deps"),
             "_rc_deps_edn": attr.label(default = "@rules_clojure//:deps.edn"),
             "_jdk": attr.label(default = "@bazel_tools//tools/jdk:current_java_runtime",
                                providers = [java_common.JavaRuntimeInfo])})

def clojure_gen_deps(name, deps_edn, aliases, deps_repo_tag):
    native.sh_binary(name=name,
                     srcs=["@deps//scripts:gen_deps.sh"])

def clojure_gen_srcs(name, deps_edn, aliases, deps_repo_tag):
    native.sh_binary(name=name,
              srcs=["@deps//scripts:gen_srcs.sh"])
