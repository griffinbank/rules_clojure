(ns rules-clojure.jar
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.classpath :as cp]
            [clojure.tools.namespace.file :as file]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.parse :as parse]
            [clojure.tools.namespace.track :as track]
            [clojure.tools.namespace.dependency :as dep])
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
  ;; time. This completely breaks reproducible builds, but not setting
  ;; means if any dependency includes the .clj version, that will be
  ;; loaded because its last-modified timestamp will be
  ;; non-zero
  (.putNextEntry target
                 (doto (JarEntry. name)
                   (.setLastModifiedTime last-modified-time))))

(defn ->path [& dirs]
  (let [[d & dr] dirs
        d (if
            (string? d) (Paths/get d (into-array String []))
            d)]
    (reduce (fn [^Path p dir] (.resolve p dir)) d (rest dirs))))

(defn path->file [p]
  (.toFile p))

(defn absolute [path]
  (.toAbsolutePath path))

(defn exists? [path]
  (Files/exists path (into-array java.nio.file.LinkOption [])))

(defn dirname [path]
  (.getParent path))

(defn path-relative-to
  [a b]
  (.relativize (absolute a) (absolute b)))

(defn mkdir [path]
  (Files/createDirectory path (into-array FileAttribute [])))

(defn ns->path [src-dir ns]
  (-> ns
      str
      (str/replace "-" "_")
      (#(->path src-dir %))))

;; https://clojure.atlassian.net/browse/CLJ-2303

(defn topo-sort
  "Given a seq of namespaces to compile, return them in topo sorted order"
  [nses]
  {:pre [(every? symbol? nses)]
   :post [(do (println "jar/topo-sort:" (set nses) (set %)) true)
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
                         ;; list. Pick clojure.core as a dummy node
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
            (doseq [d deps]
              (require d))
            (compile ns))))))

(defn compile! [json]
  (let [args (json/read-str json :key-fn keyword)
        {:keys [src_dir srcs resources aot classes_dir output_jar]} args
        classes-dir (->path classes_dir)
        src-dir (->path src_dir)
        output-jar (->path output_jar)
        aot (map symbol aot)]
    (when (seq aot)
      (binding [*compile-path* (str classes-dir "/")]
        (mkdir classes-dir)

        (doseq [ns (topo-sort aot)]
          ;; (compile ns)
          (non-transitive-compile ns))))

    (with-open [jar-os (-> output-jar .toFile FileOutputStream. BufferedOutputStream. JarOutputStream.)]
      (put-next-entry! jar-os JarFile/MANIFEST_NAME (FileTime/from (Instant/now)))
      (.write manifest jar-os)
      (.closeEntry jar-os)

      (doseq [r resources
              :let [full-path (->path src-dir r)
                    file (.toFile full-path)]]
        (assert (exists? full-path) (str full-path))
        (assert (.isFile file))

        (put-next-entry! jar-os r (Files/getLastModifiedTime full-path (into-array LinkOption [])))
        (io/copy file jar-os)
        (.closeEntry jar-os))
      (doseq [file (-> classes-dir .toFile file-seq)
              :when (.isFile file)
              :let [path (.toPath file)
                    name (str (path-relative-to classes-dir path))]]
        (put-next-entry! jar-os name (Files/getLastModifiedTime path (into-array LinkOption [])))
        (io/copy file jar-os)
        (.closeEntry jar-os)))))
