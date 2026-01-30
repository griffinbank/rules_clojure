(ns rules-clojure.compile
  (:refer-clojure :exclude [future])
  (:require
   [clojure.spec.alpha :as s]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [rules-clojure.java.classpath :as cp]
   [rules-clojure.namespace.parse :as parse]
   [rules-clojure.fs :as fs]
   [rules-clojure.util :refer [shasum debug]])
  (:import [java.util.concurrent CompletableFuture ExecutorService Executors]))

(set! *warn-on-reflection* true)

(def real-require clojure.core/require)
(def real-load clojure.core/load)

(def root-resource @#'clojure.core/root-resource)

;; this code runs inside the classpath containing user code, so it
;; must not conflict with anything else. No third party
;; dependencies. If we require a new clojure version, that requires
;; all users to upgrade so try to do that sparingly as well.

(defn deref!
  "throw `ex` if *f does not complete within timeout"
  [*f timeout ex]
  {:pre [(future? *f)
         (integer? timeout)
         (instance? Throwable ex)]}
  (when (= ::timeout (deref *f timeout ::timeout))
    (throw ex)))

(defn src-resource-name [ns]
  (.substring ^String (#'clojure.core/root-resource ns) 1))

(defn src-resource
  "given a namespace symbol, return a tuple of [filename URL] where the
  backing .clj is located"
  [ns]
  {:pre [(symbol? ns)
         (= (.getContextClassLoader (Thread/currentThread))
            (.getClassLoader (class src-resource)))]
   :post [%]}
  (->> [".clj" ".cljc"]
       (some (fn [ext]
               (let [src-path (str (src-resource-name ns) ext)
                     src-resource (io/resource src-path)]
                 src-resource)))))

(defn loaded? [ns]
  {:pre [(symbol? ns)]}
;; `loaded?` checks if the namespace is present in
;; `clojure.core/loaded-libs. this is true at the end of the namespace
;; block i.e. `(ns foo (:require bar))`. We use futures that require
;; namespaces, so the best way to know if a namespace is _done_
;; loading is to either deref the future, or check inside _your own_
;; namespace future. if loaded? returns true anywhere else, the
;; namespace loading could be after the ns block, but before the end
;; of the file, which will lead to weird errors like `Unable to
;; resolve symbol: foo`, when you know it should be there.

  (contains? (loaded-libs) ns))

(defn loaded?-str [ns]
  {:pre [(string? ns)]
   :post [(boolean? %)]}
  ;; called from the worker's class loader, so we can't pass a
  ;; `symbol` through
  (loaded? (symbol ns)))

;; we can be asked to AOT a namespace after it is already loaded. If
;; the namespace contains protocols AND it's already loaded, that
;; would break all downstream users of the protocol. Also, we want to
;; compile in parallel and want to avoid races. Therefore, use 'agents'
;; to serialize requests to specific nses. Create an 'agent' per
;; namespace, and `send` commands. We will send `compile` and (rarely)
;; `require`

;; bazel can ask us to compile the same namespace multiple times, once
;; for compile time, once for runtime. These are technically separate
;; architectures (e.g. darwin-arm64-opt-exec
;; vs. darwin-arm64-fastbuild). To the JVM it makes no difference, but
;; bazel thinks they're separate files. Compiling twice causes
;; problems, if we don't reload the second jar will contain no .class
;; files. If we reload after compiling, that breaks some OSS code
;; doing janky things.

;; Therefore, hook into `require` and compile every namespace to a
;; separate directory. If bazel asks for the same ns again, just copy
;; the class files from temp directory. We fingerprint namespaces
;; using the SHA of the file contents

(defn ns->class-resource-name
  "given a namespace symbol, return the name of classfile that will load it"
  [ns]
  (-> ns
      (munge)
      (str/replace #"\." "/")
      (str "__init.class")))

(defn compiled?
  [ns]
  ;; We could use Class/forName, but that would attempt to load the
  ;; class. Use resource instead to avoid the side effect
  (io/resource (ns->class-resource-name ns)))

(defn add-classpath! [dir]
  (let [dir-f (fs/path->file dir)]
    (assert (.exists dir-f) (print-str dir-f "not found"))
    (.addURL (.getClassLoader (class add-classpath!)) (.toURL dir-f))))

;; root directory for all compiles. Each compile will be a subdir of
;; this
(def temp-dir (fs/new-temp-dir "rules_clojure"))

(defn ns-sha
  "return the hash of the ns file contents"
  [ns]
  {:pre [(symbol? ns)]}
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (assert (src-resource ns) (print-str "couldn't find src resource" (src-resource ns) " for" ns "with context classloader" cl (cp/classpath cl))))
  (-> ns
      (src-resource)
      (io/input-stream)
      (.readAllBytes)
      (shasum)))

(defn ->cache-dir [sha]
  {:pre [(seq sha)]}
  (let [path (fs/->path temp-dir sha)]
    (fs/ensure-directory path)
    path))

(defn get-cache-dir [sha]
  {:pre [(string? sha)
         (= 40 (count sha))]}
  (let [dir (fs/->path temp-dir sha)]
    (when (fs/exists? dir)
      dir)))

(defn copy-classes [src-dir dest-dir]
  {:pre [(fs/path? src-dir)
         (fs/path? dest-dir)]}
  (fs/cp-r src-dir dest-dir))

(defn require->nses
  "Given args to `require`, return a seq of namespaces that will be loaded"
  [prefix args]
  {:pre [(or (symbol? prefix) (nil? prefix))
         (sequential? args)
         (seq args)]}
  (let [join (fn [prefix arg]
               (if prefix
                 (symbol (str prefix \. (str arg)))
                 arg))]
    (->> args
         (mapcat (fn [arg]
                   (cond
                     (symbol? arg) [(join prefix arg)]
                     (and (seq arg) (nil? (second arg))) [(join prefix (first arg))]
                     (and (seq arg) (keyword? (second arg))) (let [opts (apply hash-map (rest arg))]
                                                               (when-not (:as-alias opts)
                                                                 [(join prefix (first arg))]))
                     (seq arg) (let [[prefix' & args] arg
                                     prefix (join prefix prefix')]
                                 (mapcat (partial require->nses prefix) [args]))
                     :else (assert false (print-str "unhandled clause:" (str *ns*) prefix args))))))))

;; map of {ns-symbol #{ns-symbol}}
(def dep-graph (atom {}))

(defn add-ns [ns]
  (swap! dep-graph update ns (fnil identity #{})))

(defn transitive-dependencies [graph ns]
  (loop [stack (list ns)
         tdeps #{}]
    (if-let [ns (first stack)]
      (if (not (contains? tdeps ns))
        (let [stack (pop stack)
              tdeps (conj tdeps ns)
              deps (get graph ns)
              stack (into stack deps)]
          (recur stack tdeps))
        (recur (pop stack) tdeps))
      (disj tdeps ns))))

(defn cycle?
  "returns true if adding a -> b to the graph would cause a cycle"
  [graph a b]
  {:pre [(symbol? a) (symbol? b)]}
  (contains? (set (transitive-dependencies graph b)) a))

(defn reader [resource]
  (-> resource
      io/reader
      java.io.PushbackReader.))

(defn ns-deps- [ns]
  (-> ns
      src-resource
      reader
      parse/read-ns-decl
      parse/deps-from-ns-decl
      (disj ns)))

(def ns-deps (memoize ns-deps-))

(declare prequire)

(defn compile- [ns]
  (let [sha (ns-sha ns)
        classes-dir (->cache-dir sha)]
    (if (compiled? ns)
      (real-require ns)
      (do
        (when (loaded? ns)
          (debug "WARNING:" ns "already loaded before compilation!"
                 :compiled? (compiled? ns)
                 :loaded? (loaded? ns)
                 :bound-require? (bound? #'clojure.core/require)
                 :sha sha))
        (assert (or (not (loaded? ns))
                    (re-find #"^clojure\." (str ns))
                    (re-find #"^rules-clojure\." (str ns))) (print-str ns :compiled? (compiled? ns) :loaded? (loaded? ns) :sha sha))
        (add-classpath! classes-dir)
        (binding [*compile-path* (str classes-dir)]
          (try
            (binding [*compile-path* (str classes-dir)
                      *compile-files* true]
              (real-load (root-resource ns)))
            (catch Exception e
              (println "compile/compile-:" ns e)
              (throw e)))
          (assert (seq (fs/ls-r classes-dir)) (print-str "compile-: no .class files found in" classes-dir "for" ns)))))))

;; map of ns symbol to future.
(def ns-futures (atom {}))

(def ^:dynamic *parallel* true)

(def no-compile (quote
                 #{;; specs are too big to AOT
                   com.cognitect.aws.ec2
                   com.cognitect.aws.rds
                   com.cognitect.aws.servicecatalog
                   com.cognitect.aws.s3
                   com.cognitect.aws.iam
                   cognitect.aws.iam.specs
                   cognitect.aws.s3.specs

                   cider-piggieback
                   clojure.core.specs.alpha

               ;; requires java.awt.headless=true to AOT, but we don't have a good way of passing that to the rules_clojure worker
                   rhizome.viz}))

;;
(def no-parallel (quote #{tech.v3.dataset tech.v3.datatype}))

;; `ns-send` is responsible for making sure each ns is loaded/compiled
;; once. Compilation will happen once, repeat sends to the same ns do
;; nothing

(defn context-classloader-conveyor-fn [f]
  ;; context classloaders are not conveyed by default in futures, but we set it in rules-clojure.jar/compile!
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (fn []
      (.setContextClassLoader (Thread/currentThread) cl)
      (f))))

(defn binding-conveyor-fn [f]
  ;; don't use clojure.core/binding-conveyor-fn, because that uses
  ;; clone/reset ThreadBindings; It allows the conveyed thread to read
  ;; bindings, but not set! them (because it clones TBoxes and the
  ;; cloned tbox stores the thread.id), which breaks
  ;; clojure.lang.Compiler.  Instead, use push/pop ThreadBindings
  ;; which creates new tboxes and allows set! to work.
  (let [bindings (clojure.lang.Var/getThreadBindings)]
    (fn []
      (try
        (clojure.lang.Var/pushThreadBindings bindings)
        (f)
        (finally
          (clojure.lang.Var/popThreadBindings))))))

(def ^:dynamic *executor* nil)

;; The clojure RT won't be garbage collected if we use regular agents
;; or futures, because the new thread's context classloader points at
;; clojure. Use our own pool that is not cached across compile jobs to
;; reduce strong references.

(defn future- [^ExecutorService exec f]
  {:pre [exec]}
  (.submit exec ^Runnable (context-classloader-conveyor-fn
                           (binding-conveyor-fn f))))

(defmacro future [& body]
  `(future- *executor* (fn []
                         ~@body)))

(defn ns-send
  [ns f]
  {:pre [(symbol? ns)]
   :post [(future? %)]}
  (-> ns-futures
      (swap! update ns (fn [**f]
                         (or **f
                             (delay (future
                                      (try
                                        (f)
                                        (catch Throwable t
                                          (throw (Exception. (print-str "in ns-send" ns :parallel? *parallel*) t)))))))))
      (get ns)
      (deref)))

(defn ns-send-sync
  [ns f]
  {:pre [(symbol? ns)]
   :post [(future? %)]}
  (-> ns-futures
      (swap! update ns (fn [**f]
                         (or **f
                             (delay (let [cf (CompletableFuture.)]
                                      (try
                                        (.complete cf (f))
                                        (catch Throwable t
                                          (.completeExceptionally cf t)
                                          (throw (Exception. (print-str "in ns-send" ns :parallel? *parallel*) t))))
                                      cf)))))

      (get ns)
      (deref)))

(defn track-dep!
  "track that ns a depends on ns b. Updates the graph and returns truthy
  if there's no cycle. Does not update the graph and returns false if
  there is"
  [a b]
  {:pre [(symbol? a)
         (symbol? b)]}
  (let [new (swap! dep-graph (fn [graph]
                               (if-not (cycle? graph a b)
                                 (update graph a (fnil conj #{}) b)
                                 graph)))]
    (if (contains? (get new a) b)
      true
      (do
        (debug "WARNING cycle:" a "->" b)
        false))))

(defn pcompile
  "From namespace `parent`, compile `ns`, in parallel"
  [parent ns]
  {:pre [(or (symbol? parent) (nil? parent))
         (symbol? ns)]
   :post [(future? %)]}
  (let [parallel? (and *parallel* (not (contains? no-parallel ns)))
        send (if parallel? ns-send ns-send-sync)]
    (if (or (not parent) (track-dep! parent ns))
      (binding [*parallel* parallel?]
        (send ns (fn []
                   (let [compile? (and (not (contains? no-compile ns))
                                       (not (compiled? ns)))
                         deps (ns-deps ns)]
                     (->> deps
                          (mapv (fn [d]
                                  (let [cycle? (not (track-dep! ns d))
                                        *f (pcompile ns d)]
                                    (when (not cycle?)
                                      ;; don't deref the compile that
                                      ;; cause cycles, the other
                                      ;; thread will take care of it
                                      *f))))
                          (filter identity)
                          (mapv deref))
                     (if compile?
                       (compile- ns)
                       (do
                         (real-require ns)))
                     true))))
      (do
        (debug "compile parent cycle" parent :-> ns)
        (CompletableFuture/completedFuture true)))))

(defn cached?
  "True if this namespace has been compiled by this process, and the class files are cached"
  [ns]
  (boolean (get-cache-dir (ns-sha ns))))

(defn pcopy [dest-dir ns]
  (deref! (pcompile nil ns) 180000
          (ex-info (print-str "pcopy timeout waiting for" ns) {:dest-dir dest-dir :ns ns}))
  (assert (loaded? ns) (print-str ns "not loaded"))
  (let [sha (ns-sha ns)
        cache-dir (get-cache-dir sha)]
    (assert cache-dir (print-str ns "no cache dir"
                                 :compiled? (compiled? ns)
                                 :loaded? (loaded? ns)
                                 :cached? (cached? ns)
                                 :dest dest-dir
                                 :actual-classpath (cp/classpath)))
    (assert (seq (fs/ls-r cache-dir)) (print-str "pcopy: no .class files found in" cache-dir))
    (copy-classes (fs/->path cache-dir) (fs/->path dest-dir))))

(defn prequire
  "given a seq of namespaces, ensure all are compiled (and loaded) in parallel. Blocking."
  [nses]
  {:pre [(every? symbol? nses)]}
  (let [ns-block? (not (contains? (loaded-libs) (symbol (str *ns*))))
        ns-sym (symbol (str *ns*))
        ;; don't hang on self-requires. Silly bug, but it happens in
        ;; real life, and regular compiles don't explode
        nses (disj (set nses) ns-sym)]
    (when-not ns-block?
      (debug ns-sym "loading" nses "at toplevel"))
    (->> nses
         (mapv (partial pcompile ns-sym))
         (mapv (fn [*f]
                 ;; if we hang using 1-arity deref here, bazel doesn't
                 ;; return logging and it's very hard to debug. Set
                 ;; this high enough that it never accidentally
                 ;; triggers
                 (deref! *f 120000 (ex-info (print-str "prequire timeout in" ns-sym "waiting for" nses) {})))))))

(defn load-path
  "Given an argument to `load`, return the string we can pass to `io/resource`"
  [^String p]
  (.substring (if (.startsWith p "/")
                p
                (str (#'clojure.core/root-directory (ns-name *ns*)) \/ p)) 1))

(defn load->ns
  "Given an argument to `load`, attempt to find the source and return the
  namespace, if it contains an `ns` block, else nil. "
  [p]
  {:post [(do (when-not %
                (debug "WARNING no ns found for" p)) true)]}
  (->> [".clj" ".cljc"]
       (keep (fn [ext]
               (io/resource (load-path (str p ext)))))
       (keep (fn [r]
               (with-open [rdr (java.io.PushbackReader. (io/reader r))]
                 (let [ns (parse/name-from-ns-decl (parse/read-ns-decl rdr))]
                   ns))))
       first))

(defn spy-load [& paths]
  (let [ns-sym (symbol (str *ns*))]
    (->> paths
         (mapv (fn [p]
                 (if-let [dep-ns (load->ns p)]
                   @(pcompile ns-sym dep-ns)
                   (real-load p)))))))

(defn spy-require [& args]
  ;; the ns block will add `ns` to clojure.core/*loaded-libs*, so it won't be eval'd twice.
  (prequire (require->nses nil args))
  ;; do this for `alias`, `refer` etc effects
  (apply real-require args))

(def throw-if @#'clojure.core/throw-if)

(defn spy-load-one
  [lib need-ns require]
  (spy-load (root-resource lib))
  (throw-if (and need-ns (not (find-ns lib)))
            "namespace '%s' not found after loading '%s'"
            lib (root-resource lib))
  (when require
    (dosync
     (commute @#'clojure.core/*loaded-libs* conj lib))))

;; we need this because dtype-next calls load-lib directly rather than `load` or `require` ಠ_ಠ
(defn spy-load-lib
  [prefix lib & options]
  (throw-if (and prefix (pos? (.indexOf (name lib) (int \.))))
            "Found lib name '%s' containing period with prefix '%s'.  lib names inside prefix lists must not contain periods"
            (name lib) prefix)
  (let [lib (if prefix (symbol (str prefix \. lib)) lib)
        opts (apply hash-map options)
        {:keys [as reload reload-all require use verbose as-alias]} opts
        loaded (contains? @@#'clojure.core/*loaded-libs* lib)
        need-ns (or as use)
        load-all @#'clojure.core/load-all
        load (cond reload-all load-all
                   reload spy-load-one
                   (not loaded) (cond need-ns spy-load-one
                                      as-alias (fn [lib _need _require] (create-ns lib))
                                      :else spy-load-one))

        filter-opts (select-keys opts '(:exclude :only :rename :refer))
        undefined-on-entry (not (find-ns lib))]
    (binding [clojure.core/*loading-verbosely* (or @#'clojure.core/*loading-verbosely* verbose)]
      (if load
        (try
          (load lib need-ns require)
          (catch Exception e
            (when undefined-on-entry
              (remove-ns lib))
            (throw e)))
        (throw-if (and need-ns (not (find-ns lib)))
                  "namespace '%s' not found" lib))
      (when (and need-ns @#'clojure.core/*loading-verbosely*)
        (printf "(clojure.core/in-ns '%s)\n" (ns-name *ns*)))
      (when as
        (when @#'clojure.core/*loading-verbosely*
          (printf "(clojure.core/alias '%s '%s)\n" as lib))
        (alias as lib))
      (when as-alias
        (when @#'clojure.core/*loading-verbosely*
          (printf "(clojure.core/alias '%s '%s)\n" as-alias lib))
        (alias as-alias lib))
      (when (or use (:refer filter-opts))
        (when @#'clojure.core/*loading-verbosely*
          (printf "(clojure.core/refer '%s" lib)
          (doseq [opt filter-opts]
            (printf " %s '%s" (key opt) (print-str (val opt))))
          (printf ")\n"))
        (apply refer lib (mapcat seq filter-opts))))))

;; same as normal requiring-resolve, except call `spy-require`
;; avoiding the lock
(defn spy-requiring-resolve
  [sym]
  (if (qualified-symbol? sym)
    (or (resolve sym)
        (do (-> sym namespace symbol spy-require)
            (resolve sym)))
    (throw (IllegalArgumentException. (str "Not a qualified symbol: " sym)))))

(defmacro with-spy [& body]
  ;; note that these only work reliably _during_ compilation. We will
  ;; not hook into the loading of AOT'd code, due to static invocation
  `(do
     (.setDynamic #'clojure.core/load)
     ;; for dtype-next
     (.setDynamic #'clojure.core/load-one)
     (.setDynamic #'clojure.core/load-lib)
     (.setDynamic #'clojure.core/require)
     (.setDynamic #'clojure.core/requiring-resolve)
     (binding [clojure.core/load spy-load
               clojure.core/load-lib spy-load-lib
               clojure.core/require spy-require
               clojure.core/requiring-resolve spy-requiring-resolve]
       ~@body)))

;; clojure.lang.Compiler calls `RT.load("clojure/core/specs/alpha")`
;; during macro expansion, if it hasn't been loaded already. Force it
;; to happen here before any user code
(s/fdef force-spec-checking :args (s/cat :x any?) :ret nil?)
(defmacro force-spec-checking [x] x)

(force-spec-checking nil)

(defn compile! [classes-dir aot-nses out]
  {:pre [(string? classes-dir)
         (every? string? aot-nses)]}

  (with-open [executor (Executors/newCachedThreadPool)]
    (binding [*out* out
              *executor* executor]
      (with-spy
        (let [aot-nses (map symbol aot-nses)]
          (doseq [n aot-nses]
            (add-ns n)
            (pcompile nil n))
          (doseq [n aot-nses]
            (pcopy (str classes-dir "/") n)))))))
