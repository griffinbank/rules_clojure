(ns rules-clojure.jar
  (:require [clojure.data.json :as json]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            [clojure.stacktrace :as pst]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.tools.namespace.file :as file]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.parse :as parse]
            [clojure.tools.namespace.track :as track]
            [clojure.tools.namespace.dependency :as dep]
            [rules-clojure.fs :as fs])
  (:import clojure.lang.DynamicClassLoader
           clojure.lang.RT
           [java.io BufferedOutputStream FileOutputStream]
           [java.util.jar Manifest JarEntry JarFile JarOutputStream]
           [java.nio.file Files Path Paths FileSystem FileSystems LinkOption]
           [java.nio.file.attribute FileAttribute FileTime]
           java.time.Instant))


;;; Clojure is a compiled language. When loading source files or evaling, the compiler generates a .class file, and then loads it via standard Java classloaders. During non-AOT, the .classfile is in memory without being written to disk.

;;; When AOT'ing, the compiler writes a .class that corresponds to the name of the source file. `foo-bar.core` will produce a file `foo_bar/core.class`.

;;; Classloaders form a hierarchy. A classloader usually asks its parent to load a class, and if the parent can't, the current CL attempts to load. In normal clojure operation, `java -cp` creates a URLClassloader containing all URLs. `clojure.main` then creates a clojure.lang.DynamicClassLoader as a child of the URL classloader.

;;; DCL contains a _static_ (class-wide!) class cache.

;;; clojure.lang.Compiler contains its own private DCL.

;;; To load a namespace, clojure.lang.RT looks for both `foo-bar/core.clj` and `foo_bar/core.class` on the classpath. If only the class file is present, it is loaded. If only the source exists, it is compiled and then loaded. If both are present, the one with the newer file modification time is loaded.

;;; In normal operation, if foo-bar.core was AOT'd, it will be loaded by the URLClassloader (because the .classfile exists). If it had to be compiled, it will be loaded by the DCL.

;;; In the JVM, classes are not unique by name, they are unique by name, _per classloader_. Two classes with the same name in different classloaders will not be identical, which breaks protocols, among other things.

;;; When compiling, defprotocol creates new classes. If a compile reloads a protocol, that breaks all existing users of the protocol. If the protocol is loaded in two separate classloaders, that will break some users.

;;; If an AOT'd ns contains a protocol, the resulting classfile should appear in exactly one jar (if the classfiles appear in multiple jars, there's a chance both definitions could get loaded, and then some users of the protocol will break).

;;; When compiling a user of the protocol, the compiled definition must be on the classpath (because the user needs to refer to the AOT'd class id, not the src class id)

;;; We want to keep the worker up and incrementally load code in the same worker, because reloading is expensive. This is challenging: which classloader do we add URLs to? What happens to the existing compiled class when we add the compiled jar to the runtime for the next class?

;;; clj-tuple assumes it is in the same loader as Clojure. Breaking that assumption causes: `class clojure.lang.PersistentUnrolledVector$Card1 tried to access field clojure.lang.APersistentVector._hash (clojure.lang.PersistentUnrolledVector$Card1 is in unnamed module of loader clojure.lang.DynamicClassLoader @52525845; clojure.lang.APersistentVector is in unnamed module of loader java.net.URLClassLoader @704921a5)`

;;; Therefore, create a new classloader that derives from URLClassloader, but makes addURLs() public. This is the same behavior as clojure.lang.DynamicClassLoader, but DCL includes a cache cache which we do not want in the parent classloader.


(def manifest
  (let [m (Manifest.)]
    (doto (.getMainAttributes m)
      (.putValue "Manifest-Version" "1.0"))
    m))

(defn put-next-entry! [target name last-modified-time]
  ;; set last modified time. When both the .class and .clj are
  ;; present, Clojure loads the one with the newer file modification
  ;; time. This completely breaks reproducible builds because we can't
  ;; set the modified-time to 0 on .class files. Setting to zero means
  ;; if anything on the classpath includes the .clj version, it will
  ;; be loaded because its last-modified timestamp will be non-zero
  (.putNextEntry target
                 (doto (JarEntry. name)
                   (.setLastModifiedTime last-modified-time))))

(defn ns->path [src-dir ns]
  (-> ns
      str
      (str/replace "-" "_")
      (#(fs/->path src-dir %))))

(defn classpath []
  ;; (assert (= classloader (RT/baseLoader)))
  (cp/classpath))

(defn classpath-nses []
  (->>
   (classpath)
   (#(find/find-ns-decls % find/clj))
   (map parse/name-from-ns-decl)))


;; See https://clojure.atlassian.net/browse/CLJ-2303. Compiling is an
;; unconditional `load`. Imagine two namespaces, A, B. A contains a
;; protocol. B depends on A and uses the protocol, and A hasn't been
;; `require`d yet. Compiling B, A, causes (load B) (load A) (load
;; A)). The second load of A redefines any protocols, which then
;; breaks all usage of the protocol in B. Compile in topo-order to
;; avoid forced reloads.


;; TODO: identify when a compile request causes a reload of an existing namespace, and reload all dependent namespaces, using tools.namespace.
(defn topo-sort
  "Given a seq of namespaces to compile, return them in topo sorted order"
  [nses]
  {:pre [(every? symbol? nses)]
   :post [(do (when-not (= (set nses) (set %))
                (println "jar/topo-sort:" (set nses) (set %))) true)
          (= (set nses) (set %))]}
  (let [nses (set nses)
        graph (dep/graph)]
    (->> (classpath)
         (#(find/find-ns-decls % find/clj))
         (filter (fn [decl]
                   (let [ns (parse/name-from-ns-decl decl)]
                     (contains? nses ns))))
         (reduce (fn [graph decl]
                   (let [ns (parse/name-from-ns-decl decl)
                         graph (dep/depend graph ns 'sentinel)]
                     (reduce (fn [graph dep]
                               (dep/depend graph ns dep)) graph (parse/deps-from-ns-decl decl)))) graph)
         (dep/topo-sort)
         (filter (fn [ns]
                   (contains? nses ns))))))

(defn compile-with-clean-classloader [ns]
  ;; we don't want to 'taint' the clojure DCL with any compiled classes, because those will be separate class ids

  (let [cl (DynamicClassLoader. (-> (Thread/currentThread) .getContextClassLoader))]
    (-> (Thread/currentThread) (.setContextClassLoader cl))
    (compile ns)
    (remove-ns ns)))

(defn non-transitive-compile
  [ns]
  {:pre [(symbol? ns)]}
  (when (contains? (loaded-libs) ns)
    (println "WARNING compiling loaded lib" ns))
  (->> (classpath)
       (#(find/find-ns-decls % find/clj))
       (filter (fn [ns-decl]
                 (let [found-ns (-> ns-decl second)]
                   (assert (symbol? found-ns))
                   (= ns found-ns))))
       (first)
       ((fn [ns-decl]
          (let [deps (parse/deps-from-ns-decl ns-decl)]
            (doseq [d deps]
              (println "non-transitive-compile" ns "require" d)
              (require d))
            (compile-with-clean-classloader ns))))))

;; directory, root where all src and resources will be found
(s/def ::src-dir fs/path?)

;; path to the file, relative to workspace root. Path inside the jar will be relative-to src-dir
(s/def ::resource ::fs/path)

(s/def ::resources (s/coll-of ::resource))

;; seq of namespaces to AOT
(s/def ::aot-nses (s/coll-of symbol?))

;; path to `set!` *compile-path* to
(s/def ::classes-dir fs/path?)

(s/def ::output-jar fs/path?)

;; Doesn't take `::srcs`, assumes they are already on the classpath
(s/def ::compile (s/keys :req-un [::resources ::aot-nses ::classes-dir ::output-jar] :opt-un [::src-dir]))

(defn aot-files
  "Given the class-dir, post compiling `aot-ns`, return the files that should go in the JAR"
  [classes-dir aot-ns]
  (->> classes-dir
       .toFile
       file-seq))

(defn all-nses []
  (->> (classpath)
       (#(find/find-namespaces % find/clj))))

(defn load-classpath [{:keys [classpath]}]
  (doseq [c classpath]
    (.addURL (RT/baseLoader) (-> c io/file .toURI .toURL))))

(defn do-aot [{:keys [classes-dir aot-nses]}]
  (when (seq aot-nses)
    (binding [*compile-path* (str classes-dir "/")]
      (doseq [ns (topo-sort aot-nses)]
        (try
          (compile ns)
          (catch Throwable t
            (println "while compiling" ns)
            (pst/print-stack-trace t)
            (throw t)))))))

(defn create-jar [{:keys [src-dir classes-dir output-jar resources aot-nses]}]
  (with-open [jar-os (-> output-jar .toFile FileOutputStream. BufferedOutputStream. JarOutputStream.)]
    (put-next-entry! jar-os JarFile/MANIFEST_NAME (FileTime/from (Instant/now)))
    (.write manifest jar-os)
    (.closeEntry jar-os)
    (doseq [r resources
            :let [full-path (fs/->path src-dir r)
                  file (.toFile full-path)
                  name (str (fs/path-relative-to src-dir full-path))]]
      (assert (fs/exists? full-path) (str full-path))
      (assert (.isFile file))
      (put-next-entry! jar-os name (Files/getLastModifiedTime full-path (into-array LinkOption [])))
      (io/copy file jar-os)
      (.closeEntry jar-os))
    (doseq [file (->> aot-nses
                      (mapcat (fn [ns] (aot-files classes-dir ns)))
                      (distinct))
            :when (.isFile file)
            :let [path (.toPath file)
                  name (str (fs/path-relative-to classes-dir path))]]
      (put-next-entry! jar-os name (Files/getLastModifiedTime path (into-array LinkOption [])))
      (io/copy file jar-os)
      (.closeEntry jar-os))))

(s/fdef compile! :args ::compile)
(defn compile!
  ""
  [{:keys [src-dir resources aot-nses classes-dir output-jar classpath] :as args}]
  (when-not (s/valid? ::compile args)
    (println "args:" args)
    (s/explain ::compile args)
    (assert false))

  (fs/ensure-directory classes-dir)

  (load-classpath (select-keys args [:classpath]))

  (do-aot (select-keys args [:classes-dir :aot-nses]))
  (create-jar (select-keys args [:src-dir :classes-dir :output-jar :resources :aot-nses]))

  true)

(defn compile-json [json-str]
  (let [{:keys [src_dir resources aot_nses classes_dir output_jar classpath] :as args} (json/read-str json-str :key-fn keyword)
        _ (assert classes_dir)
        _ (when (seq resources) (assert src_dir))
        classes-dir (fs/->path classes_dir)
        resources (map fs/->path resources)
        output-jar (fs/->path output_jar)
        aot-nses (map symbol aot_nses)]
    (compile! (merge
               {:classes-dir classes-dir
                :classpath classpath
                :resources resources
                :output-jar output-jar
                :aot-nses aot-nses}
               (when src_dir
                 {:src-dir (fs/->path src_dir)})))))
