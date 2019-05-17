load("//rules:clojure.bzl", "clojure_library")
load("@bazel_skylib//lib:unittest.bzl", "analysistest", "asserts")

def _library_providers_test_impl(ctx):
  env = analysistest.begin(ctx)

  target_under_test = analysistest.target_under_test(env)

  asserts.equals(env, ["liblibrary_under_test.jar"], [f.basename for f in target_under_test[DefaultInfo].files])

  return analysistest.end(env)

library_providers_test = analysistest.make(_library_providers_test_impl)

def library_providers_test_suite():
    clojure_library(
        name = "library_under_test",
        srcs = ["empty.clj"],
    )

    library_providers_test(name = "library_providers_test", target_under_test = ":library_under_test")

    native.test_suite(
        name = "library_providers_test_suite",
        tests = [":library_providers_test",],
    )
