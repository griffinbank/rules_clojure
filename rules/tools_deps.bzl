load("//:rules.bzl", "clojure_library", "clojure_binary")

def _add_deps_edn(repository_ctx):
    # repository_ctx.delete(repository_ctx.path("deps.edn"))
    repository_ctx.symlink(
        repository_ctx.path(repository_ctx.attr.deps_edn),
        repository_ctx.path("deps.edn"))

def aliases_str(aliases):
    return str("[" + " ".join([ (":%s" % (a)) for a in aliases]) + "]")

def _run_gen_build(repository_ctx):
    print("gen-build repository:", repository_ctx.path("repository"))
    repository_ctx.file(repository_ctx.path("scripts/BUILD.bazel"),
                        executable = True,
                        content = """
package(default_visibility = ["//visibility:public"])

java_binary(name="gen_deps",
    main_class="rules_clojure.gen_build",
    runtime_deps=["@rules_clojure//src/rules_clojure:libgen_build"],
    args=["deps",
          ":deps-edn-path", "{deps_edn_path}",
          ":repository-dir", "{repository_dir}",
          ":deps-build-dir", "{deps_build_dir}",
          ":deps-repo-tag", "{deps_repo_tag}",
          ":aliases", "\\"{aliases}\\""],
    data=["{deps_edn_label}"])

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
            working_dir = repository_ctx.path(repository_ctx.attr._rc_deps_edn).dirname,
            aliases = aliases_str(repository_ctx.attr.aliases)))

def _tools_deps_impl(repository_ctx):
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

def clojure_gen_deps(name):
    native.alias(name=name,
                 actual= "@deps//scripts:gen_deps")

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
                           ":exclude-nses", "[%s]" % " ".join(exclude_nses),
                           ":platform", platform])
