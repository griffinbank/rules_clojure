load("@rules_clojure//rules:library.bzl", "CljInfo")

def contains(lst, item):
    for x in lst:
        if x == item:
            return True
    return False

def clojure_jar_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    classes = ctx.actions.declare_directory("%s.classes" % ctx.label.name)

    output_jar = ctx.actions.declare_file("%s.jar" % ctx.label.name)

    input_files = ctx.files.srcs + ctx.files.resources

    library_path = []

    for src in ctx.attr.srcs:
        if CljInfo in src:
            input_files.append(src[CljInfo].transitive_srcs)

    runfiles = ctx.runfiles()
    # TODO fix hack
    src = ctx.bin_dir.path + "/src"

    for dep in ctx.attr.srcs + ctx.attr.deps:
        if DefaultInfo in dep:
            runfiles = runfiles.merge(dep[DefaultInfo].default_runfiles)
            runfiles = runfiles.merge(dep[DefaultInfo].data_runfiles)

    java_deps = []

    for dep in toolchain.runtime + ctx.attr.srcs + ctx.attr.deps:
        if CljInfo in dep:
            java_deps.extend(dep[CljInfo].java_deps)
            for ts in dep[CljInfo].transitive_srcs:
                java_deps.extend(ts[CljInfo].java_deps)

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

    classpath_files = toolchain.files.runtime + [classes] + java_info.transitive_runtime_deps.to_list()
    classpath_string = ":".join(["src", "test"] + [f.path for f in classpath_files])

    cmd = """
        set -e;
        mkdir -p {classes};
        {java} -Dclojure.main.report=stderr -cp {classpath} -Dclojure.compile.path={classes} -Dbazel.jar.input-files={input_files} -Dbazel.jar.output-jar={output_jar} -Djava.library.path={library_path} -Dbazel.jar.aot={aot} clojure.main {script}
    """.format(
        java = toolchain.java,
        classes = classes.path,
        src = src,
        # TODO fix hardcoded 'src'
        # TODO toolchain.files causes duplicate clojure.jars. depset?
        classpath = classpath_string,
        input_files = ",".join([f.path for f in input_files]),
        output_jar = output_jar.path,
        library_path = ":".join(library_path),
        script = toolchain.scripts["jar.clj"].path,
        aot = ",".join([ns for ns in ctx.attr.aot]))

    inputs = input_files + java_common.merge(java_deps).transitive_runtime_deps.to_list() + toolchain.files.scripts + toolchain.files.jdk + native_libs

    ctx.actions.run_shell(
        outputs = [output_jar, classes],
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
