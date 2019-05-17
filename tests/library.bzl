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

  return analysistest.end(env)

providers_test = analysistest.make(_providers_test_impl)

def test_suite():
    clojure_library(
        name = "library_under_test",
        srcs = ["empty.clj"],
    )

    providers_test(name = "library_providers_test", target_under_test = ":library_under_test")

    native.test_suite(
        name = "library_test_suite",
        tests = [":library_providers_test",],
    )
