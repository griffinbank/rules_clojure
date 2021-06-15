load("//rules:jar.bzl", _clojure_jar_impl = "clojure_jar_impl")

def _clojure_aot_annotation_impl(ctx):
    ctx.actions.expand_template(
        template = ctx.file._template,
        output = ctx.outputs.dest,
        substitutions = {
            "{ns}": ctx.attr.ns,
        })

_clojure_aot_annotation = rule(
    attrs= {
        "ns": attr.string(),
        "dest": attr.output(),
        "_template": attr.label(default="//java/rules_clojure:aot_template.java", allow_single_file=True)
    },
    implementation = _clojure_aot_annotation_impl)

def clojure_library(name, **kwargs):
    aot = kwargs.get("aot",[])
    if "aot" in kwargs:
        kwargs.pop("aot")

    if len(aot) > 0:
        preaot = "%s.preaot" % name
        plugin_classpath = "%s.plugin_classpath" % name
        aot_anns = []
        native.java_library(name=preaot,
                            **kwargs)
        for ns in aot:
            aot_ann = ":%s/package-info.java" % name
            aot_anns.append(aot_ann)
            _clojure_aot_annotation(name="%s.gen_annotate" % name,
                                    dest=aot_ann,
                                    ns=ns)
        # use this plugin to add lib source to the plugin classpath
        native.java_plugin(name=plugin_classpath,
                           deps=[preaot,
                                 "@rules_clojure//java/tools/aot"])
        native.java_library(name=name,
                            srcs=aot_anns,
                            plugins=[plugin_classpath],
                            deps=[preaot,
                                  "@rules_clojure//java/tools/aot"])
    else:
        native.java_library(name=name,
                            **kwargs)

def clojure_binary(name, **kwargs):
    deps = []
    runtime_deps = []
    if "deps" in kwargs:
        deps = kwargs["deps"]
        kwargs.pop("deps")

    if "runtime_deps" in kwargs:
        runtime_deps = kwargs["runtime_deps"]
        kwargs.pop("runtime_deps")

    native.java_binary(name=name,
                       runtime_deps = deps + runtime_deps,
                       **kwargs)

def clojure_repl(name, deps=[], ns=None, **kwargs):
    args = []

    if ns:
        args.extend(["-e", """\"(require '[{ns}]) (in-ns '{ns})\"""".format(ns = ns)])

    args.extend(["-e", "(clojure.main/repl)"])

    native.java_binary(name=name,
                       runtime_deps=deps,
                       jvm_flags=["-Dclojure.main.report=stderr"],
                       main_class = "clojure.main",
                       args = args,
                       **kwargs)

def clojure_test(name, *, test_ns, runtime_deps=[], **kwargs):
    # ideally the library name and the bin name would be the same. They can't be.
    # clojure src files would like to depend on `foo_test`, so mangle the test binary, not the src jar name

    native.java_test(name=name,
                     runtime_deps = runtime_deps + ["@rules_clojure//src/rules_clojure:testrunner"],
                     use_testrunner = False,
                     main_class="clojure.main",
                     jvm_flags=["-Dclojure.main.report=stderr"],
                     args = ["-m", "rules_clojure.testrunner", test_ns],
                     **kwargs)
