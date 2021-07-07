// extend URLClassLoader to allow adding URLs. We can't use clojure.lang.DynamicClassLoader because 1) clojure.jar can't be on ClojureWorker's boot classpath 2) we need the compiler to have a dynamicclassloader (including cache), but the parent CL can't use DCL's cache
package rules_clojure;

import java.lang.ref.Reference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URLClassLoader;
import java.net.URL;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;

public class DynamicClassLoader extends URLClassLoader{

    public DynamicClassLoader(ClassLoader parent){
	super(new URL[]{}, parent);
    }

    public void addURL(URL url){
	super.addURL(url);
    }

}
