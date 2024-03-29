(ns rules-clojure.jar
  (:require [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.namespace.dependency :as dep]
            [clojure.tools.namespace.find :as find]
            [rules-clojure.fs :as fs]
            [rules-clojure.parse :as parse])
  (:import [java.io BufferedOutputStream File FileOutputStream]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute FileTime]
           [java.time LocalDateTime ZoneId]
           [java.util.jar JarEntry JarFile JarOutputStream Manifest]))

(def manifest
  (let [m (Manifest.)]
    (doto (.getMainAttributes m)
      (.putValue "Manifest-Version" "1.0"))
    m))

(def default-file-modified-time-millis
  ;; this timestamp should always be newer than 'now', in case we
  ;; include .clj from 3rd party jars, which have real last modified
  ;; times.
  (-> (LocalDateTime/of 2038 1 1 0 0 0)
      (.atZone (ZoneId/of "UTC"))
      (.toInstant)
      (.toEpochMilli)))

(def default-class-file-modified-time-millis
  (+ default-file-modified-time-millis 2000))

(defn put-next-entry! [^JarOutputStream target ^String name]
  ;; We want reproducible builds and so must use fixed file modification times.
  ;; When both the .class and .clj are present, Clojure loads the one with the
  ;; newer file modification time, so always give class files newer modification times.
  ;;
  ;; Follows the same convention as bazel
  ;; https://github.com/bazelbuild/bazel/blob/d1fdc5303fd3cc22c5091aa4ce7df02eef09d922/src/java_tools/buildjar/java/com/google/devtools/build/buildjar/jarhelper/JarHelper.java#L114-L129
  (let [last-modified-time (if (str/ends-with? name ".class")
                             default-class-file-modified-time-millis
                             default-file-modified-time-millis)]
    (.putNextEntry target
                   (doto (JarEntry. name)
                     (.setLastModifiedTime (FileTime/fromMillis last-modified-time))))))

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
  (let [ret (get all-ns-decls ns)]
    (assert ret (print-str "could not find ns-decl for" ns ". Is it on the classpath?" (keys all-ns-decls)))
    ret))

(defn classpath-resources [classpath]
  {:pre [(every? fs/file? classpath)]}
  (->> classpath
       (mapcat (fn [^File f]
                 (cond
                   (.isDirectory f) (->> (fs/file->path f)
                                         (fs/ls-r)
                                         (map (fn [sub-path]
                                                (-> f
                                                    (fs/file->path)
                                                    (fs/path-relative-to sub-path)
                                                    str))))
                   (re-find #".jar$" (str f)) (-> (JarFile. (str f))
                                                  (.entries)
                                                  (enumeration-seq)
                                                  (->> (map (fn [^JarEntry e]
                                                              (.getName e)))))
                   :else (assert false (print-str "don't know how to deal with p")))))))


(defn classpath-resource
  "Given a seq of classpath files, resolve a resource the way `io/resource` would."
  [classpath resource]
  (->> (classpath-resources classpath)
       (filter (fn [e]
                 (= e resource)))
       first))

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
(s/def ::compile (s/keys :req-un [::aot-nses ::classes-dir ::output-jar] :opt-un [::resources ::src-dir]))

(defn create-jar [{:keys [src-dir classes-dir output-jar resources aot-nses] :as args}]
  {:pre [(s/valid? ::compile args)]}
  (let [temp (Files/createTempFile (fs/dirname output-jar) (fs/filename output-jar) "jar" (into-array FileAttribute []))
        aot-files (->> classes-dir fs/ls-r (filter (fn [p] (-> p fs/path->file .isFile))) sort)
        resources (->> resources (map (fn [r] (fs/->path src-dir r))) (map (fn [p] (fs/path-relative-to src-dir p))) sort)]

    (when (and (seq aot-nses) (not (seq aot-files)))
      (assert false (print-str "create-jar" output-jar "aot-nses" aot-nses "but no aot output files:" classes-dir)))

    (with-open [jar-os (-> temp fs/path->file FileOutputStream. BufferedOutputStream. JarOutputStream.)]
      (put-next-entry! jar-os JarFile/MANIFEST_NAME)
      (.write ^Manifest manifest jar-os)
      (.closeEntry jar-os)
      (doseq [r resources
              :let [^Path full-path (fs/->path src-dir r)
                    file (.toFile full-path)
                    name (str (fs/path-relative-to src-dir full-path))]]
        (assert (fs/exists? full-path) (str full-path))
        (assert (.isFile file))
        (put-next-entry! jar-os name)
        (io/copy file jar-os)
        (.closeEntry jar-os))
      (doseq [^Path path aot-files
              :let [file (.toFile path)
                    name (str (fs/path-relative-to classes-dir path))]]
        (put-next-entry! jar-os name)
        (io/copy file jar-os)
        (.closeEntry jar-os)))
    (fs/mv temp output-jar)
    (assert (fs/exists? output-jar) (print-str "jar not found:" output-jar))))

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

(defn get-compilation-script
  "Returns a string, a script to eval in the compilation env."
  [{:keys [classpath
           classes-dir
           output-jar]} nses]
  {:pre [(every? fs/file? classpath)
         (string? classes-dir)]}

  (let [topo-nses (topo-sort classpath)
        all-ns-decls (ns->ns-decls classpath)
        deps-of (fn [ns]
                  (transitive-deps all-ns-decls ns))
        compile-nses (set nses)
        compile-nses (filter (fn [n]
                               (contains? compile-nses n)) topo-nses) ;; sorted order
        _ (assert (= (count nses) (count compile-nses)) (print-str "couldn't find nses:" (set/difference (set nses) (set compile-nses))))
        script (when (seq compile-nses)
                 `(let [rets# ~(mapv (fn [n] `(do
                                                (require (quote ~'rules-clojure.compile))
                                                (let [ntc# (ns-resolve (quote ~'rules-clojure.compile) (quote ~'non-transitive-compile-json))]
                                                  (assert ntc#)
                                                  (ntc# (quote ~(deps-of n)) (quote ~n))))) compile-nses)]
                    (some identity rets#)))]
    (fs/clean-directory (fs/->path classes-dir))

    `(do
       (let [ns# (create-ns (quote ~(gensym)))]
         (binding [*ns* ns#
                   *compile-path* (str ~classes-dir "/")]
         ~script)))))

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
        (mapcat (fn [^File jar]
                  (map (fn [src]
                         [jar src]) (find/sources-in-jar (JarFile. jar))))))
   (->> cp
        (filter (fn [f] (.isDirectory (io/file f))))
        (mapcat (fn [dir]
                  (map (fn [src]
                         [dir src]) (find/find-sources-in-dir (io/file dir))))))))

(def old-classpath (atom nil))

(defn create-jar-json [json]
  (let [{:keys [src-dir resources aot-nses classes-dir output-jar classpath] :as args} json
        _ (when (seq resources) (assert src-dir))
        aot-nses (map symbol aot-nses)
        classes-dir (fs/->path classes-dir)
        resources (map fs/->path resources)
        output-jar (fs/->path output-jar)]
    (str
     (create-jar (merge
                  {:aot-nses aot-nses
                   :classes-dir classes-dir
                   :classpath classpath
                   :resources resources
                   :output-jar output-jar}
                  (when src-dir
                    {:src-dir (fs/->path src-dir)}))))))

(defn get-compilation-script-json [json]
  (let [{:keys [src-dir resources aot-nses classes-dir output-jar classpath] :as args} json
        aot-nses (map symbol aot-nses)
        classpath-files (map io/file classpath)
        output-jar (fs/->path output-jar)]
    (str (get-compilation-script {:classpath classpath-files
                                  :classes-dir classes-dir
                                  :output-jar output-jar} aot-nses))))

(comment
  (require 'clojure.java.classpath)
  (get-compilation-script {:classpath (clojure.java.classpath/classpath) :classes-dir "target"} '[rules-clojure.jar])
  (eval (read-string (str (get-compilation-script {:classpath (clojure.java.classpath/classpath) :classes-dir "target"} '[rules-clojure.jar])))))
