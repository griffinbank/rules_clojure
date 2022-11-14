<!-- Generated with Stardoc: http://skydoc.bazel.build -->

<a name="#clojure_binary"></a>

## clojure_binary

<pre>
clojure_binary(<a href="#clojure_binary-name">name</a>, <a href="#clojure_binary-deps">deps</a>, <a href="#clojure_binary-main">main</a>)
</pre>

Builds a wrapper shell script with the same name as the rule.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :-------------: | :-------------: | :-------------: | :-------------: | :-------------: |
| name |  A unique name for this target.   | <a href="https://bazel.build/docs/build-ref.html#name">Name</a> | required |  |
| deps |  Libraries to link into this binary.   | <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a> | required |  |
| main |  A namespace to find a -main function for execution.   | String | required |  |


<a name="#clojure_library"></a>

## clojure_library

<pre>
clojure_library(<a href="#clojure_library-name">name</a>, <a href="#clojure_library-deps">deps</a>, <a href="#clojure_library-srcs">srcs</a>)
</pre>

Builds a jar file from given sources with the paths corresponding to namespaces.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :-------------: | :-------------: | :-------------: | :-------------: | :-------------: |
| name |  A unique name for this target.   | <a href="https://bazel.build/docs/build-ref.html#name">Name</a> | required |  |
| deps |  Libraries to link into this library.   | <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a> | optional | [] |
| srcs |  clj source files.   | <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a> | required |  |


<a name="#clojure_repl"></a>

## clojure_repl

<pre>
clojure_repl(<a href="#clojure_repl-name">name</a>, <a href="#clojure_repl-deps">deps</a>, <a href="#clojure_repl-ns">ns</a>)
</pre>

Runs REPL with given dependencies in classpath.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :-------------: | :-------------: | :-------------: | :-------------: | :-------------: |
| name |  A unique name for this target.   | <a href="https://bazel.build/docs/build-ref.html#name">Name</a> | required |  |
| deps |  Libraries available in REPL.   | <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a> | optional | [] |
| ns |  Namespace to start REPL in.   | String | optional | "" |


<a name="#clojure_test"></a>

## clojure_test

<pre>
clojure_test(<a href="#clojure_test-name">name</a>, <a href="#clojure_test-deps">deps</a>, <a href="#clojure_test-srcs">srcs</a>)
</pre>

Runs clojure.test for given sources.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :-------------: | :-------------: | :-------------: | :-------------: | :-------------: |
| name |  A unique name for this target.   | <a href="https://bazel.build/docs/build-ref.html#name">Name</a> | required |  |
| deps |  Libraries to link into this library.   | <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a> | optional | [] |
| srcs |  clj source files with test cases.   | <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a> | required |  |


