(ns rules-clojure.jar
  (:require [clojure.data.json :as json]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            [clojure.set :as set]
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
           [java.io BufferedOutputStream FileOutputStream File]
           [java.util.jar Manifest JarEntry JarFile JarOutputStream]
           [java.nio.file Files Path Paths FileSystem FileSystems LinkOption]
           [java.nio.file.attribute FileAttribute FileTime]
           java.time.Instant))

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
  ;; if anything on the classpath includes the .clj version, the .clj
  ;; will be loaded because its last-modified timestamp will be
  ;; non-zero
  (.putNextEntry target
                 (doto (JarEntry. name)
                   (.setLastModifiedTime last-modified-time))))

;; See https://clojure.atlassian.net/browse/CLJ-2303. Compiling is an
;; unconditional `load`. Imagine two namespaces, A, B. A contains a
;; protocol. B depends on A and uses the protocol, and A hasn't been
;; `require`d yet. Compiling B, A, causes (load B) (load A) (load
;; A)). The second load of A redefines any protocols, which then
;; breaks all usage of the protocol in B. Compile in topo-order to
;; avoid reloads.

(defn topo-sort
  "Return nses on the classpath in topo-sorted order"
  [classpath]
  {:pre [(every? fs/file? classpath)]}
  (let [graph (dep/graph)]
    (->> classpath
         (#(find/find-ns-decls % find/clj))
         (reduce (fn [graph decl]
                   (let [ns (parse/name-from-ns-decl decl)
                         graph (dep/depend graph ns 'sentinel)]
                     (reduce (fn [graph dep]
                               (dep/depend graph ns dep)) graph (parse/deps-from-ns-decl decl)))) graph)
         (dep/topo-sort))))



(defn ns->ns-decls [classpath-files]
  (->> classpath-files
       (#(find/find-ns-decls % find/clj))
       (map (fn [decl]
              [(parse/name-from-ns-decl decl) decl]))
       (into {})))

(defn get-ns-decl [all-ns-decls ns]
  {:post [%]}
  (get all-ns-decls ns))

(defn verify-compile!
  "the clojure compiler works by binding *compile-files* true and then
  calling `load`. `load` looks for both the source file and .class. If
  the .class file is present it is loaded as a normal java class If
  both are present, If the src file is present , the compiler runs,
  and .class files are produced as a side effect of the load. If both
  are present, the newer one is loaded.

  If the .class file is loaded, the compiler will not run and no
  .class files will be produced. Detect that situation and throw"
  [classpath ns]
  (let [root (.substring (#'clojure.core/root-resource ns) 1)
        src-paths (map (fn [ex] (str root ex)) [".clj" ".cljc"])
        src-resource (some (fn [p] (io/resource p)) src-paths)
        class-resource (io/resource (str root "__init.class"))
        classpath-str (str/join "\n" (map str classpath))]

    (assert src-resource (print-str "no src for" ns "on classpath"))
    (assert (not class-resource) (print-str "already AOT compiled" ns "on classpath, this will nop" "existing:" class-resource))))

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
  [classes-dir]
  (->> classes-dir
       .toFile
       file-seq))

(defn create-jar [{:keys [src-dir classes-dir output-jar resources aot-nses]}]
  (let [temp (File/createTempFile (fs/filename output-jar) "jar")]
    (with-open [jar-os (-> temp FileOutputStream. BufferedOutputStream. JarOutputStream.)]
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
      (doseq [file (aot-files classes-dir)
              :when (.isFile file)
              :let [path (.toPath file)
                    name (str (fs/path-relative-to classes-dir path))]]
        (put-next-entry! jar-os name (Files/getLastModifiedTime path (into-array LinkOption [])))
        (io/copy file jar-os)
        (.closeEntry jar-os)))
    (fs/mv (.toPath temp) output-jar)))

(defn direct-deps-of [all-ns-decls ns]
  (mapcat #'parse/deps-from-ns-form (get-ns-decl all-ns-decls ns)))

(defn transitive-deps [all-decls ns]
  (loop [ns ns
         tdeps (list)
         stack (list ns)
         seen #{}]
    (if-let [ns (first stack)]
      (if (not (contains? seen ns))
        (let [stack (pop stack)
              tdeps (conj tdeps ns)
              deps (direct-deps-of all-decls ns)
              stack (into stack (reverse deps))
              seen (conj seen ns)]
          (recur (peek stack) tdeps stack seen))
        (recur (peek stack) tdeps (pop stack) seen))
      (butlast tdeps))))

(defn read-all [stream]
  (let [ret (read stream false ::eof)]
    (when (not= ret ::eof)
      (lazy-cat [ret] (read-all stream)))))

(defn get-preamble []
  (read-all (java.io.PushbackReader. ;; (io/reader (io/file "/Users/arohner/Programming/rules_clojure/src/rules_clojure/compile.clj"))
                                     (io/reader (io/resource "rules_clojure/compile.clj"))
                                     )))

(defn get-compilation-script
  "Returns a string, a script to eval in the compilation env."
  [{:keys [classpath
           classes-dir]} nses]
  (assert (every? fs/file? classpath))
  (assert (string? classes-dir))

  (let [topo-nses (topo-sort classpath)
        all-ns-decls (ns->ns-decls classpath)
        deps-of (fn [ns]
                  (transitive-deps all-ns-decls ns))
        compile-nses (set nses)
        compile-nses (filter (fn [n]
                               (contains? compile-nses n)) topo-nses) ;; sorted order
        _ (assert (= (count nses) (count compile-nses)))
        preamble (get-preamble)
        script (if (seq compile-nses)
                 (map (fn [n] `(~'non-transitive-compile (quote ~(deps-of n)) (quote ~n))) compile-nses)
                 [nil])]
    (fs/clean-directory (fs/->path classes-dir))

    `(do
       (binding [*ns* 'user
                 *compile-path* (str ~classes-dir "/")]
         ~@(get-preamble)
         ~@script))))

(s/fdef compile! :args ::compile)
(defn create-jar!
  ""
  [{:keys [src-dir resources aot-nses classes-dir output-jar] :as args}]
  (when-not (s/valid? ::compile args)
    (println "args:" args)
    (s/explain ::compile args)
    (assert false))

  (create-jar (select-keys args [:src-dir :classes-dir :output-jar :resources :aot-nses])))

(defn find-sources [cp]
  (concat
   (->> cp
        (filter (fn [f] (cp/jar-file? (io/file f))))
        (mapcat (fn [jar]
                  (map (fn [src]
                         [jar src]) (find/sources-in-jar (JarFile. jar))))))
   (->> cp
        (filter (fn [f] (.isDirectory (io/file f))))
        (mapcat (fn [dir]
                  (map (fn [src]
                         [dir src]) (find/find-sources-in-dir (io/file dir))))))))

(def old-classpath (atom nil))

(defn create-jar-json [json-str]
  (let [{:keys [src_dir resources aot_nses classes_dir output_jar classpath] :as args} (json/read-str json-str :key-fn keyword)
        _ (assert classes_dir)
        _ (when (seq resources) (assert src_dir))
        classes-dir (fs/->path classes_dir)
        resources (map fs/->path resources)
        output-jar (fs/->path output_jar)]
    (str
     (create-jar (merge
                  {:classes-dir classes-dir
                   :classpath classpath
                   :resources resources
                   :output-jar output-jar}
                  (when src_dir
                    {:src-dir (fs/->path src_dir)}))))))

(defn get-compilation-script-json [json-str]
  (let [{:keys [src_dir resources aot_nses classes_dir compile_classpath] :as args} (json/read-str json-str :key-fn keyword)
        aot-nses (map symbol aot_nses)
        classpath-files (map io/file compile_classpath)]
    (str (get-compilation-script {:classpath classpath-files
                                  :classes-dir classes_dir} aot-nses))))

(comment
  (require 'clojure.java.classpath)
  (eval (read-string (str (get-compilation-script {:classpath (clojure.java.classpath/classpath) :classes-dir "target"} '[rules-clojure.jar])))))
