package rules_clojure;

import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.projectodd.shimdandy.ClojureRuntimeShim;

// Clojure is a compiled language. When loading source files or
// evaling, the compiler generates a .class file, and then loads it
// via standard Java classloaders. During non-AOT operation, the
// .classfile exists in memory without being written to disk.

// When AOT'ing, the compiler writes a .class file that corresponds to the
// name of the source file. The clojure namespace `foo-bar.core` will
// produce a file `foo_bar/core.class`.

// Classloaders form a hierarchy. A classloader usually asks its
// parent to load a class, and if the parent can't, the current CL
// attempts to load. In normal clojure operation, `java -cp` creates a
// URLClassloader containing URLs pointing at jars and source
// dirs. `clojure.main` then creates a clojure.lang.DynamicClassLoader
// as a child of the URL classloader. Finally, clojure.lang.Compiler
// creates its own private DCL

// To load a namespace, clojure.lang.RT looks for both
// `foo-bar/core.clj` and `foo_bar/core.class`, starting from the
// classloader that contains 'this' clojure.lang.RT. If only the class
// file is present, it is loaded. If only the source exists, it is
// compiled and then loaded. If both are present, the one with the
// newer file modification time is loaded.

// In normal operation, if foo-bar.core was AOT'd, it will be loaded
// by the URLClassloader (because the .classfile exists). If it had to
// be compiled, it will be loaded by the compiler's DCL.

// In the JVM, classes are not unique, they are unique _per
// classloader_. Two classes with the same name in different
// classloaders will not be identical, which breaks protocols, among
// other things.

// DCL contains a static class cache, shared by all instances in the
// same classloader (static class variables are shared among all
// instances of the same class, _from the same classloader_)

// When compiling, defprotocol and deftype/defrecord create new
// classes. If a compile reloads a protocol, that breaks all existing
// users of the protocol. If the class is loaded in two separate
// classloaders, that will break some users.

// If an AOT'd ns contains a protocol or deftype, the resulting
// classfile should appear in exactly one jar (if the classfiles
// appear in multiple jars, there's a chance both definitions could
// get loaded, and then some users will break).

// When AOT'ing a consumer of a protocol, the compiled definition must
// be on the classpath (because the user needs to refer to the AOT'd
// class id, not the src class id)

// We want to keep the worker up and incrementally load code in the
// same worker, because reloading the environment is expensive.

// therefore: create a mostly-persistent classloader containing all
// jars that compile requests have asked for.

// we compile in Clojure because it's easier and more powerful.

// compile-json has three return values:

// nil: compilation returned successfully
// ::reload - compilation was successful, and this environment should be thrown away before the next compile
// ::restart - the new compilation request is incompatible with the existing environment. Throw away the environment and try again with a clean env.

class ClojureCompileRequest {
    String[] aot;
    String classes_dir;
    String[] classpath;
    String output_jar;
    String src_dir;
    String[] srcs;
}

class ClojureWorker  {

    public static DynamicClassLoader classloader = new DynamicClassLoader(ClojureWorker.class.getClassLoader());

    public static void main(String [] args) throws Exception
    {
	if (args.length > 0 && args[0].equals("--persistent_worker")) {
	    persistentWorkerMain(args);
	} else {
	    ephemeralWorkerMain(args);
	}
    }

    public static void persistentWorkerMain(String[] args) throws Exception
    {
	System.err.println("ClojureWorker persistentWorkerMain");
	PrintStream real_stdout = System.out;
	PrintStream real_stderr = System.err;
	InputStream stdin = System.in;
	ByteArrayOutputStream outStream = new ByteArrayOutputStream();
	PrintStream out = new PrintStream(outStream, true, "UTF-8");

	Reader stdin_reader = new InputStreamReader(stdin);
	Writer stdout_writer = new OutputStreamWriter(out);

	System.setOut(out);

	try {
	    while (true) {
		outStream.reset();

		WorkRequest request = WorkRequest.parseDelimitedFrom(stdin);

		// The request will be null if stdin is closed.  We're
		// not sure if this happens in TheRealWorld™ but it is
		// useful for testing (to shut down a persistent
		// worker process).
		if (request == null) {
		    real_stderr.println("null request, break");
		    break;
		}

		int code = 1;

		try {
		    System.setErr(out);
		    Object ret = processRequest(request);
		    code = 0;
		} catch (Throwable e) {
		    real_stderr.println(e.getMessage());
		    e.printStackTrace(real_stderr);
		    throw e;
		}
		finally {
		    System.setErr(real_stderr);
		    String out_str = outStream.toString();
		    if (out_str.length() > 0) {
			real_stderr.println(out_str);
			real_stderr.flush();
		    }
		}
		out.flush();

		WorkResponse.newBuilder()
		    .setExitCode(code)
		    .setOutput(outStream.toString())
		    .build()
		    .writeDelimitedTo(real_stdout);
	    }
	}
	catch (Throwable t){
	    real_stderr.println(t.getMessage());
	    t.printStackTrace(real_stderr);
	    throw t;
	}
    }

    public static ClojureRuntimeShim newRuntime(WorkRequest work_request, ClojureCompileRequest compile_request) throws Exception
    {
	for(String path : compile_request.classpath) {
	    classloader.addURL(new File(path).toURI().toURL());
	}

	return ClojureRuntimeShim.newRuntime(classloader, "clojure-worker");

    }

    public static Object processRequest(WorkRequest work_request) throws Exception
    {
	Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_DASHES).create();
	ClojureCompileRequest compile_request = gson.fromJson(work_request.getArguments(0),ClojureCompileRequest.class);

	ClojureRuntimeShim runtime = newRuntime(work_request, compile_request);

	runtime.require("rules-clojure.jar");
	String ret = (String) runtime.invoke("rules-clojure.jar/compile-json", work_request.getArguments(0));

	if(ret.equals(":rules-clojure.jar/restart")) {
	    classloader = new DynamicClassLoader(ClojureWorker.class.getClassLoader());
	    return processRequest(work_request);
	}

	if(ret.equals(":rules-clojure.jar/reload")) {
	    classloader = new DynamicClassLoader(ClojureWorker.class.getClassLoader());
	}
	return ret;
    }

    public static void ephemeralWorkerMain(String[] args)
    {
	System.err.println("ClojureWorker ephemeral");
    }
}
