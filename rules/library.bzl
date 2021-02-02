def copy_bash(ctx, src, dst):
    ctx.actions.run_shell(
        tools = [src],
        outputs = [dst],
        command = "cp -f \"$1\" \"$2\"",
        arguments = [src.path, dst.path],
        mnemonic = "CopyFile",
        progress_message = "Copying files",
        use_default_shell_env = True,
    )

CljInfo = provider(fields = ["depset",
                             "srcs",
                             "transitive_srcs",
                             "java_deps"])

def clojure_library_impl(ctx):
    toolchain = ctx.toolchains["@rules_clojure//:toolchain"]

    all_deps = []
    java_deps = []
    transitive_srcs = []
    for src in ctx.attr.srcs:
        if CljInfo in src:
            transitive_srcs.append(src[CljInfo].srcs)

    for dep in ctx.attr.srcs + ctx.attr.deps:
        if CljInfo in dep:
            all_deps.append(dep[CljInfo].depset)
            java_deps.extend(dep[CljInfo].java_deps)
        if JavaInfo in dep:
            java_deps.append(dep[JavaInfo])

    for dep in java_deps:
        all_deps.append(dep[JavaInfo].transitive_runtime_deps)
        all_deps.append(dep[JavaInfo].transitive_deps)

    direct_files = ctx.files.srcs + ctx.files.deps
    the_depset = depset(direct_files, transitive = all_deps)

    runfiles = ctx.runfiles(files = direct_files,
                            transitive_files = the_depset)

    return [
        DefaultInfo(runfiles = runfiles),
        CljInfo(depset = the_depset,
                srcs = ctx.files.srcs,
                transitive_srcs = transitive_srcs,
                java_deps = java_deps)

    ]
