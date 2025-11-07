def contains(lst, item):
    for x in lst:
        if x == item:
            return True
    return False

def distinct(lst):
    d = {}
    for i in lst:
        d[i] = True
    return d.keys()

def argsfile_name(label):
    return str(label).replace("@","_").replace("/","_") + "_args"

def printable_label(label):
    return "%s.%s" % (label.package.replace("/","_").replace("@",""),
                      label.name)

def clojure_jar_impl(ctx):

    if (len(ctx.attr.srcs) > 0 and len(ctx.attr.aot) == 0):
        fail("srcs but no AOT")

    output_jar = ctx.actions.declare_file("%s.jar" % ctx.label.name)
    classes_dir = ctx.actions.declare_directory("%s.classes" % (printable_label(ctx.label)))

    library_path = []

    compile_deps = []
    runtime_deps = []

    runfiles = ctx.runfiles(files = ctx.files.data)

    worker_classpath_depset = depset(transitive=[d[JavaInfo].transitive_runtime_jars for d in ctx.attr._libworker])

    for dep in ctx.attr.srcs + ctx.attr.deps + ctx.attr.data + ctx.attr.compiledeps + ctx.attr._libcompile:
        if JavaInfo in dep:
            compile_deps.append(dep[JavaInfo])

    for dep in ctx.attr.deps + ctx.attr.runtime_deps:
        runfiles = runfiles.merge(dep[DefaultInfo].default_runfiles)
        if JavaInfo in dep:
            runtime_deps.append(dep[JavaInfo])

    compile_info = java_common.merge(compile_deps)

    java_info = JavaInfo(
        output_jar = output_jar,
        compile_jar = output_jar,
        source_jar = None,
        deps = compile_deps,
        runtime_deps = runtime_deps)

    default_info = DefaultInfo(
        files = depset([output_jar]),
        runfiles = runfiles)

    aot_nses = list(ctx.attr.aot)

    input_files = ctx.files.srcs + ctx.files.resources

    compile_classpath = depset(
        ctx.files.compiledeps + [classes_dir],
        transitive = [compile_info.transitive_runtime_jars],
    )

    native_libs = []

    aot_nses = distinct(aot_nses)

    javaopts_str = " ".join(ctx.attr.javacopts)

    compile_args = ctx.actions.args()
    compile_args.use_param_file("@%s", use_always = True)

    compile_args.add_all([classes_dir], before_each = "--classes-dir", expand_directories = False)
    compile_args.add_all([output_jar], before_each = "--output-jar", expand_directories = False)

    if ctx.attr.resource_strip_prefix != "":
        compile_args.add("--resource-strip-prefix")
        compile_args.add(ctx.attr.resource_strip_prefix)

    compile_args.add_all(ctx.files.srcs, before_each="--src")
    compile_args.add_all(ctx.files.resources, before_each="--resource")
    compile_args.add_all(aot_nses, before_each="--aot-nses")
    compile_args.add("--classpath")
    compile_args.add_joined(compile_classpath, join_with=":")

    inputs = depset(
        ctx.files.srcs + ctx.files.resources +  native_libs,
        transitive = [compile_info.transitive_runtime_jars, worker_classpath_depset],
    )

    ctx.actions.run(
        executable= ctx.executable._clojureworker_binary,
        arguments=
        ["--jvm_flags=" + f for f in ctx.attr.jvm_flags] +
        ["-m", "rules-clojure.worker"] + [compile_args],
        outputs = [output_jar, classes_dir],
        inputs = inputs,
        mnemonic = "ClojureCompile",
        progress_message = "Compiling %s" % ctx.label,
        execution_requirements={"supports-workers": "1",
                                "supports-multiplex-workers": "1",
                                "requires-worker-protocol": "json",
                                "supports-path-mapping": "1"})

    return [
        default_info,
        java_info
    ]
