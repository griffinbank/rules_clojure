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

def paths(resources, resource_strip_prefix):
    """Return a list of path tuples (target, source) where:
        target - is a path in the archive (with given prefix stripped off)
        source - is an absolute path of the resource file

    Tuple ordering is aligned with zipper format ie zip_path=file

    Args:
        resources: list of file objects
        resource_strip_prefix: string to strip from resource path
    """
    return [(_target_path(resource, resource_strip_prefix), resource.path) for resource in resources]

def _strip_prefix(path, prefix):
    return path[len(prefix):] if path.startswith(prefix) else path

def _target_path(resource, resource_strip_prefix):
    path = _target_path_by_strip_prefix(resource, resource_strip_prefix) if resource_strip_prefix else _target_path_by_default_prefixes(resource)
    return _strip_prefix(path, "/")

def _target_path_by_strip_prefix(resource, resource_strip_prefix):
    # Start from absolute resource path and then strip roots so we get to correct short path
    # resource.short_path sometimes give weird results ie '../' prefix
    path = resource.path
    if resource_strip_prefix != resource.owner.workspace_root:
        path = _strip_prefix(path, resource.owner.workspace_root + "/")
    path = _strip_prefix(path, resource.root.path + "/")

    # proto_library translates strip_import_prefix to proto_source_root which includes root so we have to strip it
    prefix = _strip_prefix(resource_strip_prefix, resource.root.path + "/")
    if not path.startswith(prefix):
        fail("Resource file %s is not under the specified prefix %s to strip" % (path, prefix))
    return path[len(prefix):]

def _target_path_by_default_prefixes(resource):
    path = resource.path

    #  Here we are looking to find out the offset of this resource inside
    #  any resources folder. We want to return the root to the resources folder
    #  and then the sub path inside it
    dir_1, dir_2, rel_path = path.partition("resources")
    if rel_path:
        return rel_path

    #  The same as the above but just looking for java
    (dir_1, dir_2, rel_path) = path.partition("java")
    if rel_path:
        return rel_path

    # Both short_path and path have quirks we wish to avoid, in short_path there are times where
    # it is prefixed by `../` instead of `external/`. And in .path it will instead return the entire
    # bazel-out/... path, which is also wanting to be avoided. So instead, we return the short-path if
    # path starts with bazel-out and the entire path if it does not.
    return resource.short_path if path.startswith("bazel-out") else path

def restore_prefix(src, stripped):
    """opposite of _target_path. Given a source and stripped file, return the prefix """
    if src.path.endswith(stripped):
        return src.path[:len(src.path)-len(stripped)]
    else:
        fail("Resource file %s is not under the specified prefix %s to strip" % (src, stripped))

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

    if len(input_files):
        src_dir = restore_prefix(input_files[0], _target_path(input_files[0], ctx.attr.resource_strip_prefix))
    else:
        src_dir = None

    compile_classpath = compile_info.transitive_runtime_jars.to_list() + ctx.files.compiledeps + [classes_dir]
    compile_classpath = [f.path for f in compile_classpath]
    compile_classpath = compile_classpath + [p for p in [src_dir] if p]

    native_libs = []
    for f in runfiles.files.to_list():
        ## Bazel on mac sticks weird looking directories in runfiles, like _solib_darwin/_U_S_Snative_C_Ulibsodium___Unative_Slibsodium_Slib. filter them out
        if (f.path.endswith(".dylib") or f.path.endswith(".so")) and (f.path.rfind("solib_darwin") == -1):
            native_libs.append(f)

    aot_nses = distinct(aot_nses)

    javaopts_str = " ".join(ctx.attr.javacopts)

    compile_args = {"classes-dir": classes_dir.path,
                    "output-jar": output_jar.path,
                    "src-dir": src_dir,
                    "srcs": [_target_path(s, ctx.attr.resource_strip_prefix) for s in ctx.files.srcs],
                    "resources": [_target_path(s, ctx.attr.resource_strip_prefix) for s in ctx.files.resources],
                    "aot-nses": aot_nses,
                    "classpath": compile_classpath}

    args_file = ctx.actions.declare_file(argsfile_name(ctx.label))
    ctx.actions.write(
        output = args_file,
        content = json.encode(compile_args))

    inputs = ctx.files.srcs + ctx.files.resources + compile_info.transitive_runtime_jars.to_list() + native_libs + [args_file] + worker_classpath_depset.to_list()

    worker_classpath_str = ":".join([d.path for d in worker_classpath_depset.to_list()])

    ctx.actions.run(
        executable= ctx.executable._clojureworker_binary,
        arguments=
        ["--jvm_flags=" + f for f in ctx.attr.jvm_flags] +
        ["-m", "rules-clojure.worker",
         "@%s" % args_file.path],
        outputs = [output_jar, classes_dir],
        inputs = inputs,
        mnemonic = "ClojureCompile",
        progress_message = "Compiling %s" % ctx.label,
        execution_requirements={"supports-workers": "1",
                                "supports-multiplex-workers": "1",
                                "requires-worker-protocol": "json"})

    return [
        default_info,
        java_info
    ]
