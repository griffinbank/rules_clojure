package rules_clojure;

import com.google.devtools.build.lib.worker.WorkerProtocol;
import com.google.gson.stream.JsonReader;
import com.google.gson.GsonBuilder;
import com.google.gson.Gson;
import com.google.gson.FieldNamingPolicy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import org.projectodd.shimdandy.ClojureRuntimeShim;

class ClojureCompileRequest{
    // GSON representation
    String[] aot;
    String classes_dir;
    String[] classpath;
    String output_jar;
    String src_dir;
    String[] srcs;
}

class ClojureWorker  {

    public static void main(String [] args) {
	System.err.println("ClojureWorker main" + args);

	if (args.length > 0 && args[0].equals("--persistent_worker")) {
	    persistentWorkerMain(args);
	} else {
	    ephemeralWorkerMain(args);
	}
    }

    public static void persistentWorkerMain(String[] args)
    {
	System.err.println("ClojureWorker persistentWorkerMain");

	PrintStream real_stdout = System.out;

	while (true) {
	    try {
		ByteArrayOutputStream outStream = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(outStream);
		System.setOut(out);
		System.setErr(out);

		WorkerProtocol.WorkRequest request =
		    WorkerProtocol.WorkRequest.parseDelimitedFrom(System.in);

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
		}
		out.flush();

		WorkerProtocol.WorkResponse.newBuilder()
		    .setExitCode(code)
		    .setOutput(outStream.toString())
		    .build()
		    .writeDelimitedTo(real_stdout);

	    } catch (Throwable e) {
		System.err.println(e.getMessage());
	    }
	}
    }

    public static void processRequest(WorkerProtocol.WorkRequest request) throws Exception
    {
	Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_DASHES).create();
	ClojureCompileRequest compile_req = gson.fromJson(request.getArguments(0),ClojureCompileRequest.class);

	URL[] classpath_urls = new URL[compile_req.classpath.length];


	for (int i = 0; i < compile_req.classpath.length; i++){
	    String path = compile_req.classpath[i];
	    classpath_urls[i] = new File(path).toURI().toURL();
	}

	ClassLoader cl = new URLClassLoader(classpath_urls, ClojureWorker.class.getClassLoader());
	ClojureRuntimeShim runtime = ClojureRuntimeShim.newRuntime(cl, "clojure-worker");

	try {
	    runtime.require("rules-clojure.jar");
	    runtime.invoke("rules-clojure.jar/compile!", request.getArguments(0));
	}
	finally{
	    runtime.close();
	}

    }

    public static void ephemeralWorkerMain(String[] args)
    {
	System.err.println("ClojureWorker ephemeral");
    }
}
