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
  (:import [java.io BufferedOutputStream FileOutputStream]
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

;; See https://clojure.atlassian.net/browse/CLJ-2303. Compiling is an
;; unconditional `load`. Imagine two namespaces, A, B. A contains a
;; protocol. B depends on A and uses the protocol, and A hasn't been
;; `require`d yet. Compiling B, A, causes (load B) (load A) (load
;; A)). The second load of A redefines any protocols, which then
;; breaks all usage of the protocol in B. Compile in topo-order to
;; avoid forced reloads.

(defn classpath-nses []
  (->>
   (cp/classpath)
   (#(find/find-ns-decls % find/clj))
   (map parse/name-from-ns-decl)))

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
    (->> (cp/classpath)
         (#(find/find-ns-decls % find/clj))
         (filter (fn [decl]
                   (let [ns (parse/name-from-ns-decl decl)]
                     (contains? nses ns))))
         (reduce (fn [graph decl]
                   (let [ns (parse/name-from-ns-decl decl)
                         ;; make sure the parent namespace is in the
                         ;; graph or topo-sort will return an empty
                         ;; list, not containing the parent. Pick
                         ;; clojure.core as a dummy node
                         graph (dep/depend graph ns 'clojure.core)]
                     (reduce (fn [graph dep]
                               (dep/depend graph ns dep)) graph (parse/deps-from-ns-decl decl)))) graph)
         (dep/topo-sort)
         (filter (fn [ns]
                   (contains? nses ns))))))

(defn non-transitive-compile
  [ns]
  {:pre [(symbol? ns)]}
  (->> (cp/classpath)
       (find/find-ns-decls)
       (filter (fn [ns-decl]
                 (let [found-ns (-> ns-decl second)]
                   (assert (symbol? found-ns))
                   (= ns found-ns))))
       (first)
       ((fn [ns-decl]
          (let [deps (parse/deps-from-ns-decl ns-decl)]
            ;; (println "non-transitive-compile for ns" ns "require" deps)
            (doseq [d deps]
              (require d))
            (compile ns))))))

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
  "Given the class-dir, post compiling `aot-ns`, return only the files that should go in the JAR"
  [classes-dir aot-ns]
  (let [internal-name (str classes-dir "/" (-> aot-ns
                                               (str/replace "-" "_")
                                               (str/replace "." "/")))]
    (->> classes-dir
         .toFile
         file-seq)))

(s/fdef compile! :args ::compile)
(defn compile!
  ""
  [{:keys [src-dir resources aot-nses classes-dir output-jar] :as args}]
  (when-not (s/valid? ::compile args)
    (println "args:" args)
    (s/explain ::compile args)
    (assert false))

  (fs/ensure-directory classes-dir)

  (when (seq aot-nses)
    (binding [*compile-path* (str classes-dir "/")]
      (doseq [ns (topo-sort aot-nses)]
        (try
          (compile ns)
          (catch Throwable t
            (println "while compiling" ns)
            (pst/print-stack-trace t)

            (throw t))))))

  (with-open [jar-os (-> output-jar .toFile FileOutputStream. BufferedOutputStream. JarOutputStream.)]
    (put-next-entry! jar-os JarFile/MANIFEST_NAME (FileTime/from (Instant/now)))
    (.write manifest jar-os)
    (.closeEntry jar-os)
    (doseq [r resources
            :let [full-path (fs/->path src-dir r)
                  file (.toFile full-path)]]
      (assert (fs/exists? full-path) (str full-path))
      (assert (.isFile file))
      (put-next-entry! jar-os (str full-path) (Files/getLastModifiedTime full-path (into-array LinkOption [])))
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
      (.closeEntry jar-os)))
  true)

(defn compile-json [json-str]
  (let [{:keys [src_dir resources aot_nses classes_dir output_jar] :as args} (json/read-str json-str :key-fn keyword)
        _ (assert classes_dir)
        _ (when (seq resources) (assert src_dir))
        classes-dir (fs/->path classes_dir)
        resources (map fs/->path resources)
        output-jar (fs/->path output_jar)
        aot-nses (map symbol aot_nses)]
    (compile! (merge
               {:classes-dir classes-dir
                :resources resources
                :output-jar output-jar
                :aot-nses aot-nses}
               (when src_dir
                 {:src-dir (fs/->path src_dir)})))))
