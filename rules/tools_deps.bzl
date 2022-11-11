load("//:rules.bzl", "clojure_library", "clojure_binary")

CLJ_VERSIONS_MAC = {
    "1.10.1.763": ("https://download.clojure.org/install/clojure-tools-1.10.1.763.tar.gz", "2a3ec8a6c3639035c2bba10945ae9007ab0dc9136766b95d2161f354e62a4d10"),
    "1.11.1.1113": ("https://download.clojure.org/install/clojure-tools-1.11.1.1113.tar.gz", "0c954a56a071f33b9e039f8ab905f8372a5a601a0d14a32e0ccf230ea7606a22")
}

CLJ_VERSIONS_LINUX = {
    "1.10.1.763": ("https://download.clojure.org/install/linux-install-1.10.1.763.sh", "91421551872d421915c4a598741aefcc6749d3f4aafca9c08f271958e5456e2c"),
    "1.10.2.774": ("https://download.clojure.org/install/linux-install-1.10.2.774.sh", "6d39603e84ad2622e5ae601436f02a1ee4a57e4e35dc49098b01a7d142a13d4a"),
    "1.11.1.1113": ("https://download.clojure.org/install/linux-install-1.11.1.1113.sh", "7677bb1179ebb15ebf954a87bd1078f1c547673d946dadafd23ece8cd61f5a9f")
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

# This should be kept in line with the deps.edn for this actual repo
INSTALL_DEPS_EDN_TEMPLATE = """
{:paths ["%s"]
 :deps {org.clojure/clojure {:mvn/version "1.11.1" :exclusions #{org.clojure/spec.alpha
                                                                 org.clojure/core.specs.alpha}}
        org.clojure/spec.alpha {:mvn/version "0.3.218" :exclusions #{org.clojure/clojure}}
        org.clojure/core.specs.alpha {:mvn/version "0.2.62" :exclusions #{org.clojure/clojure
                                                                          org.clojure/spec.alpha}}
        org.clojure/data.json {:mvn/version "2.4.0"}
        org.clojure/java.classpath {:mvn/version "1.0.0"}
        org.clojure/tools.deps.alpha {:mvn/version "0.14.1212"}
        org.clojure/tools.namespace {:mvn/version "1.1.0"}
        org.projectodd.shimdandy/shimdandy-api {:mvn/version "1.2.1"}
        org.projectodd.shimdandy/shimdandy-impl {:mvn/version "1.2.1"}}}
"""

RUN_DEPS_EDN_TEMPLATE = """
{:paths ["%s"]
 :deps {org.clojure/tools.deps.alpha {:mvn/version "0.14.1212"}
        org.clojure/tools.namespace {:mvn/version "1.1.0"}}}
"""

def _install_base_deps(repository_ctx):
    args = [repository_ctx.path("tools.deps/bin/clojure"),
            "-Sdeps"
            INSTALL_DEPS_EDN_TEMPLATE % repository_ctx.path("../rules_clojure/src"),
            "-X:deps"
            "list"]
    ret = repository_ctx.execute(args, quiet=False)
    if ret.return_code > 0:
        fail("install of toolchain deps failed:", ret.return_code, ret.stdout, ret.stderr)

def _add_deps_edn(repository_ctx):
    repository_ctx.symlink(
        repository_ctx.path(repository_ctx.attr.deps_edn),
        repository_ctx.path("deps.edn"))

def _run_gen_build(repository_ctx):
    args = [repository_ctx.path("tools.deps/bin/clojure"),
            "-Srepro",
            "-Sdeps",
            RUN_DEPS_EDN_TEMPLATE % repository_ctx.path("../rules_clojure/src"),
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
    ret = repository_ctx.execute(args, quiet=False)
    if ret.return_code > 0:
        fail("gen build failed:", ret.return_code, ret.stdout, ret.stderr)

def _tools_deps_impl(repository_ctx):
    _install_tools_deps(repository_ctx)
    _symlink_repository(repository_ctx)
    _install_base_deps(repository_ctx)
    _add_deps_edn(repository_ctx)
    _install_scripts(repository_ctx)
    _run_gen_build(repository_ctx)
    return None

clojure_tools_deps = repository_rule(
    _tools_deps_impl,
    attrs = {"deps_edn": attr.label(allow_single_file = True),
             "aliases": attr.string_list(default = [], doc = "extra aliases in deps.edn to merge in while resolving deps"),
             "clj_version": attr.string(default="1.11.1.1113"),
             "_rules_clj_deps": attr.label(default="@rules_clojure//:deps.edn"),
             "_rules_clj_src": attr.label(default="@rules_clojure//:src")})

def clojure_gen_srcs(name):
    native.alias(name=name,
                 actual="@deps//scripts:gen_srcs")

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
