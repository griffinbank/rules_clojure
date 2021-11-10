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

(defn classpath []
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


(defn classpath-nses []
  (->> (classpath)
       (find/find-namespaces)))

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

(defn get-context-classloader []
  (-> (Thread/currentThread) .getContextClassLoader))

(defn protocol? [val]
  (and (map? val)
       (class? (:on val))
       (class? (:on-interface val))
       (map? (:sigs val))))

(defn contains-protocols? [ns]
  (->> ns
       ns-interns
       vals
       (some protocol?)))

(defn deftype? [ns v]
  (and (class? v)
       (-> v
           (.getName)
           (str/starts-with? (munge (name ns))))
       (= (count (str/split (.getName v) #"\."))
          (inc (count (str/split (name ns) #"\."))))))

(defn contains-deftypes? [ns]
  (->> ns
       ns-map
       vals
       (some (fn [v]
               (deftype? ns v)))))

(defn non-transitive-compile
  "By default, `compile` compiles all dependencies of ns. This is
  non-deterministic, so require all dependencies
  first. Returns ::reload when ClojureWorker should discard the
  environment"
  [ns]
  {:pre [(symbol? ns)]}
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
              (try
                (require d)
                (catch Exception e
                  (throw (ex-info "while requiring" {:ns d} e)))))
            (compile ns)
            (when (or (contains-protocols? ns)
                      (contains-deftypes? ns))
              ::reload))))))

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

(defn do-aot [{:keys [classes-dir aot-nses]}]
  (when (seq aot-nses)
    (binding [*compile-files* true
              *compile-path* (str classes-dir "/")]
      (->> aot-nses
           topo-sort
           (map (fn [ns]
                  (try
                    (non-transitive-compile ns)
                    (catch Throwable t
                      (println "while compiling" ns)
                      (println "classpath:" (str/join "\n" (classpath)))
                      (pst/print-stack-trace t)
                      (throw t)))))
           (doall)
           (filter identity)
           first))))

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
      (doseq [file (->> aot-nses
                        (mapcat (fn [ns]
                                  {:post [(do (when (not (seq %))
                                                (println "ERROR no files from compiling" ns)) true)
                                          (seq %)]}
                                  (aot-files classes-dir ns)))
                        (distinct))
              :when (.isFile file)
              :let [path (.toPath file)
                    name (str (fs/path-relative-to classes-dir path))]]
        (put-next-entry! jar-os name (Files/getLastModifiedTime path (into-array LinkOption [])))
        (io/copy file jar-os)
        (.closeEntry jar-os)))
    (fs/mv (.toPath temp) output-jar)))

(s/fdef compile! :args ::compile)
(defn compile!
  ""
  [{:keys [src-dir resources aot-nses classes-dir output-jar classpath] :as args}]
  (when-not (s/valid? ::compile args)
    (println "args:" args)
    (s/explain ::compile args)
    (assert false))

  (fs/ensure-directory classes-dir)

  (let [needs-reload? (do-aot (select-keys args [:classes-dir :aot-nses]))]
    (create-jar (select-keys args [:src-dir :classes-dir :output-jar :resources :aot-nses]))
    needs-reload?))

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

(defn data-readers-on-classpath
  "Given the classpath, return the set of jars and dirs that contain data_readers.clj"
  [cp]
  (->> (find-sources cp)
       (filter (fn [[f src]]
                 (= "data_readers.clj" src)))
       (map first)
       seq))

(def old-classpath (atom nil))

(defn compile-json [json-str]
  (let [{:keys [src_dir resources aot_nses classes_dir output_jar classpath] :as args} (json/read-str json-str :key-fn keyword)
        _ (assert classes_dir)
        _ (when (seq resources) (assert src_dir))
        classes-dir (fs/->path classes_dir)
        resources (map fs/->path resources)
        output-jar (fs/->path output_jar)
        aot-nses (map symbol aot_nses)]
    (#'clojure.core/load-data-readers)
    (str
     (let [ret (compile! (merge
                          {:classes-dir classes-dir
                           :classpath classpath
                           :resources resources
                           :output-jar output-jar
                           :aot-nses aot-nses}
                          (when src_dir
                            {:src-dir (fs/->path src_dir)})))]
       (reset! old-classpath classpath)
       ret))))
