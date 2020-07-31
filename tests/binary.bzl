def test_suite():
    native.sh_test(
        name = "binary_output_test",
        srcs = [":binary.sh"],
        data = [":binary"],
        args = ["$(location :binary)", "test", "test"],
        size = "small",
    )
