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
##  single directory, `src`, so it's easy to specify the classpath,
##  and easy to create jars (just zip up everything under `src`). It
##  would be nice if we could just use `declare_file` + `symlink`, but
##  that's not possible. If we declare_file both foo/bar.clj
##  foo/bbq.clj, bazel complains about one being a prefix of the
##  other. Therefore, use `declare_directory`. declare_directory means
##  that bazel doesn't pay attention to the contents of the
##  directory. But if we declare directory, only one action can create
##  that directory, which means the script to create the directory
##  must also symlink all files into place in the same go.

##

symlink_sh = """
#!/bin/bash
set -euo pipefail;

# the root directory
mkdir -p $1;

shift;

# pairs of src->dest symlinks
while (($#)); do
    src=$1
    dest=$2
    destdir=$(dirname $dest)

    mkdir -p $destdir
    cp $1 $2

    shift 2
done
"""

def clojure_jar_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    src_dir = ctx.actions.declare_directory("%s.srcs" % ctx.label.name)

    # not declaring `classes_dir` because it's not an output, and we don't need any
    # shell commands on it. Unclear why, but clojure doesn't like the
    # directory created by `declare_directory`
    classes_dir = "classes"

    output_jar = ctx.actions.declare_file("%s.jar" % ctx.label.name)

    library_path = []

    input_file_map = {}
    for dep in ctx.attr.srcs + ctx.attr.deps:
        if CljInfo in dep:
            input_file_map.update(dep[CljInfo].transitive_clj_srcs)

    java_deps = []
    for dep in ctx.attr.srcs + input_file_map.keys() + ctx.attr.deps + ctx.attr.compiledeps + toolchain.runtime:
        if CljInfo in dep:
            java_deps.extend(dep[CljInfo].transitive_java_deps)
        if JavaInfo in dep:
            java_deps.append(dep[JavaInfo])

    symlink_args = ctx.actions.args()
    for infile,path in input_file_map.items():
        if path[0] != "/":
            fail("path must be absolute, got " + path)
        src_file = infile.files.to_list()[0]
        dest_file = src_dir.path + path
        symlink_args.add(src_file.path, dest_file)

    ctx.actions.run_shell(
        command = symlink_sh,
        arguments = [src_dir.path, symlink_args],
        inputs = [target.files.to_list()[0] for target in input_file_map.keys()],
        outputs = [src_dir])

    runfiles = ctx.runfiles()

    for dep in ctx.attr.srcs + ctx.attr.deps:
        if DefaultInfo in dep:
            runfiles = runfiles.merge(dep[DefaultInfo].default_runfiles)
            runfiles = runfiles.merge(dep[DefaultInfo].data_runfiles)

    java_info = JavaInfo(
        output_jar = output_jar,
        compile_jar = output_jar,
        source_jar = None,
        deps = java_deps,
        runtime_deps = java_deps)

    native_libs = []
    for f in runfiles.files.to_list():
        ## Bazel on mac sticks weird looking directories in runfiles, like _solib_darwin/_U_S_Snative_C_Ulibsodium___Unative_Slibsodium_Slib. filter them out
        if (f.path.endswith(".dylib") or f.path.endswith(".so")) and (f.path.rfind("solib_darwin") == -1):
            native_libs.append(f)

    for f in native_libs:
        dirname = f.dirname
        if not contains(library_path, dirname):
            library_path.append(dirname)

    classpath_files = [src_dir] + toolchain.files.runtime + java_info.transitive_runtime_deps.to_list() + ctx.files.compiledeps
    classpath_string = ":".join([classes_dir] + [f.path for f in classpath_files])

    library_path_str = "-Djava.library.path=" + ":".join(library_path) if len(library_path) > 0 else ""

    cmd = """
        set -euo pipefail;
        mkdir {classes_dir};
        {java} -Dclojure.main.report=stderr -cp {classpath} {library_path_str} clojure.main -m rules-clojure.jar :input-dir '"{input_dir}"' :aot [{aot}] :classes-dir '"{classes_dir}"' :output-jar '"{output_jar}"'
    """.format(
        java = toolchain.java,
        src_dir = src_dir.path,
        classes_dir = classes_dir,
        classpath = classpath_string,
        input_dir = src_dir.path,
        output_jar = output_jar.path,
        library_path_str = library_path_str,
        aot = ",".join([ns for ns in ctx.attr.aot]))

    inputs = [src_dir] + toolchain.files.scripts + java_common.merge(java_deps).transitive_runtime_deps.to_list() + toolchain.files.jdk + native_libs

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
