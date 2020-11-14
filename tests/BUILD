load("@rules_clojure//:rules.bzl", "clojure_binary", "clojure_java_library", "clojure_library", "clojure_repl", "clojure_test")
load("@rules_java//java:defs.bzl", "java_binary")

clojure_test(
    name = "unconditionally-passing-test",
    size = "small",
    srcs = ["unconditionally-passing-test.clj"],
)

sh_test(
    name = "unconditionally-passing-test-report",
    size = "small",
    srcs = ["assert/test.sh"],
    args = [
        "$(location :unconditionally-passing-test)",
        """'Testing tests.unconditionally-passing-test'""",
    ],
    data = [":unconditionally-passing-test"],
)

clojure_test(
    name = "unconditionally-failing-test",
    size = "small",
    srcs = ["unconditionally-failing-test.clj"],
    tags = ["manual"],
)

sh_test(
    name = "unconditionally-failing-test-report",
    size = "small",
    srcs = ["assert/test.sh"],
    args = [
        "$(location :unconditionally-failing-test)",
        """'FAIL in (must-fail)'""",
    ],
    data = [":unconditionally-failing-test"],
)

clojure_repl(
    name = "repl",
)

sh_test(
    name = "repl-test",
    size = "small",
    srcs = ["assert/repl.sh"],
    args = [
        "$(location :repl)",
        """'(+ 2 2)'""",
        """'user=> 4'""",
    ],
    data = [":repl"],
)

clojure_library(
    name = "ns-to-file-path",
    srcs = ["ns-to-file-path.clj"],
)

clojure_repl(
    name = "ns-to-file-path-repl",
    deps = [":ns-to-file-path"],
)

sh_test(
    name = "ns-to-file-path-repl-test",
    size = "small",
    srcs = ["assert/repl.sh"],
    args = [
        "$(location :ns-to-file-path-repl)",
        """'(load "/does/not/match/file_path")\n(all-ns)'""",
        """'does.not.match.file-path'""",
    ],
    data = [":ns-to-file-path-repl"],
)

clojure_library(
    name = "library",
    srcs = ["library.clj"],
)

clojure_test(
    name = "library-test",
    size = "small",
    srcs = ["library-test.clj"],
    deps = [":library"],
)

clojure_binary(
    name = "library-binary",
    main = "tests.library",
    deps = [":library"],
)

sh_test(
    name = "library-binary-test",
    size = "small",
    srcs = ["assert/binary.sh"],
    args = [
        "$(location :library-binary)",
        """input""",
        """'library main input'""",
    ],
    data = [":library-binary"],
)

clojure_repl(
    name = "library-repl",
    ns = "tests.library",
    deps = [":library"],
)

sh_test(
    name = "library-repl-test",
    size = "small",
    srcs = ["assert/repl.sh"],
    args = [
        "$(location :library-repl)",
        """'(echo "message")'""",
        """'tests.library=> "library message"'""",
    ],
    data = [":library-repl"],
)

clojure_library(
    name = "app-transitive",
    srcs = ["app.clj"],
    deps = [":library"],
)

clojure_test(
    name = "app-transitive-test",
    size = "small",
    srcs = ["app-test.clj"],
    deps = [":app-transitive"],
)

clojure_repl(
    name = "app-transitive-repl",
    ns = "tests.app",
    deps = [":app-transitive"],
)

sh_test(
    name = "app-transitive-repl-test",
    size = "small",
    srcs = ["assert/repl.sh"],
    args = [
        "$(location :app-transitive-repl)",
        """'(echo "message")'""",
        """'tests.app=> "app library message"'""",
    ],
    data = [":app-transitive-repl"],
)

clojure_binary(
    name = "app-transitive-binary",
    main = "tests.app",
    deps = [":app-transitive"],
)

sh_test(
    name = "app-transitive-binary-test",
    size = "small",
    srcs = ["assert/binary.sh"],
    args = [
        "$(location :app-transitive-binary)",
        """input""",
        """'app main library input'""",
    ],
    data = [":app-transitive-binary"],
)

clojure_library(
    name = "app-bundled",
    srcs = [
        "app.clj",
        "library.clj",
    ],
)

clojure_test(
    name = "app-bundled-test",
    size = "small",
    srcs = ["app-test.clj"],
    deps = [":app-bundled"],
)

clojure_repl(
    name = "app-bundled-repl",
    ns = "tests.app",
    deps = [":app-bundled"],
)

sh_test(
    name = "app-bundled-repl-test",
    size = "small",
    srcs = ["assert/repl.sh"],
    args = [
        "$(location :app-bundled-repl)",
        """'(echo "message")'""",
        """'tests.app=> "app library message"'""",
    ],
    data = [":app-bundled-repl"],
)

clojure_binary(
    name = "app-bundled-binary",
    main = "tests.app",
    deps = [":app-bundled"],
)

sh_test(
    name = "app-bundled-binary-test",
    size = "small",
    srcs = ["assert/binary.sh"],
    args = [
        "$(location :app-bundled-binary)",
        """input""",
        """'app main library input'""",
    ],
    data = [":app-bundled-binary"],
)

# AOT with clojure_java_library
# TODO: Move to aot package
# TODO: Add tests for multiple namespaces and multiple dependencies
# TODO: Do we need multiple namespaces and dependencies since clojure compiles everything transitively?

clojure_java_library(
    name = "library-java",
    namespaces = ["tests.library"],
    deps = [":library"],
)

java_binary(
    name = "library-java-binary",
    main_class = "tests.library",
    runtime_deps = [":library-java"],
)

sh_test(
    name = "library-java-binary-test",
    size = "small",
    srcs = ["assert/binary.sh"],
    args = [
        "$(location :library-java-binary)",
        """input""",
        """'library main input'""",
    ],
    data = [":library-java-binary"],
)

clojure_java_library(
    name = "app-bundled-java",
    namespaces = ["tests.app"],
    deps = [":app-bundled"],
)

clojure_test(
    name = "app-bundled-java-test",
    size = "small",
    srcs = ["app-java-test.clj"],
    deps = [":app-bundled-java"],
)

java_binary(
    name = "app-bundled-java-binary",
    main_class = "aot.CompiledAppClass",
    runtime_deps = [":app-bundled-java"],
)

sh_test(
    name = "app-bundled-java-binary-test",
    size = "small",
    srcs = ["assert/binary.sh"],
    args = [
        "$(location :app-bundled-java-binary)",
        """input""",
        """'app main library input'""",
    ],
    data = [":app-bundled-java-binary"],
)

clojure_java_library(
    name = "app-transitive-java",
    namespaces = ["tests.app"],
    deps = [":app-transitive"],
)

clojure_test(
    name = "app-transitive-java-test",
    size = "small",
    srcs = ["app-java-test.clj"],
    deps = [":app-transitive-java"],
)

java_binary(
    name = "app-transitive-java-binary",
    main_class = "aot.CompiledAppClass",
    runtime_deps = [":app-transitive-java"],
)

sh_test(
    name = "app-transitive-java-binary-test",
    size = "small",
    srcs = ["assert/binary.sh"],
    args = [
        "$(location :app-transitive-java-binary)",
        """input""",
        """'app main library input'""",
    ],
    data = [":app-transitive-java-binary"],
)
