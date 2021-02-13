load("@rules_clojure//rules:common.bzl", "CljInfo")

def contains(lst, item):
    for x in lst:
        if x == item:
            return True
    return False

def clojure_jar_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    classes_dir = ctx.actions.declare_directory("%s.classes" % ctx.label.name) # .class files

    output_jar = ctx.actions.declare_file("%s.jar" % ctx.label.name)

    src_dir = "src" # all source files will be symlinked under here
    toolchain_dir = "toolchain" # compile dependencies, e.g. jar.clj under here

    ctx.actions.run_shell(
        command = """
        mkdir -p {classes_dir};

""".format(classes_dir = classes_dir.path),
        outputs = [classes_dir])

    input_files = [classes_dir]

    library_path = []

    input_file_map = {}
    for ns in ctx.attr.srcs:
        input_file_map.update(ns[CljInfo].srcs)
        input_file_map.update(ns[CljInfo].transitive_clj_srcs)

    for d in ctx.attr.deps:
        if CljInfo in dep:
            input_file_map.update(dep[CljInfo].srcs)
            input_file_map.update(dep[CljInfo].transitive_clj_srcs)

    for infile,path in input_file_map.items():
        if path[0] != "/":
            fail("path must be absolute, got " + path)
        dest = ctx.actions.declare_file(src_dir + path)
        ctx.actions.symlink(output=dest,target_file = infile.files.to_list()[0])
        input_files.append(dest)

    toolchain_file_map = {}
    for ns in ctx.attr._compiledeps:
        toolchain_file_map.update(ns[CljInfo].srcs)
        toolchain_file_map.update(ns[CljInfo].transitive_clj_srcs)

    for infile,path in toolchain_file_map.items():
        if path[0] != "/":
            fail("path must be absolute, got " + path)
        dest = ctx.actions.declare_file(toolchain_dir + path)
        ctx.actions.symlink(output=dest,target_file = infile.files.to_list()[0])
        input_files.append(dest)

    runfiles = ctx.runfiles()

    for dep in ctx.attr.srcs + ctx.attr.deps:
        if DefaultInfo in dep:
            runfiles = runfiles.merge(dep[DefaultInfo].default_runfiles)
            runfiles = runfiles.merge(dep[DefaultInfo].data_runfiles)

    java_deps = []

    for dep in toolchain.runtime + ctx.attr.srcs + ctx.attr.deps:
        if CljInfo in dep:
            java_deps.extend(dep[CljInfo].transitive_java_deps)

        if JavaInfo in dep:
            java_deps.append(dep[JavaInfo])

    java_info = JavaInfo(
        output_jar = output_jar,
        compile_jar = output_jar,
        source_jar = None,
        deps = java_deps)

    native_libs = []
    for f in runfiles.files.to_list():
        ## Bazel on mac sticks weird looking directories in runfiles, like _solib_darwin/_U_S_Snative_C_Ulibsodium___Unative_Slibsodium_Slib. filter them out
        if (f.path.endswith(".dylib") or f.path.endswith(".so")) and (f.path.rfind("solib_darwin") == -1):
            native_libs.append(f)

    for f in native_libs:
        dirname = f.dirname
        if not contains(library_path, dirname):
            library_path.append(dirname)

    # it's a little silly, but if we `declare_file("src/a/b/c.clj"),
    # there's no clean way to recover `src`, so determine the root
    # from `classes_dir`, and use that
    dir_root = classes_dir.dirname
    src_dir = dir_root + "/" + src_dir
    toolchain_dir = dir_root + "/" + toolchain_dir
    classpath_files = toolchain.files.runtime + java_info.transitive_runtime_deps.to_list()
    classpath_string = ":".join([src_dir, toolchain_dir] + [f.path for f in classpath_files])

    library_path_str = "-Djava.library.path=" + ":".join(library_path) if len(library_path) > 0 else ""

    cmd = """
        {java} -Dclojure.main.report=stderr -cp {classpath} {library_path_str} clojure.main -m rules-clojure.jar :input-dir '"{input_dir}"' :aot [{aot}] :classes-dir '"{classes_dir}"' :output-jar '"{output_jar}"'
    """.format(
        java = toolchain.java,
        classes_dir = classes_dir.path,
        classpath = classpath_string,
        input_dir = src_dir,
        output_jar = output_jar.path,
        library_path_str = library_path_str,
        script = toolchain.scripts["jar.clj"].path,
        aot = ",".join([ns for ns in ctx.attr.aot]))

    inputs = input_files + toolchain.files.scripts + java_common.merge(java_deps).transitive_runtime_deps.to_list() + toolchain.files.jdk + native_libs

    ctx.actions.run_shell(
        outputs = [output_jar],
        command = cmd,
        inputs = inputs,
        mnemonic = "ClojureJar",
        progress_message = "Compiling %s" % ctx.label)

    return [
        DefaultInfo(
            files = depset([output_jar]),
            runfiles = runfiles,
        ),
        java_info
    ]
