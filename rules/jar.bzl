load("@rules_clojure//rules:common.bzl", "CljInfo")
load("@bazel_skylib//lib:paths.bzl", "paths")

def contains(lst, item):
    for x in lst:
        if x == item:
            return True
    return False

## This is annoyingly painful. It's the result of several constraints:
##  Bazel wants every output to be declared using either declare_file,
##  or declare_directory. We would like all source files to be in a
##  single directory, `src`, so it's easy to specify the classpath. It
##  would be nice if we could just use `declare_file` + `symlink`, but
##  that's not possible. If we declare_file both foo/bar.clj
##  foo/bbq.clj, bazel complains. Therefore, use
##  `declare_directory`. declare_directory means that bazel doesn't
##  pay attention to the contents of the directory. But if we declare
##  directory, only one action can create that directory, which means
##  the script to create the directory must also symlink all files
##  into place in the same go.

##
symlink_sh = """
#!/bin/bash
set -xeuo pipefail;

# the root directory
mkdir -p $1;
shift;

# pairs of src->dest symlinks
while (($#)); do
    src=$1
    dest=$2
    if ! [ -d $(dirname $dest) ]; then
	mkdir -p $(dirname $dest)
    fi
    cp $1 $2
    shift 2
done
"""

def clojure_jar_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    classes_dir = ctx.actions.declare_directory("%s.classes" % ctx.label.name) # .class files
    src_dir = ctx.actions.declare_directory("%s.srcs" % ctx.label.name)
    toolchain_dir = ctx.actions.declare_directory("%s.toolchain" % ctx.label.name)

    output_jar = ctx.actions.declare_file("%s.jar" % ctx.label.name)

    ctx.actions.run_shell(
        command = """
        mkdir -p {classes_dir};
""".format(classes_dir = classes_dir.path),
        outputs = [classes_dir])

    library_path = []

    input_file_map = {}
    for ns in ctx.attr.srcs:
        input_file_map.update(ns[CljInfo].srcs)
        input_file_map.update(ns[CljInfo].transitive_clj_srcs)

    for d in ctx.attr.deps:
        if CljInfo in dep:
            input_file_map.update(dep[CljInfo].srcs)
            input_file_map.update(dep[CljInfo].transitive_clj_srcs)

    symlink_args = ctx.actions.args()
    for infile,path in input_file_map.items():
        if path[0] != "/":
            fail("path must be absolute, got " + path)
        src_file = infile.files.to_list()[0]
        dest_file = src_dir.path + path
        symlink_args.add(src_file.path, dest_file)

    src_input_files = []
    for label in input_file_map.keys():
        src_input_files.extend(infile.files.to_list())

    print("input_files", src_input_files)
    print("ctx.files", ctx.files.srcs)

    ctx.actions.run_shell(
        command = symlink_sh,
        arguments = [src_dir.path, symlink_args],
        inputs = src_input_files,
        outputs = [src_dir])

    toolchain_symlink_args = ctx.actions.args()

    toolchain_file_map = {}

    for ns in ctx.attr._compiledeps:
        toolchain_file_map.update(ns[CljInfo].srcs)
        toolchain_file_map.update(ns[CljInfo].transitive_clj_srcs)

    for infile,path in toolchain_file_map.items():
        if path[0] != "/":
            fail("path must be absolute, got " + path)

        src_file = infile.files.to_list()[0]
        dest_file = toolchain_dir.path + path
        toolchain_symlink_args.add(src_file.path, dest_file)

    toolchain_input_files = []
    for label in toolchain_file_map.keys():
        toolchain_input_files.extend(label.files.to_list())

    ctx.actions.run_shell(
        command = symlink_sh,
        arguments = [toolchain_dir.path, toolchain_symlink_args],
        inputs = toolchain_input_files,
        outputs = [toolchain_dir])

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

    classpath_files = [src_dir, toolchain_dir] + toolchain.files.runtime + java_info.transitive_runtime_deps.to_list()
    classpath_string = ":".join([f.path for f in classpath_files])

    library_path_str = "-Djava.library.path=" + ":".join(library_path) if len(library_path) > 0 else ""

    cmd = """
        {java} -Dclojure.main.report=stderr -cp {classpath} {library_path_str} clojure.main -m rules-clojure.jar :input-dir '"{input_dir}"' :aot [{aot}] :classes-dir '"{classes_dir}"' :output-jar '"{output_jar}"'
    """.format(
        java = toolchain.java,
        src_dir = src_dir.path,
        toolchain_dir = toolchain_dir.path,
        classes_dir = classes_dir.path,
        classpath = classpath_string,
        input_dir = src_dir.path,
        output_jar = output_jar.path,
        library_path_str = library_path_str,
        script = toolchain.scripts["jar.clj"].path,
        aot = ",".join([ns for ns in ctx.attr.aot]))

    inputs = [classes_dir, src_dir, toolchain_dir] + toolchain.files.scripts + java_common.merge(java_deps).transitive_runtime_deps.to_list() + toolchain.files.jdk + native_libs

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
