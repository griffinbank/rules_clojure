CljInfo = provider(fields = ["srcs", # map of labels to classpath
                             "deps",
                             "aot", # list of namespace strings
                             "transitive_clj_srcs", # transitive srcs
                             "transitive_java_deps"])
