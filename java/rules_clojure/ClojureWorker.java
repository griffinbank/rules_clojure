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
import rules_clojure.DynamicClassLoader;

class Input {
    String path;
    String digest;
}

class WorkRequest {
    String[] arguments;
    Input[] inputs;
    Integer requestId;
}

class WorkResponse {
    Integer exitCode;
    String output;
    Integer requestId;
}

class ClojureCompileRequest {
    String[] aot;
    String classes_dir;
    String[] classpath;
    String output_jar;
    String src_dir;
    String[] srcs;
}

class RuntimeCache{
    // Classpath String to Digest
    HashMap<String,String> key;
    DynamicClassLoader classloader;
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
	InputStream stdin = System.in;

	Gson gson = new GsonBuilder().create();

	while (true) {
	    ByteArrayOutputStream outStream = new ByteArrayOutputStream();
	    PrintStream out = new PrintStream(outStream);
	    System.setOut(out);
	    System.setErr(out);

	    Reader stdin_reader = new InputStreamReader(stdin);
	    Writer stdout_writer = new OutputStreamWriter(out);
	    JsonReader gson_reader = new JsonReader(stdin_reader);
	    JsonWriter gson_writer = new JsonWriter(stdout_writer);

	    WorkRequest request = gson.fromJson(gson_reader, WorkRequest.class);

	    // System.err.println("request" + gson.toJson(request));
	    // System.err.println("requestId" + request.requestId);

	    // The request will be null if stdin is closed.  We're
	    // not sure if this happens in TheRealWorld™ but it is
	    // useful for testing (to shut down a persistent
	    // worker process).
	    if (request == null) {
		System.err.println("break");
		break;
	    }

	    int code = 0;

	    try {
		processRequest(request);
	    } catch (Throwable e) {
		System.err.println(e.getMessage());
		e.printStackTrace(System.err);
		code = 1;
		throw e;
	    }
	    out.flush();

	    WorkResponse response = new WorkResponse();
	    response.exitCode = code;
	    response.output = outStream.toString();
	    real_stdout.println(gson.toJson(response));
	}
    }

    public static HashMap<String,String> getCacheKey(WorkRequest work_request,ClojureCompileRequest compile_request)
    {
	// returns a map of classpath string to digests
	HashSet<String> classpath = new HashSet<String>();
	// the classpath will be: 1) directory paths and 2)
	// JARs. Bazel will provide input digests for all src _files_
	// but not src directories, so they will not be present in the
	// cache key. Therefore, we will only reload when a jar changes digest
	for(String s: compile_request.classpath) {
	    classpath.add(s);
	}
	HashMap<String,String> key = new HashMap<String,String>();
	for(Input i : work_request.inputs) {
	    if (classpath.contains(i.path)) {
		key.put(i.path,i.digest);
	    }
	}
	return key;
    }

    public static RuntimeCache newRuntime(WorkRequest work_request, ClojureCompileRequest compile_request) throws Exception
    {
	System.err.println("new runtime");
	DynamicClassLoader cl = new DynamicClassLoader(ClojureWorker.class.getClassLoader());
	RuntimeCache cache = new RuntimeCache();

	for(String path : compile_request.classpath) {
	    cl.addURL(new File(path).toURI().toURL());
	}

	cache.classloader = cl;
	cache.runtime = ClojureRuntimeShim.newRuntime(cl, "clojure-worker");
	cache.key = getCacheKey(work_request, compile_request);
	return cache;
    }

    public static List<URL> classpathUrls(ClojureCompileRequest req) throws Exception
    {
	ArrayList<URL> urls = new ArrayList<URL>();

	for (String path : req.classpath){
	    urls.add(new File(path).toURI().toURL());
	}
	return urls;
    }

    public static void printClassPath()
    {
	System.err.println("classpath:");
	for (URL u : runtimeCache.classloader.getURLs()){
	    System.err.println(u);
	}
    }

    public static RuntimeCache getClojureRuntime(WorkRequest work_request) throws Exception
    {
	Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_DASHES).create();
	ClojureCompileRequest compile_request = gson.fromJson(work_request.arguments[0],ClojureCompileRequest.class);

	// Look at the inputs. If the new jars are a superset of the existing set, return the cached loader, otherwise create and return a new loader
	if(runtimeCache == null) {
	    System.err.println("cache = null, new runtime");
	    return newRuntime(work_request, compile_request);
	}
	HashMap<String,String> request_key = getCacheKey(work_request, compile_request);

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
	System.err.println("reusing runtime");
	for(Entry<String,String> entry : request_key.entrySet()) {
	    String path = entry.getKey();
	    URL url = new File(path).toURI().toURL();
	    runtimeCache.classloader.addURL(url);
	}
	runtimeCache.key = request_key;
	return runtimeCache;
    }

    public static void processRequest(WorkRequest request) throws Exception
    {
	runtimeCache = getClojureRuntime(request);
	ClojureRuntimeShim runtime = runtimeCache.runtime;
	try {
	    runtime.require("rules-clojure.jar");
	    runtime.invoke("rules-clojure.jar/compile!", request.arguments[0]);
	} catch (Throwable t) {
	    printClassPath();
	    throw t;
	}
    }

    public static void ephemeralWorkerMain(String[] args)
    {
	System.err.println("ClojureWorker ephemeral");
    }
}
