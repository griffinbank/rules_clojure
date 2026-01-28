### rule to start a clojure repl. We don't use `java_binary` because
### we want to support adding directories to the classpath (and in the
### future, possibly running from the source tree rather than `bazel-bin`), both in support of
### `clj` live reloading semantics

def get_classpath_path(f):
    if f.is_source:
        return "$BUILD_WORKSPACE_DIRECTORY/%s" % f.short_path
    else:
        return "$BUILD_WORKSPACE_DIRECTORY/%s" % f.path

def clojure_repl_impl(ctx):

    java_deps = depset(transitive = [d[JavaInfo].transitive_runtime_jars for d in ctx.attr.runtime_deps])

    classpath_files = [get_classpath_path(d) for d in ctx.files.classpath_dirs]

    jars = [d.short_path for d in java_deps.to_list()]

    ## It's important that the dirs go ahead of jars, or live reloading breaks
    classpath = classpath_files + jars
    classpath_str = ":".join(classpath)

    sh_file = ctx.actions.declare_file(ctx.attr.name)

    runfiles = ctx.runfiles(files = ctx.files.data,
                            transitive_files = java_deps)
    runfiles.merge_all([d[DefaultInfo].default_runfiles for d in ctx.attr.runtime_deps])

    default_info = DefaultInfo(executable=sh_file,
                               runfiles = runfiles)

    cmd = """java {jvm_flags} -cp {cp} {main_class} {args}""".format(cp=classpath_str,
                                                                          main_class = ctx.attr.main_class,
                                                                          args = " ".join(ctx.attr.args),
                                                                          jvm_flags = " ".join(ctx.attr.jvm_flags))

    ctx.actions.write(output=sh_file,
                      content=cmd,
                      is_executable=True)

    return [ default_info ]
