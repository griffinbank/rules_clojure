# [Bazel](https://bazel.build) rules for [Clojure](https://clojure.org) programming language

<!-- Generated with Stardoc: http://skydoc.bazel.build -->

<a name="#clojure_library"></a>

## clojure_library

<pre>
clojure_library(<a href="#clojure_library-name">name</a>, <a href="#clojure_library-srcs">srcs</a>, <a href="#clojure_library-deps">deps</a>, <a href="#clojure_library-aots">aots</a>)
</pre>

Builds a jar for given sources with ahead-of-time compilation.

### Attributes

<table class="params-table">
  <colgroup>
    <col class="col-param" />
    <col class="col-description" />
  </colgroup>
  <tbody>
    <tr id="clojure_library-name">
      <td><code>name</code></td>
      <td>
        <a href="https://bazel.build/docs/build-ref.html#name">Name</a>; required
        <p>
          A unique name for this target.
        </p>
      </td>
    </tr>
    <tr id="clojure_library-srcs">
      <td><code>srcs</code></td>
      <td>
        <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a>; optional
        <p>
          clj source files.
        </p>
      </td>
    </tr>
    <tr id="clojure_library-deps">
      <td><code>deps</code></td>
      <td>
        <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a>; optional
        <p>
          Libraries to link into this library.
        </p>
      </td>
    </tr>
    <tr id="clojure_library-aots">
      <td><code>aots</code></td>
      <td>
        List of strings; optional
        <p>
          Namespaces to be compiled.
        </p>
      </td>
    </tr>
  </tbody>
</table>


<a name="#clojure_repl"></a>

## clojure_repl

<pre>
clojure_repl(<a href="#clojure_repl-name">name</a>, <a href="#clojure_repl-deps">deps</a>, <a href="#clojure_repl-ns">ns</a>)
</pre>

Runs REPL with given dependencies in classpath.

### Attributes

<table class="params-table">
  <colgroup>
    <col class="col-param" />
    <col class="col-description" />
  </colgroup>
  <tbody>
    <tr id="clojure_repl-name">
      <td><code>name</code></td>
      <td>
        <a href="https://bazel.build/docs/build-ref.html#name">Name</a>; required
        <p>
          A unique name for this target.
        </p>
      </td>
    </tr>
    <tr id="clojure_repl-deps">
      <td><code>deps</code></td>
      <td>
        <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a>; optional
        <p>
          Libraries available in REPL.
        </p>
      </td>
    </tr>
    <tr id="clojure_repl-ns">
      <td><code>ns</code></td>
      <td>
        String; optional
        <p>
          Namespace to start REPL in.
        </p>
      </td>
    </tr>
  </tbody>
</table>


<a name="#clojure_test"></a>

## clojure_test

<pre>
clojure_test(<a href="#clojure_test-name">name</a>, <a href="#clojure_test-srcs">srcs</a>, <a href="#clojure_test-deps">deps</a>)
</pre>

Runs clojure.test for given sources.

### Attributes

<table class="params-table">
  <colgroup>
    <col class="col-param" />
    <col class="col-description" />
  </colgroup>
  <tbody>
    <tr id="clojure_test-name">
      <td><code>name</code></td>
      <td>
        <a href="https://bazel.build/docs/build-ref.html#name">Name</a>; required
        <p>
          A unique name for this target.
        </p>
      </td>
    </tr>
    <tr id="clojure_test-srcs">
      <td><code>srcs</code></td>
      <td>
        <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a>; optional
        <p>
          clj source files with test cases.
        </p>
      </td>
    </tr>
    <tr id="clojure_test-deps">
      <td><code>deps</code></td>
      <td>
        <a href="https://bazel.build/docs/build-ref.html#labels">List of labels</a>; optional
        <p>
          Libraries to link into this library.
        </p>
      </td>
    </tr>
  </tbody>
</table>

<!-- ------------------------------------------------- -->

## clojure_binary

There is no such rule but same effect might be achieved with combination of clojure_library with aots and java_binary.

```build
clojure_library(
    name = "library",
    srcs = ["library.clj"],
    aots = ["library"]
)

java_binary(
    name = "binary",
    main_class = "library",
    runtime_deps = [":library"]
)
```
