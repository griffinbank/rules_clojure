Clojure is a compiled language. When loading source files or
evaling, the Clojure compiler generates a .class file, and then
loads it via standard Java classloaders. During non-AOT operation,
the .classfile exists in memory without being written to disk.

When AOT'ing, the compiler writes a .class file that corresponds to the
name of the source file. The clojure namespace `foo-bar.core` will
produce a file `foo_bar/core.class`.

The clojure compiler works by binding `*compile-files*` true and then
calling `load`. `load` looks for either the source file or .class. If
the .class file is present and has a newer file modification time, it
is loaded as a normal java class. Otherwise if the src file is present
the compiler runs, and .class files are produced as a side effect of
the load. If the .class file is already loaded, the compiler will not
run and no .class files will be produced.

Classloaders form a hierarchy. A classloader first asks its parent
to load a class, and if the parent can't, the current CL attempts
to load. In normal clojure operation, `java -cp` creates a
URLClassloader containing URLs pointing at jars and source
dirs. `clojure.main` then creates a clojure.lang.DynamicClassLoader
as a child of the URL classloader. Finally, clojure.lang.Compiler
creates its own private c.l.DCL.

Classloaders are also responsible for loading resources, using the
same hierarchy.

Visibility principle: Child classloaders can look up the classloader
hierarchy to find classes, parent classesloaders can't look down the
hierarchy.

There are many ways to configure classloaders, but the most common way
we see Clojure in the wild is:

- System classloader
 - URLClassLoader
  - clojure.lang.DynamicClassLoader

`java -cp jarA.jar:jarB.jar:src clojure.main`, the `-cp` flag will
create a URLClassLoader containing all jars from the command line. On
startup, Clojure will create an instance of c.l.DCL which is a child
of the URL loader.

Consider `(require 'foo.core)`

If foo.core was AOT'd, it will be loaded by the URLClassloader
(because after AOTing, there will be a foo/core.class file in the
jar). If the namespace had to be compiled at runtime, it will be
loaded by the compiler's DCL.

In the JVM, classes are not unique, they are unique _per
classloader_. Two classes with the same name in different classloaders
will not be identical, which breaks protocols because (instance? ClsA
x) is false.

DCL contains a static class cache, shared by all instances in the
same classloader (static class variables are shared among all
instances of the same class, _from the same classloader_)

When compiling, defprotocol creates new java interfaces.

CLJ-1544: If a jar in the URLClassLoader contains an AOT'd call site
of a protocol, and the protocol definition is not AOT'd, things will
break. This is because the java interface will be compiled and stored in
the clojure.lang.DynamicClassLoader. The DCL is a child of the
URLClassloader, so the call site in the parent classloader can't find
the AOT'd definition due to the Visibility Principle. There are two
solutions: AOT the protocol definition as well (so both live in the
URLClassLoader), or use DCL/addURL so both live in c.l.DCL. AOTing the
protocol and not AOTing the call site is fine, because the call site
can look up the classloader hierarchy to find the interface backing
the protocol.

If an AOT'd ns contains a protocol or deftype, the resulting
classfile should appear in exactly one jar: if the classfiles
appear in multiple jars, there's a chance of JarHell.

We want to keep the worker up and incrementally load code in the
same worker, because reloading the environment on every namespace
is slow.

Java classes cannot be GC'd individually, but when the defining
classloader is GC'd, all child classes of the classloader can be GC'd.

# Solution
rules-clojure.jar has dependencies to implement non-transitive
compilation. rules clojure also wants to be AOT'd, for speed. It is
possible for dependencies to conflict between rules-clojure and
client code. Therefore, use two classloaders, to create separate
environments: one to generate the compilation script and assemble
jars, and a second to do compilation.

When a compile job comes in, allocate a classloader. After compiling,
if the namespace being AOT'd did not contain `defprotocol`, return the
classloader to the cache. If it did compile a protocol, GC the
cacheloader

# Rejected Implementations

- naive: make a clean classloader between every job. Works. Clean. Too
  slow.

- dirty: use the same classloader and append on every job. Works when
  single-threaded. Fails under concurrency because RT state is not
  cleaned up. Fails because jarhell between compiles.

- immutable persistent tree of loaders. Fails because some classes
  insist on being part of the same loader as others. RT state still
  gets dirty, and we have to reload the clojure.jar, which will
  invalidate the whole tree. If cleaning up worked, this would work.

- keeping a pool of cached classloaders. Fixes JarHell. Fails because
  the clojure RT is dirty and not cleaned between runs.

- same as above, but don't include clojure in the cache and add every
  time. Fails because Visibility principle. If jars require clojure,
  clojure must be above or equal to them in the hierarchy.

- naive multiplex: the code supports multiplex, but it's disabled
  because it's slower than singleplex for now. The caching algorithm
  reuses the cached classloader which shares the most jars in
  common. This frequently leads to cache starvation, where multiple
  threads want to use the same classloader. When the cache is empty,
  starting a new classloader might take 20seconds, while the next
  compile job might take 1 second. When running singleplex, each
  worker has its own classloader which is warm 90% of the time. To
  make multiplex faster, we'd need each worker thread to have its own
  classloader.

- clean up clojure.lang.RT state after compiling.
 - cannot work, because classes are only GC'd when the ClassLoader is GC'd

- write a mutable classloader than can unload classes between jobs
 - Suspect this is not possible. To truly unload a class, the defining
   classloader needs to be GC'd
