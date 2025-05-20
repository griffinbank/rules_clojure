load("//:rules.bzl", "clojure_library", "clojure_binary")

CLJ_VERSIONS_MAC = {
    "1.10.3.1087": ("https://download.clojure.org/install/clojure-tools-1.10.3.1087.tar.gz", "a6b3b3547adc6da6ca5cfe10e037f1fde88a78f948372bb598ef4d0859da3e94"),
    "1.11.1.1347": ("https://download.clojure.org/install/clojure-tools-1.11.1.1347.tar.gz", "d5e6c03e4eb8b49b7f0a9b77a4a7cc4cde7460004a3df96a1b4e797f842ebfe3")
}

CLJ_VERSIONS_LINUX = {
    "1.10.3.1087": ("https://download.clojure.org/install/linux-install-1.10.3.1087.sh", "fd3d465ac30095157ce754f1551b840008a6e3503ce5023d042d0490f7bafb98"),
    "1.11.1.1347": ("https://download.clojure.org/install/linux-install-1.11.1.1347.sh", "73a780bac41fc43ac624973f4f6ac4e46f293fe25aa43636b477bcc9ce2875de")
}

clj_install_prefix = "tools.deps"
clj_path = clj_install_prefix + "/bin/clojure"

def _install_clj_mac(repository_ctx):
    clj_version = repository_ctx.attr.clj_version

    url, sha256 = CLJ_VERSIONS_MAC[clj_version]

    repository_ctx.download_and_extract(
        auth = {},
        url = url,
        stripPrefix = "clojure-tools",
        output = "tools.deps",
        sha256 = sha256)

    repository_ctx.execute(["mkdir", repository_ctx.path(clj_install_prefix)],
                           quiet = False)
    ret = repository_ctx.execute(["./install.sh", repository_ctx.path(clj_install_prefix)],
                           # bazel strips the environment, but the install assumes this is defined
                           environment={"HOMEBREW_RUBY_PATH": "/usr/bin/ruby"},
                           working_directory="tools.deps/",
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

def _install_scripts(repository_ctx):
    repository_ctx.file(repository_ctx.path("scripts/BUILD.bazel"),
                        executable = True,
                        content = """
package(default_visibility = ["//visibility:public"])

java_binary(name="gen_srcs",
    main_class="rules_clojure.gen_build",
    runtime_deps=["@rules_clojure//src/rules_clojure:libgen_build"],
    args=["srcs",
          ":deps-edn-path", "{deps_edn_path}",
          ":repository-dir", "{repository_dir}",
          ":deps-build-dir", "{deps_build_dir}",
          ":deps-repo-tag", "{deps_repo_tag}",
          ":aliases", "\\"{aliases}\\""],
    data=["{deps_edn_label}"])

 """.format(deps_repo_tag = "@" + repository_ctx.attr.name,
            deps_edn_label = repository_ctx.attr.deps_edn,
            deps_edn_path = repository_ctx.path(repository_ctx.attr.deps_edn),
            repository_dir = repository_ctx.path("repository"),
            deps_build_dir = repository_ctx.path(""),
            aliases = aliases_str(repository_ctx.attr.aliases)))

def _symlink_repository(repository_ctx):
    repository_ctx.symlink(repository_ctx.os.environ["HOME"] + "/.m2/repository", repository_ctx.path("repository"))

def _run_gen_build(repository_ctx):
    args = [repository_ctx.path("tools.deps/bin/clojure"),
            "-Srepro",
            "-Sdeps", """{:paths ["%s", "%s"]
            :deps {org.clojure/tools.namespace {:mvn/version "1.1.0"}
            org.clojure/tools.deps.alpha {:mvn/version "0.14.1178"}}}""" % (repository_ctx.path("../rules_clojure/src"),
                                                                            repository_ctx.path("../rules_clojure~/src")),

            "-J-Dclojure.main.report=stderr",
            "-M",
            "-m", "rules-clojure.gen-build",
            "deps",
            ":deps-edn-path", repository_ctx.path(repository_ctx.attr.deps_edn),
            ":repository-dir", repository_ctx.path("repository/"),
            ":deps-build-dir", repository_ctx.path(""),
            ":deps-repo-tag", "@" + repository_ctx.attr.name,
            ":workspace-root", repository_ctx.attr.deps_edn.workspace_root,
            ":aliases", aliases_str(repository_ctx.attr.aliases)]
    ret = repository_ctx.execute(args, quiet=False, environment=repository_ctx.attr.env)
    if ret.return_code > 0:
        fail("gen build failed:", ret.return_code, ret.stdout, ret.stderr)

def _tools_deps_impl(repository_ctx):
    _install_tools_deps(repository_ctx)
    _add_deps_edn(repository_ctx)
    _symlink_repository(repository_ctx)
    _install_scripts(repository_ctx)
    _run_gen_build(repository_ctx)
    return None

clojure_tools_deps = repository_rule(
    _tools_deps_impl,
    attrs = {"deps_edn": attr.label(allow_single_file = True),
             "aliases": attr.string_list(default = [], doc = "extra aliases in deps.edn to merge in while resolving deps"),
             "clj_version": attr.string(default="1.11.1.1347"),
             "env": attr.string_dict(default = {})})

def clojure_gen_srcs(name):
    native.alias(name=name,
                 actual= "@deps//scripts:gen_srcs")

def clojure_gen_namespace_loader(name, output_filename, output_ns_name, output_fn_name, in_dirs, exclude_nses, platform, deps_edn):
    native.java_binary(name=name,
                       runtime_deps=["@rules_clojure//src/rules_clojure:libgen_build"],
                       data=[deps_edn],
                       main_class="rules_clojure.gen_build",
                       args=["ns-loader",
                           ":output-filename", output_filename,
                           ":output-ns-name", output_ns_name,
                           ":output-fn-name", output_fn_name,
                           ":in-dirs", "[%s]" % " ".join(["\\\"%s\\\"" % d for d in in_dirs]),
                           ":exclude-nses", "'[%s]'" % " ".join(exclude_nses),
                           ":platform", platform])
