package rules_clojure;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.HashSet;
import org.projectodd.shimdandy.ClojureRuntimeShim;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

class ClojureCompileRequest {
    String[] aot;
    String classes_dir;
    String[] classpath;
    String[] shim_classpath;
    String output_jar;
    String src_dir;
    String[] srcs;
}

class RuntimeCache{
    // Classpath String to Digest
    HashMap<String,String> key;
    ClojureRuntimeShim runtime;
}

class ClojureWorker  {

    private static RuntimeCache runtimeCache = null;

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
		    real_stderr.println(outStream.toString());
		    real_stderr.flush();
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

    public static HashMap<String,String> getCacheKey(WorkRequest work_request,ClojureCompileRequest compile_request)
    {
	// returns a map of classpath string to digests
	HashSet<String> classpath = new HashSet<String>();
	// the classpath will be: 1) directory paths and 2)
	// JARs. Bazel will provide input digests for all src _files_
	// but not src directories. If a source file changes, just
	// `compile` will handle it. Therefore, we will only reload
	// when a jar changes digest
	for(String s: compile_request.classpath) {
	    classpath.add(s);
	}

	HashMap<String,String> key = new HashMap<String,String>();
	for(Input i : work_request.getInputsList()) {
	    if (classpath.contains(i.getPath())) {
		key.put(i.getPath(),i.getDigest().toStringUtf8());
	    }
	}
	return key;
    }

    public static RuntimeCache newRuntime(WorkRequest work_request, ClojureCompileRequest compile_request) throws Exception
    {
	DynamicClassLoader shim_cl = new DynamicClassLoader(ClojureWorker.class.getClassLoader());
	for(String path : compile_request.shim_classpath) {
	    shim_cl.addURL(new File(path).toURI().toURL());
	}

	RuntimeCache cache = new RuntimeCache();

	cache.runtime = ClojureRuntimeShim.newRuntime(shim_cl, "clojure-worker");
	cache.key = getCacheKey(work_request, compile_request);
	return cache;
    }

    public static RuntimeCache getClojureRuntime(WorkRequest work_request) throws Exception
    {
	Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_DASHES).create();
	ClojureCompileRequest compile_request = gson.fromJson(work_request.getArguments(0),ClojureCompileRequest.class);

	// Look at the inputs. If the new jars are a superset of the existing set, return the cached loader, otherwise create and return a new loader
	if(runtimeCache == null) {
	    return newRuntime(work_request, compile_request);
	}
	HashMap<String,String> request_key = getCacheKey(work_request, compile_request);

	HashMap<String,String> existing_key = runtimeCache.key;

	for(Entry<String,String> entry : request_key.entrySet()) {
	    String path = entry.getKey();
	    String request_digest = entry.getValue();
	    String cache_digest = runtimeCache.key.get(path);
	    if ((cache_digest != null) && (!cache_digest.equals(request_digest))) {
		System.err.println(String.format("digests differ on %s, %s vs. %s",path, request_digest, cache_digest));
		runtimeCache.runtime.close();
		runtimeCache = newRuntime(work_request,compile_request);
		return runtimeCache;
	    }
	}
	runtimeCache.key = request_key;
	return runtimeCache;
    }

    public static Object processRequest(WorkRequest request) throws Exception
    {
	runtimeCache = getClojureRuntime(request);
	ClojureRuntimeShim runtime = runtimeCache.runtime;

	runtime.require("rules-clojure.jar");
	return runtime.invoke("rules-clojure.jar/compile-json", request.getArguments(0));
    }

    public static void ephemeralWorkerMain(String[] args)
    {
	System.err.println("ClojureWorker ephemeral");
    }
}
