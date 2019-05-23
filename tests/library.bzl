load("//rules:library.bzl", "clojure_library")
load("@bazel_skylib//lib:unittest.bzl", "analysistest", "asserts")

def _providers_test_impl(ctx):
  env = analysistest.begin(ctx)

  target_under_test = analysistest.target_under_test(env)

  libjar = "liblibrary_under_test.jar"

  asserts.equals(env, [libjar], [f.basename for f in target_under_test[DefaultInfo].files])
  asserts.equals(env, [libjar], [f.class_jar.basename for f in target_under_test[JavaInfo].outputs.jars])
  asserts.equals(env, [libjar], [f.basename for f in target_under_test[JavaInfo].runtime_output_jars])
  asserts.equals(env, [libjar], [f.basename for f in target_under_test[JavaInfo].compile_jars])
  asserts.equals(env, [libjar], [f.basename for f in target_under_test[JavaInfo].full_compile_jars])
  asserts.equals(env, [libjar], [f.basename for f in target_under_test[JavaInfo].source_jars])
  asserts.equals(env,
      [libjar, "clojure-1.10.0.jar", "spec.alpha-0.2.176.jar", "core.specs.alpha-0.2.44.jar"],
      [f.basename for f in target_under_test[JavaInfo].transitive_runtime_deps]
  )

  return analysistest.end(env)

providers_test = analysistest.make(_providers_test_impl)

def test_suite():
    clojure_library(
        name = "library_under_test",
        srcs = ["library.clj"],
    )

    providers_test(name = "library_providers_test", target_under_test = ":library_under_test")

    native.test_suite(
        name = "library_test_suite",
        tests = [":library_providers_test",],
    )

    native.sh_test(
        name = "library_output_test",
        srcs = ["library.sh"],
        args = ["$(location :liblibrary_under_test.jar)", "tests/library.clj"],
        data = [":liblibrary_under_test.jar"],
    )
