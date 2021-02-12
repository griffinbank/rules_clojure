(ns rules-clojure.gen-build
  "Tools for generating BUILD.bazel files for clojure deps"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.set :as set]
            [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.util.concurrent :as concurrent]
            [clojure.tools.namespace.parse :as parse]
            [clojure.tools.namespace.find :as find])
  (:import java.io.File
           [clojure.lang Keyword IPersistentVector IPersistentList IPersistentMap Var]
           [java.nio.file Files Path Paths FileSystem FileSystems]
           java.nio.file.attribute.FileAttribute
           [java.util.jar JarFile]))

(defn path? [x]
  (instance? Path x))

(defn file? [x]
  (instance? File x))

(s/fdef absolute? :args (s/cat :p path?) :ret boolean?)
(defn absolute? [path]
  (.isAbsolute path))

(s/def ::absolute-path (s/and path? absolute?))

(s/def ::ns-path (s/map-of symbol? ::absolute-path))
(s/def ::read-deps map?)
(s/def ::aliases (s/coll-of keyword?))

(s/def ::paths (s/coll-of string?))
(s/def ::library qualified-symbol?)
(s/def ::lib->deps (s/map-of ::library (s/coll-of ::library)))

(s/def ::deps-info (s/keys :req-un [::paths]))
(s/def ::jar->lib (s/map-of ::absolute-path ::library))
(s/def ::workspace-root ::absolute-path)
(s/def ::deps-repo-tag string?)
(s/def ::deps-edn-path ::absolute-path)
(s/def ::deps-out-dir ::absolute-path)
(s/def ::deps-build-dir ::absolute-path)

(s/def ::classpath (s/map-of ::absolute-path map?))
(s/def ::basis (s/keys :req-un [::classpath]))
(s/def ::aliases (s/coll-of keyword?))

(defmacro throw-if-not! [expr msg data]
  `(when (not ~expr)
     (throw (ex-info ~msg ~data))))

(defn ! [x]
  (or x (throw-if-not! x "false" {:value x})))

(defn get! [m k]
  (throw-if-not! (find m k) "couldn't find key" {:map m :key k})
  (get m k))

(defn first! [coll]
  (throw-if-not! (seq coll) "no first in coll" {:coll coll})
  (first coll))

(s/fdef ->path :args (s/cat :dirs (s/* (s/alt :s string? :p path?))) :ret path?)
(defn ->path [& dirs]
  (let [[d & dr] dirs
        d (if
              (string? d) (Paths/get d (into-array String []))
              d)]
    (reduce (fn [^Path p dir] (.resolve p dir)) d (rest dirs))))

(defn file->path [f]
  (.toPath f))

(defn path->file [p]
  (.toFile p))

(s/fdef absolute :args (s/cat :p path?) :ret path?)
(defn absolute [path]
  (.toAbsolutePath path))

(s/fdef relative-to :args (s/cat :a path? :b path?) :ret path?)
(defn path-relative-to
  [a b]
  {:pre []}
  (.relativize (absolute a) (absolute b)))

(s/fdef normal-file? :args (s/cat :f file?) :ret boolean?)
(defn normal-file? [file]
  (.isFile file))

(s/fdef directory? :args (s/cat :f file?) :ret boolean?)
(defn directory? [file]
  (.isDirectory file))

(defn create-directory [path]
  (Files/createDirectory path (into-array java.nio.file.attribute.FileAttribute [])))

(defn exists? [path]
  (Files/exists path (into-array java.nio.file.LinkOption [])))

(defn ensure-directory [path]
  (when-not (exists? path)
    (create-directory path)))

(s/fdef filename :args (s/cat :p path?) :ret string?)
(defn filename [path]
  (-> path
      .getFileName
      str))

(defn dirname [path]
  (.getParent path))

(s/fdef extension :args (s/cat :p path?) :ret string?)
(defn extension [path]
  (-> path
      filename
      (str/split #"\.")
      rest
      last))

(defn basename [path]
  (-> path
      filename
      (str/split #"\.")
      first))

(s/fdef ls :args (s/cat :d path?) :ret (s/coll-of path?))
(defn ls [^Path dir]
  (-> dir
      .toFile
      .listFiles
      (->>
       (map (fn [^File f]
              (.toPath f))))))

(s/fdef ls-r :args (s/cat :d path?) :ret (s/coll-of path?))
(defn ls-r
  "recursive list"
  [dir]
  (->> dir
       ls
       (mapcat (fn [path]
                 (if (-> path .toFile directory?)
                   (concat [path] (ls-r path))
                   [path])))))

(s/fdef jar? :args (s/cat :f path?) :ret boolean?)
(defn jar? [path]
  (= "jar" (extension path)))

(s/fdef clj-file? :args (s/cat :f file?) :ret boolean?)
(defn clj-file? [file]
  (and (normal-file? file)
       (contains? #{"clj" "cljc"} (-> file file->path extension))))

(defn emit-bazel-dispatch [x]
  (class x))

(defmulti emit-bazel* #'emit-bazel-dispatch)

(defmethod emit-bazel* :default [x]
  (assert false (print-str "don't know how to emit" (class x))))

(defmethod emit-bazel* String [x]
  (pr-str x))

(defmethod emit-bazel* Keyword [x]
  (name x))

(defmethod emit-bazel* Path [x]
  (-> x str pr-str))

(defmethod emit-bazel* Boolean [x]
  (case x
    true "True"
    false "False"))

(defn emit-bazel-kwargs [x]
  {:pre [(map? x)]
   :post [(string? %)]}
  x
  (->> x
       (map (fn [[k v]]
              (print-str (emit-bazel* k) "=" (emit-bazel* v))))
       (interpose ",\n\t")
       (apply str)))

(defmethod emit-bazel* IPersistentList [[name & args]]
  ;; function call
  (let [args (if (seq args)
               (conj (mapv emit-bazel* (butlast args)) (if (map? (last args))
                                                         (emit-bazel-kwargs (last args))
                                                         (emit-bazel* (last args)))))]
    (str name "(" (apply str (interpose ", " args)) ")")))

(defmethod emit-bazel* IPersistentVector [x]
  (str "[" (->> x
                (map emit-bazel*)
                (interpose ",")
                (apply str)) "]"))

(defmethod emit-bazel* IPersistentMap [x]
  (str "{" (->> x
                (map (fn [[k v]]
                       (str (emit-bazel* k) " : " (emit-bazel* v))))
                (interpose ",")
                (apply str)) "}"))

(defn emit-bazel
  "Given a string name and a dictionary of arguments, return a string of bazel"
  [x]
  (emit-bazel* x))

(defn resolve-src-location
  "Given a directory on the classpath and an ns, return the path to the file inside the classpath"
  [src-dir ns]
  (-> (str src-dir "/" (-> (str ns)
                           (str/replace #"\." "/")
                           (str/replace #"-" "_")) ".clj")
      ->path
      absolute))

(s/fdef ->ns->path :args (s/cat :b ::basis) :ret ::ns-path)
(defn ->ns->path
  "Given a classpath string, return a map of namespace symbols to the .clj / .jar containing it on the classpath. `cp` is a classpath string"
  [basis]
  (->> basis
       :classpath
       (mapcat (fn [[path _]]
                 (let [file (.toFile path)
                       nses (find/find-namespaces [file])]
                   (map (fn [n]
                          ;; the classpath contains source directories, but we'd prefer source files here so we can look up ns->file directly
                          (if (directory? file)
                            [n (resolve-src-location path n)]
                            [n (->path path)])) nses))))
       (into {})))

(s/fdef read-deps :args (s/cat :p path?))
(defn read-deps [deps-path]
  (-> deps-path
      path->file
      slurp
      read-string))

(s/def ::target string?)
(s/def ::target-info (s/map-of keyword? any?))
(s/def ::extra-deps (s/map-of ::target ::target-info))
(s/def ::deps-bazel (s/keys :opt-un [::extra-deps]))

(defn parse-deps-bazel
  "extra data under `:bazel` in a deps.edn file for controlling gen-build. Supported keys:

  :extra-deps - (map-of bazel-target to (s/keys :opt-un [:srcs :deps])), extra targets to include on a clojure_library. This is useful for e.g. adding native library dependencies onto a .clj file"
  [read-deps]
  {:post [(s/valid? ::deps-bazel %)]}
  (or (:bazel read-deps) {}))

(defn find-nses [classpath]
  (find/find-namespaces (map io/file (str/split classpath #":"))))

(defn locate-file
  "starting in path, traverse parent directories until finding a file named `name`. Return the path or nil"
  [path name]
  (let [orig (.getAbsoluteFile path)]
    (loop [path orig]
      (if (seq (str path))
        (let [dir (dirname path)
              path (io/file dir name)]
          (if (exists? (io/file path))
            path
            (recur (dirname (dirname path)))))
        (assert false (print-str "could not find " name " above" orig))))))

(s/fdef ->jar->lib :args (s/cat :b ::basis) :ret ::jar->lib)
(defn ->jar->lib
  "Return a map of jar path to library name ('org.clojure/clojure)"
  [basis]
  {:post [(s/valid? ::jar->lib %)]}
  (->> basis
       :classpath
       (map (fn [[path {:keys [path-key lib-name]}]]
              (when lib-name
                [(->path path) lib-name])))
       (filter identity)
       (into {})))

(defn ->lib->jar
  "Return a map of library name to jar"
  [jar->lib]
  (set/map-invert jar->lib))

(defn jar-classes
  "given the path to a jar, return a list of classes contained"
  [path]
  (-> (JarFile. (str path))
      (.entries)
      (enumeration-seq)
      (->>
       (map (fn [e]
              (when-let [[_ class-name] (re-find #"(.+).class$" (.getName e))]
                class-name)))
       (filter identity)
       (map (fn [e]
              (-> e
                  (str/replace "/" ".")
                  symbol))))))

(s/def ::class->jar (s/map-of symbol? path?))
(s/fdef ->class->jar :args (s/cat :b ::basis) :ret ::class->jar)
(defn ->class->jar
  "returns a map of class symbol to jarpath for all jars on the classpath"
  [basis]
  {:post [(s/valid? ::class->jar %)]}
  (->> basis
       :classpath
       (mapcat (fn [[path {:keys [lib-name]}]]
                 (when lib-name
                   (->> (jar-classes path)
                        (map (fn [c]
                               [c (->path path)]))))))
       (into {})))

(defn expand-deps- [basis]
  (let [ex-svc (concurrent/new-executor 2)]
    (#'deps/expand-deps (:deps basis) nil nil (select-keys basis [:mvn/repos]) ex-svc true)))

(defn ->lib->deps
  "Return a map of library-name to dependencies of lib-name"
  [basis]
  (->> basis
       :libs
       meta
       :trace
       :log
       (reduce
        (fn [dep-map {:keys [path lib reason]}]
          (let [parent (last path)
                child lib
                exclude-reasons #{:excluded :parent-omitted}]
            (if (and parent (not (contains? exclude-reasons reason)))
              (do
                (assert (symbol? parent))
                (assert (symbol? child))
                (update dep-map parent (fnil conj #{}) child)
                )
              ;; empty parent means this is a toplevel dep, already covered elsewhere
              dep-map)
            )) {})))

(s/fdef src->label :args (s/cat :a (s/keys :req-un [::workspace-root]) :p path?) :ret string?)
(defn src->label [{:keys [workspace-root]} path]
  (let [path (path-relative-to workspace-root path)]
    (str "//" (dirname path) ":" (str (basename path)))))

(s/fdef library->label :args (s/cat :p symbol?) :ret string?)
(defn library->label
  "given the name of a library, e.g. `org.clojure/clojure`, munge the name into a bazel label"
  [lib-name]
  (-> lib-name
      (str/replace #"-" "_")
      (str/replace #"[^\w]" "_")))

(s/fdef jar->label :args (s/cat :a (s/keys :req-un [::jar->lib] :opt-un [::deps-repo-tag]) :p path?) :ret string?)
(defn jar->label
  "Given a .jar path, return the bazel label. `deps-repo-tag` is the name of the bazel repository where deps are held, e.g `@deps`"
  [{:keys [deps-repo-tag jar->lib] :as args} jarpath]
  (str deps-repo-tag "//:" (->> jarpath (get! jar->lib) library->label)))

(s/fdef ns->label :args (s/cat :a (s/keys :req-un [::workspace-root ::ns->path ::deps-repo-tag
                                                   ]) :n symbol?))
(defn ns->label
  "given the ns-map and a namespace, return a map of `:src` or `:dep` to the file/jar where it is located"
  [{:keys [ns->path jar->lib workspace-root deps-repo-tag] :as args} ns]
  (let [path (get! ns->path ns)]
    {:deps (if (jar? path)
             [(jar->label (select-keys args [:deps-repo-tag :jar->lib]) path)]
             [(src->label {:workspace-root workspace-root} path)])}))

(defn get-ns-decl [path]
  (let [form (-> path
                 (.toFile)
                 (slurp)
                 (#(read-string {:read-cond :allow} %)))]
    ;; the first form might not be `ns`, in which case ignore the file
    (when (and form (= 'ns (first form)))
      form)))

(s/fdef ns-deps :args (s/cat :a (s/keys :req-un [::workspace-root ::ns->path ::jar->lib ::deps-repo-tag]) :p path? :d ::ns-decl))
(defn ns-deps
  "Given the ns declaration for a .clj file, return a map of {:srcs [labels], :data [labels]} for all :require statements"
  [{:keys [ns->path workspace-root jar->lib deps-repo-tag] :as args} path ns-decl]
  (try
    (->> ns-decl
         parse/deps-from-ns-decl
         (map (partial ns->label (select-keys args [:ns->path :jar->lib :workspace-root :deps-repo-tag :workspace-root])))
         (filter identity)
         (distinct)
         (apply merge-with concat))
    (catch Throwable t
      (throw (ex-info "error" {:path path} t)))))

(s/def ::ns-decl any?)

(s/fdef ns-import-deps :args (s/cat :a (s/keys :req-un [::deps-repo-tag ::class->jar ::jar->lib]) :n ::ns-decl) )
(defn ns-import-deps
  "Given the ns declaration for a .clj file, return a map of {:srcs [labels], :data [labels]} for all :import statements"
  [{:keys [deps-repo-tag class->jar jar->lib] :as args} ns-decl]
  (let [[_ns _name & refs] ns-decl]
    (->> refs
         (filter (fn [r]
                   (= :import (first r))))
         (mapcat  rest)
         (mapcat (fn [form]
                   (cond
                     (symbol? form) [form]
                     (sequential? form) (let [package (first form)
                                              classes (rest form)]
                                          (map (fn [c]
                                                 (symbol (str package "." c))) classes)))))
         (map (fn [class]
                (when-let [jar (get class->jar class)]
                  {:deps [(jar->label {:deps-repo-tag deps-repo-tag
                                       :jar->lib jar->lib} jar)]})))
         (filter identity)
         (distinct)
         (apply merge-with concat))))

(defn ns-gen-class-deps
  "Given the ns declaration for a .clj file, return extra {:deps} from a :gen-class :extends"
  [{:keys [deps-repo-tag class->jar jar->lib] :as args} ns-decl]
  (let [[_ns _name & refs] ns-decl]
    (->> refs
         (filter (fn [r]
                   (= :gen-class (first r))))
         (first)
         ((fn [form]
            (let [args (apply hash-map (rest form))]
              (when-let [class (:extends args)]
                (when-let [jar (get class->jar class)]
                  {:deps [(jar->label {:deps-repo-tag deps-repo-tag
                                       :jar->lib jar->lib} jar)]}))))))))

(defn test-ns? [path]
  (re-find #"_test.clj" (str path)))

(defn src-ns? [path]
  (not (test-ns? path)))

(defn path->ns
  "given the path to a .clj file, return the namespace"
  [path]
  {:post [(symbol? %)]}
  (-> path
      .toFile
      (slurp)
      (read-string)
      (second)))

(defn requires-aot?
  [ns-decl]
  (let [[_ns _name & refs] ns-decl]
    (->> refs
         (filter (fn [r]
                   (= :gen-class (first r))))
         first
         boolean)))

(defn ns-classpath
  "given a namespace symbol, return the path where clojure expects to find the .clj file relative to the root of the classpath"
  [ns extension]
  (assert (symbol? ns) (print-str ns (class ns)))
  (assert (path? path) (print-str path))
  (str "/" (-> ns
               (str/replace "-" "_")
               (str/replace "." "/")) "." extension))

(s/fdef ns-rules :args (s/cat :a (s/keys :req-un [::workspace-root ::ns->path ::jar->lib ::deps-repo-tag ::deps-bazel]) :f path?))
(defn ns-rules
  "given a .clj path, return all rules for the file "
  [{:keys [workspace-root deps-bazel deps-repo-tag] :as args} path]
  (try
    (if-let [[_ns ns-name & refs :as ns-decl] (get-ns-decl path)]
      (let [test? (test-ns? path)
            ns-label (if test?
                       (str (basename path) "_ns")
                       (str (basename path)))
            test-label (when test?
                         (str (basename path)))
            aot? (requires-aot? ns-decl)
            overrides (get-in deps-bazel [:extra-deps (src->label {:workspace-root workspace-root} path)])]
        (when overrides
          (println "extra-info:" overrides))
        (->>
         (concat
          [(emit-bazel (list 'clojure_namespace (-> (merge-with into
                                                                {:name ns-label
                                                                 :srcs {(str (filename path)) (ns-classpath ns-name (extension path))}
                                                                 :deps [(str deps-repo-tag "//:org_clojure_clojure") "//resources"]}
                                                                (ns-deps (select-keys args [:workspace-root :ns->path :jar->lib :deps-repo-tag]) path ns-decl)
                                                                (ns-import-deps args ns-decl)
                                                                (ns-gen-class-deps args ns-decl)
                                                                (when test?
                                                                  {:testonly true})
                                                                overrides)
                                                    (update :deps (comp vec distinct)))))]
          (when test?
            [(emit-bazel (list 'clojure_test {:name test-label
                                              :test_ns (str ns-name)
                                              :srcs [ns-label]}))]))
         (filterv identity)))
      (println "WARNING: skipping" path "due to no ns declaration"))
    (catch Throwable t
      (println "while processing" path)
      (throw t))))

(s/fdef gen-dir :args (s/cat :a (s/keys :req-un [::workspace-root ::ns->path ::jar->lib ::deps-repo-tag]) :f path?))
(defn gen-dir
  "given a source directory, write a BUILD.bazel for all .clj files in the directory. non-recursive"
  [args dir]
  (println "writing to" (->path dir "BUILD.bazel"))
  (let [paths (->> dir
                   ls
                   (filter (fn [path]
                             (-> path .toFile clj-file?)))
                   doall)
        rules (->> paths
                   (mapcat (fn [p]
                             (ns-rules args p)))
                   doall)
        content (str "#autogenerated, do not edit\n"
                     (emit-bazel (list 'package {:default_visibility ["//visibility:public"]}))
                     "\n"
                     (emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_namespace" "clojure_test"))
                     "\n"
                     "\n"
                     (str/join "\n\n" rules))]
    (-> dir
        (->path "BUILD.bazel")
        path->file
        (spit content :encoding "UTF-8"))))

(s/fdef gen-source-path- :args (s/cat :a (s/keys :req-un [::workspace-root ::ns->path ::jar->lib ::deps-repo-tag ::deps-bazel]) :paths (s/coll-of path?)))
(defn gen-source-paths-
  "gen-dir for every directory on the classpath."
  [args paths]
  (->> paths
       (mapcat (fn [path]
                 (ls-r path)))
       (filter (fn [path]
                 (-> path .toFile clj-file?)))
       (map dirname)
       (distinct)
       (map (fn [dir]
              (gen-dir args dir)))
       (dorun)))

(defn basis-absolute-source-paths
  "By default the source directories on the basis `:classpath` are relative to the deps.edn. Absolute-ize them"
  [basis deps-edn-path]
  (reduce (fn [basis [path info]]
            (if (= "jar" (-> path ->path extension))
              (-> basis
                  (update-in [:classpath] dissoc path)
                  (assoc-in [:classpath (->path path)] info))
              (-> basis
                  (update-in [:classpath] dissoc path)
                  (assoc-in [:classpath (->path (dirname deps-edn-path) path)] info)))) basis (:classpath basis)))

(s/fdef make-basis :args (s/cat :a (s/keys :req-un [::read-deps ::aliases ::deps-out-dir ::deps-edn-path])) :ret ::basis)
(defn make-basis
  "combine a set of aliases and return a complete deps map"
  [{:keys [read-deps aliases deps-out-dir deps-edn-path]}]
  (let [combined-aliases (deps/combine-aliases read-deps aliases)]
    (-> read-deps
        (merge {:mvn/local-repo (str deps-out-dir)})
        (deps/calc-basis {:resolve-args (merge combined-aliases {:trace true})
                          :classpath-args combined-aliases})
        (update :deps merge (:extra-deps combined-aliases))
        (update :extra-paths merge (:paths combined-aliases))
        (basis-absolute-source-paths deps-edn-path))))

(s/fdef source-paths :args (s/cat :b ::basis :p path?) :ret (s/coll-of string?))
(defn source-paths
  "return the set of source directories on the classpath"
  [basis deps-edn-path]
  {:post [(do (println "source-paths:" %) true)]}
  (-> basis
      :classpath
      (->>
       (map first)
       (filter (fn [path]
                 (-> path
                     .toFile
                     (directory?)))))))

(s/fdef gen-source-paths :args (s/cat :a (s/keys :req-un [::deps-edn-path ::deps-out-dir ::deps-repo-tag ::basis ::jar->lib ::deps-bazel ::workspace-root]
                                                 :opt-un [::aliases ])))
(defn gen-source-paths
  "Given the path to a deps.edn file, gen-dir every source file on the classpath

  deps-edn-path: path to the deps.edn file
  deps-out-dir: output directory in the bazel sandbox where deps should be downloaded
  deps-repo-tag: Bazel workspace repo for deps, typically `@deps`
  "
  [{:keys [deps-edn-path deps-repo-tag basis jar->lib workspace-root aliases] :as args}]
  (let [ns->path (->ns->path basis)
        args (merge args
                    {:ns->path ns->path
                     :jar->lib jar->lib})]
    (println "gen-source-paths" deps-edn-path)
    (gen-source-paths- args (map ->path (source-paths basis deps-edn-path)))))

(s/fdef gen-toplevel-build :args (s/cat :a (s/keys :req-un [::deps-out-dir ::deps-build-dir ::deps-repo-tag ::jar->lib ::lib->jar ::lib->deps ::deps-bazel])))
(defn gen-toplevel-build
  "generates the BUILD file for @deps//: with a single target containing all deps.edn-resolved dependencies"
  [{:keys [deps-out-dir deps-build-dir jar->lib lib->jar lib->deps deps-repo-tag deps-bazel] :as args}]
  (println "writing to" (-> (->path deps-build-dir "BUILD.bazel") path->file))
  (spit (-> (->path deps-build-dir "BUILD.bazel") path->file)
        (str/join "\n\n" (concat
                          [(emit-bazel (list 'package {:default_visibility ["//visibility:public"]}))
                           (emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_library"))]
                          (->> jar->lib
                               (map (fn [[jarpath lib]]
                                      (let [munged (str (library->label lib))
                                            extra-deps (get-in deps-bazel [:extra-deps (jar->label (select-keys args [:jar->lib]) jarpath)])]
                                        (when extra-deps
                                          (println lib "extra-deps:" extra-deps))
                                        (assert (re-find #".jar$" (str jarpath)) "only know how to handle jars for now")
                                        (emit-bazel (list 'java_import (merge-with into
                                                                                   {:name munged
                                                                                    :jars [(path-relative-to deps-build-dir jarpath)]
                                                                                    :deps (->> (get lib->deps lib)
                                                                                               (mapv (fn [lib]
                                                                                                       (str ":" (library->label lib)))))}
                                                                                   extra-deps)))))))))
        :encoding "UTF-8"))

(defn gen-maven-install
  "prints out a maven_install() block for pasting into WORKSPACE"
  [basis]
  (list 'maven_install
        {:artifacts (->> basis
                         :deps
                         (map (fn [[library-name info]]
                                (if-let [version (:mvn/version info)]
                                  (str (str/replace library-name "/" ":") ":" version)
                                  (do (println "WARNING unsupported dep type:" library-name info) nil))))
                         (filterv identity)
                         (sort))
         :repositories (or (->> basis :mvn/repos vals (mapv :url))
                           ["https://repo1.maven.org/maven2/"])}))

(defn gen-resources
  "Generate a BUILD file for the resources directory"
  [{:keys [deps-edn-path]} path]
  (spit (-> deps-edn-path dirname (->path path "BUILD.bazel") path->file)
        (str "#autogenerated, do not edit\n"
             (emit-bazel (list 'package {:default_visibility ["//visibility:public"]}))
             "\n"
             (emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_library"))
             "\n"
             (emit-bazel (list 'clojure_library {:name (basename path)
                                                 :resources (list 'glob ["**/*"])}))
             "\n")))

(defn instrument-ns
  ([]
   (instrument-ns *ns*))
  ([ns]
   (println "instrumenting" ns)
   (s/check-asserts true)
   (->> ns
        (ns-publics)
        (vals)
        (mapv (fn [^Var v]
                (symbol (str (.ns v) "/" (.sym v)))))
        (stest/instrument))
   nil))

;; (instrument-ns)

(defn deps [{:keys [deps-out-dir deps-build-dir deps-edn-path deps-repo-tag aliases]
             :or {deps-repo-tag "@deps"}}]
  (assert (re-find #"^@" deps-repo-tag) (print-str "deps repo tag must start with @"))
  (let [deps-edn-path (-> deps-edn-path ->path absolute)
        deps-out-dir (-> deps-out-dir ->path absolute)
        deps-build-dir (-> deps-build-dir ->path absolute)
        read-deps (#'read-deps deps-edn-path)
        deps-bazel (parse-deps-bazel read-deps)
        basis (make-basis {:read-deps read-deps
                           :aliases (or (mapv keyword aliases) [])
                           :deps-out-dir deps-out-dir
                           :deps-edn-path deps-edn-path})
        jar->lib (->jar->lib basis)
        lib->jar (set/map-invert jar->lib)
        class->jar (->class->jar basis)
        lib->deps (->lib->deps basis)]

    (gen-toplevel-build {:deps-bazel deps-bazel
                         :deps-out-dir deps-out-dir
                         :deps-build-dir deps-build-dir
                         :deps-repo-tag deps-repo-tag
                         :jar->lib jar->lib
                         :lib->jar lib->jar
                         :lib->deps lib->deps})))

(defn srcs [{:keys [deps-out-dir deps-edn-path deps-repo-tag aliases workspace-root]
             :or {deps-repo-tag "@deps"}}]
  (assert (re-find #"^@" deps-repo-tag) (print-str "deps repo tag must start with @"))
  (assert workspace-root)
  (let [deps-edn-path (-> deps-edn-path ->path absolute)
        deps-out-dir (-> deps-out-dir ->path absolute)
        read-deps (#'read-deps deps-edn-path)
        deps-bazel (parse-deps-bazel read-deps)
        basis (make-basis {:read-deps read-deps
                           :aliases (or (mapv keyword aliases) [])
                           :deps-out-dir deps-out-dir
                           :deps-edn-path deps-edn-path})
        jar->lib (->jar->lib basis)
        lib->jar (set/map-invert jar->lib)
        class->jar (->class->jar basis)
        lib->deps (->lib->deps basis)]
    (gen-source-paths {:deps-bazel deps-bazel
                       :deps-edn-path deps-edn-path
                       :deps-out-dir deps-out-dir
                       :deps-repo-tag deps-repo-tag
                       :workspace-root workspace-root
                       :basis basis
                       :jar->lib jar->lib
                       :class->jar class->jar})
    (gen-resources {:deps-edn-path deps-edn-path} (->path "resources"))))

(defn run! []
  (let [args {:deps-edn-path "deps.edn"
              :deps-out-dir "deps"
              :deps-repo-tag "@deps"
              :workspace-root (-> (->path "") absolute)
              :aliases [:dev :test :bazel :datomic :staging]}]
    ;; (deps args)
    (srcs args)))

(defn -main [& args]
  (let [cmd (first args)
        cmd (keyword cmd)
        opts (apply hash-map (rest args))
        opts (into {} (map (fn [[k v]]
                             [(keyword k) v]) opts))
        opts (-> opts
                 (update :aliases (fn [aliases] (-> aliases
                                                    (edn/read-string)
                                                    (#(mapv keyword %)))))
                 (cond->
                   (:workspace-root opts) (update :workspace-root (comp absolute ->path))))

        f (case cmd
            :deps deps
            :srcs srcs)]
    (set! *print-length* 100)
    (f opts)))
