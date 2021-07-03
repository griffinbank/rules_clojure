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
            [clojure.tools.namespace.find :as find]
            [rules-clojure.fs :as fs])
  (:import java.io.File
           [clojure.lang Keyword IPersistentVector IPersistentList IPersistentMap Var]
           [java.nio.file Files Path Paths FileSystem FileSystems]
           java.nio.file.attribute.FileAttribute
           [java.util.jar JarFile]))

(s/def ::ns-path (s/map-of symbol? ::fs/absolute-path))
(s/def ::read-deps map?)
(s/def ::aliases (s/coll-of keyword?))

(s/def ::paths (s/coll-of string?))
(s/def ::library symbol?)
(s/def ::lib->deps (s/map-of ::library (s/coll-of ::library)))

(s/def ::deps-info (s/keys :req-un [::paths]))
(s/def ::jar->lib (s/map-of ::fs/absolute-path ::library))
(s/def ::workspace-root ::fs/absolute-path)
(s/def ::deps-repo-tag string?)
(s/def ::deps-edn-path ::fs/absolute-path)
(s/def ::deps-out-dir ::fs/absolute-path)
(s/def ::deps-build-dir ::fs/absolute-path)

(s/def ::classpath (s/map-of ::fs/absolute-path map?))
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

(defn emit-bazel-dispatch [x]
  (class x))

(defrecord KeywordArgs [x])

(s/fdef kwargs :args (s/cat :x map?))
(defn kwargs [x]
  (->KeywordArgs x))

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

(defn emit-bazel-kwargs [kwargs]
  {:pre [(map? kwargs)]
   :post [(string? %)]}
  (->> (:x kwargs)
       (map (fn [[k v]]
              (print-str (emit-bazel* k) "=" (emit-bazel* v))))
       (interpose ",\n\t")
       (apply str)))

(defmethod emit-bazel* KeywordArgs [x]
  (emit-bazel-kwargs x))

(defmethod emit-bazel* IPersistentList [[name & args]]
  ;; function call
  (let [args (if (seq args)
               (mapv emit-bazel* args))]
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
      fs/->path
      fs/absolute))

(s/fdef read-deps :args (s/cat :p fs/path?))
(defn read-deps [deps-path]
  (-> deps-path
      fs/path->file
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
        (let [dir (fs/dirname path)
              path (io/file dir name)]
          (if (fs/exists? (io/file path))
            path
            (recur (fs/dirname (fs/dirname path)))))
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
                [(fs/->path path) lib-name])))
       (filter identity)
       (into {})))

(s/fdef library->label :args (s/cat :p symbol?) :ret string?)
(defn library->label
  "given the name of a library, e.g. `org.clojure/clojure`, munge the name into a bazel label"
  [lib-name]
  (-> lib-name
      (str/replace #"-" "_")
      (str/replace #"[^\w]" "_")))

(defn internal-dep-ns-aot-label
  "Given a dep library and a namespace inside it, return the name of the AOT target"
  [lib ns]
  (str "ns_" (library->label lib) "_" (library->label ns)))

(defn external-dep-ns-aot-label [{:keys [deps-repo-tag]} lib ns]
  "Given a dep library and a namespace inside it, return the name of the AOT target"
  [{:keys [deps-repo-tag]} lib ns]
  {:pre [deps-repo-tag]}
  (str deps-repo-tag "//:ns_" (library->label lib) "_" (library->label ns)))

(s/def ::dep-ns->label (s/map-of symbol? string?))

(defn ->dep-ns->label [{:keys [basis] :as args}]
  {:pre [(map? basis)]}
  (->> basis
       :classpath
       (map (fn [[path {:keys [lib-name]}]]
              (when lib-name
                (let [nses (find/find-namespaces [(fs/path->file path)])]
                  (->> nses
                       (map (fn [n]
                              [n (library->label lib-name)]))
                       (into {}))))))
       (filter identity)
       (apply merge)))

(s/fdef src-path->label :args (s/cat :a (s/keys :req-un [::workspace-root]) :p fs/path?) :ret string?)
(defn src-path->label [{:keys [workspace-root]} path]
  (let [path (fs/path-relative-to workspace-root path)]
    (str "//" (fs/dirname path) ":" (str (fs/basename path)))))

(s/def ::src-ns->label (s/map-of symbol? string?))

(defn ->src-ns->label [{:keys [basis workspace-root] :as args}]
  {:pre [(-> basis map?) (-> basis :classpath map?)]
   :post [(map? %)]}
  (->> basis
       :classpath
       (map (fn [[path {:keys [path-key]}]]
              (when path-key
                (let [nses (find/find-namespaces [(fs/path->file path)])]
                  (->> nses
                       (map (fn [n]
                              [n (-> (resolve-src-location path n)
                                     (#(src-path->label (select-keys args [:workspace-root]) %)))]))
                       (into {}))))))
       (filter identity)
       (apply merge)))

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

(defn jar-nses [path]
  (-> path
      str
      (JarFile.)
      (find/find-namespaces-in-jarfile)))

(defn jar-compiled?
  "true if the jar contains .class files"
  [path]
  (-> path
      str
      (JarFile.)
      (.entries)
      (enumeration-seq)
      (->>
       (some (fn [e]
               (re-find #".class$" (.getName e)))))))

(defn jar-ns-decls
  "Given a path to a jar, return a seq of ns-decls"
  [path]
  (-> path
      str
      (JarFile.)
      (find/find-ns-decls-in-jarfile)))

(s/def ::class->jar (s/map-of symbol? fs/path?))
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
                               [c (fs/->path path)]))))))
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
                (update dep-map parent (fnil conj #{}) child))
              ;; empty parent means this is a toplevel dep, already covered elsewhere
              dep-map)
            )) {})))

(s/fdef src->label :args (s/cat :a (s/keys :req-un [::workspace-root]) :p fs/path?) :ret string?)
(defn src->label [{:keys [workspace-root]} path]
  (let [path (fs/path-relative-to workspace-root path)]
    (str "//" (fs/dirname path) ":" (str (fs/basename path)))))

(s/fdef jar->label :args (s/cat :a (s/keys :req-un [::jar->lib] :opt-un [::deps-repo-tag]) :p fs/path?) :ret string?)
(defn jar->label
  "Given a .jar path, return the bazel label. `deps-repo-tag` is the name of the bazel repository where deps are held, e.g `@deps`"
  [{:keys [deps-repo-tag jar->lib] :as args} jarpath]
  (str deps-repo-tag "//:" (->> jarpath (get! jar->lib) library->label)))

(s/fdef ns->label :args (s/cat :a (s/keys :req-un [::src-ns->label ::dep-ns->label]) :n symbol?))
(defn ns->label
  "given the ns-map and a namespace, return a map of `:src` or `:dep` to the file/jar where it is located"
  [{:keys [src-ns->label dep-ns->label deps-repo-tag] :as args} ns]
  {:pre [(map? src-ns->label)]}
  (let [label  (or (get src-ns->label ns)
                   (when-let [label (get dep-ns->label ns)]
                     (assert deps-repo-tag)
                     (str deps-repo-tag "//:" label)))]
    (assert label (str "couldn't find label for " ns))
    {:deps [label]}))

(defn get-ns-decl [path]
  (let [form (-> path
                 (.toFile)
                 (slurp)
                 (#(read-string {:read-cond :allow} %)))]
    ;; the first form might not be `ns`, in which case ignore the file
    (when (and form (= 'ns (first form)))
      form)))

(s/fdef ns-deps :args (s/cat :a (s/keys :req-un [::workspace-root ::jar->lib ::deps-repo-tag]) :p fs/path? :d ::ns-decl))
(defn ns-deps
  "Given the ns declaration for a .clj file, return a map of {:srcs [labels], :data [labels]} for all :require statements"
  [{:keys [src-ns->label dep-ns->label workspace-root jar->lib deps-repo-tag] :as args} path ns-decl]
  (try
    (->> ns-decl
         parse/deps-from-ns-decl
         (map (partial ns->label (select-keys args [:src-ns->label :dep-ns->label :deps-repo-tag])))
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
  (assert (string? extension) (print-str extension))
  (str "/" (-> ns
               (str/replace "-" "_")
               (str/replace "." "/")) "." extension))

(s/fdef resource-paths :args (s/cat :a (s/keys :req-un [::aliases ::basis ::workspace-root])) :ret (s/coll-of fs/path?))
(defn resource-paths
  "Returns the set paths"
  [{:keys [basis aliases workspace-root] :as args}]
  (->>
   (concat
    (:bazel/resources basis)
    (mapcat (fn [a]
              (get-in basis [:aliases a :bazel/resources])) aliases))
   (distinct)
   (map (fn [p]
          (assert p)
          (assert workspace-root)
          (fs/->path workspace-root p)))))

(s/fdef resource-labels :args (s/cat :a (s/keys :req-un [::basis ::workspace-root ::aliases])) :ret (s/coll-of string?))
(defn resource-labels [{:keys [basis workspace-root aliases] :as args}]
  (->> (resource-paths args)
       (mapv (fn [p]
               (str "//" (fs/path-relative-to workspace-root p))))))

(defn strip-path
  "Given a"
  [{:keys [basis workspace-root]} path]
  (->> basis
       :paths
       (filter (fn [p]
                 (.startsWith path (fs/->path workspace-root p))))
       first))

(s/fdef ns-rules :args (s/cat :a (s/keys :req-un [::basis ::workspace-root ::jar->lib ::deps-repo-tag ::deps-bazel]) :f fs/path?))
(defn ns-rules
  "given a .clj path, return all rules for the file "
  [{:keys [basis workspace-root deps-bazel deps-repo-tag deps-edn-path] :as args} path]
  (assert (map? (:src-ns->label args)))
  (try
    (if-let [[_ns ns-name & refs :as ns-decl] (get-ns-decl path)]
      (let [test? (test-ns? path)
            ns-label (str (fs/basename path))
            src-label (src->label {:workspace-root workspace-root} path)
            test-label (str (fs/basename path) ".test")
            overrides (get-in deps-bazel [:extra-deps src-label])
            test-full-label (str "//" (fs/path-relative-to workspace-root (fs/dirname path)) ":" (str (fs/basename path) ".test"))
            test-overrides (get-in deps-bazel [:extra-deps test-full-label])
            aot (or
                 (get overrides :aot)
                 (if true ;; (requires-aot? ns-decl)
                   [(str ns-name)]
                   []))]
        (when overrides
          (println ns-name "extra-info:" overrides))
        (when test-overrides
          (println ns-name "test extra-info:" test-overrides))
        (->>
         (concat
          [(emit-bazel (list 'clojure_library (kwargs (-> (merge-with into
                                                                      {:name ns-label
                                                                       :deps [(str deps-repo-tag "//:org_clojure_clojure")]}
                                                                      (if (seq aot)
                                                                        {:srcs [(fs/filename path)]
                                                                         :aot aot}
                                                                        {:resources [(fs/filename path)]
                                                                         :aot []})
                                                                      (when-let [strip-path (strip-path (select-keys args [:basis :workspace-root]) path)]
                                                                        {:resource_strip_prefix strip-path})
                                                                   (ns-deps (select-keys args [:workspace-root :src-ns->label :dep-ns->label :jar->lib :deps-repo-tag]) path ns-decl)
                                                                   (ns-import-deps args ns-decl)
                                                                   (ns-gen-class-deps args ns-decl)
                                                                   overrides)
                                                          (as-> m
                                                              (cond-> m
                                                                (seq (:deps m)) (update :deps (comp vec distinct))
                                                                (:deps m) (update :deps (comp vec distinct))))))))]
          (when test?
            [(emit-bazel (list 'clojure_test (kwargs (merge
                                                      {:name test-label
                                                       :test_ns (str ns-name)
                                                       :deps [(str ":" ns-label)]}
                                                      test-overrides))))]))
         (filterv identity)))
      (println "WARNING: skipping" path "due to no ns declaration"))
    (catch Throwable t
      (println "while processing" path)
      (throw t))))

(s/fdef gen-dir :args (s/cat :a (s/keys :req-un [::workspace-root ::basis ::jar->lib ::deps-repo-tag]) :f fs/path?))
(defn gen-dir
  "given a source directory, write a BUILD.bazel for all .clj files in the directory. non-recursive"
  [args dir]
  (assert (map? (:src-ns->label args)))
  (let [paths (->> dir
                   fs/ls
                   (filter (fn [path]
                             (-> path .toFile fs/clj-file?)))
                   doall)
        rules (->> paths
                   (mapcat (fn [p]
                             (ns-rules args p)))
                   doall)
        content (str "#autogenerated, do not edit\n"
                     (emit-bazel (list 'package (kwargs {:default_visibility ["//visibility:public"]})))
                     "\n"
                     (emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_library" "clojure_test"))
                     "\n"
                     "\n"
                     (str/join "\n\n" rules))]
    (-> dir
        (fs/->path "BUILD.bazel")
        fs/path->file
        (spit content :encoding "UTF-8"))))

(s/fdef gen-source-paths- :args (s/cat :a (s/keys :req-un [::workspace-root ::src-ns->label ::dep-ns->label ::jar->lib ::deps-repo-tag ::deps-bazel]) :paths (s/coll-of fs/path?)))
(defn gen-source-paths-
  "gen-dir for every directory on the classpath."
  [args paths]
  (assert (map? (:src-ns->label args)))
  (->> paths
       (mapcat (fn [path]
                 (fs/ls-r path)))
       (filter (fn [path]
                 (-> path .toFile fs/clj-file?)))
       (map fs/dirname)
       (distinct)
       (map (fn [dir]
              (gen-dir args dir)))
       (dorun)))

(defn basis-absolute-source-paths
  "By default the source directories on the basis `:classpath` are relative to the deps.edn. Absolute-ize them"
  [basis deps-edn-path]
  (reduce (fn [basis [path info]]
            (if (= "jar" (-> path fs/->path fs/extension))
              (-> basis
                  (update-in [:classpath] dissoc path)
                  (assoc-in [:classpath (fs/->path path)] info))
              (-> basis
                  (update-in [:classpath] dissoc path)
                  (assoc-in [:classpath (fs/->path (fs/dirname deps-edn-path) path)] info)))) basis (:classpath basis)))

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
        (update :paths concat (:extra-paths combined-aliases))
        (basis-absolute-source-paths deps-edn-path))))

(s/fdef source-paths :args (s/cat :a (s/keys :req-un [::basis ::deps-edn-path ::aliases ::workspace-root])) :ret (s/coll-of fs/path?))
(defn source-paths
  "return the set of source directories on the classpath"
  [{:keys [basis deps-edn-path aliases] :as args}]
  (let [resource-paths (set (resource-paths (select-keys args [:aliases :basis :workspace-root])))]
    (->>
     (:paths basis)
     (map (fn [path]
            (fs/->path (fs/dirname deps-edn-path) path)))
     (remove (fn [path]
               (contains? resource-paths path))))))

(s/fdef gen-source-paths :args (s/cat :a (s/keys :req-un [::deps-edn-path ::deps-out-dir ::deps-repo-tag ::basis ::jar->lib ::deps-bazel ::workspace-root]
                                                 :opt-un [::aliases ])))
(defn gen-source-paths
  "Given the path to a deps.edn file, gen-dir every source file on the classpath

  deps-edn-path: path to the deps.edn file
  deps-out-dir: output directory in the bazel sandbox where deps should be downloaded
  deps-repo-tag: Bazel workspace repo for deps, typically `@deps`
  "
  [{:keys [deps-edn-path deps-repo-tag basis jar->lib workspace-root aliases] :as args}]
  (let [args (merge args
                    {:src-ns->label (->src-ns->label args)
                     :dep-ns->label (->dep-ns->label args)
                     :jar->lib jar->lib})]
    (println "gen-source-paths" deps-edn-path)
    (gen-source-paths- args (source-paths (select-keys args [:aliases :basis :deps-edn-path :workspace-root])))))

(s/fdef gen-deps-build :args (s/cat :a (s/keys :req-un [::deps-out-dir ::deps-build-dir ::deps-repo-tag ::jar->lib ::lib->jar ::lib->deps ::deps-bazel])))
(defn gen-deps-build
  "generates the BUILD file for @deps//: with a single target containing all deps.edn-resolved dependencies"
  [{:keys [deps-out-dir deps-build-dir jar->lib lib->jar lib->deps deps-repo-tag deps-bazel] :as args}]
  (println "writing to" (-> (fs/->path deps-build-dir "BUILD.bazel") fs/path->file))
  (spit (-> (fs/->path deps-build-dir "BUILD.bazel") fs/path->file)
        (str/join "\n\n" (concat
                          [(emit-bazel (list 'package (kwargs {:default_visibility ["//visibility:public"]})))
                           (emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_library"))]
                          (->> jar->lib
                               (sort-by (fn [[k v]] (library->label v)))
                               (mapcat (fn [[jarpath lib]]
                                         (let [label (library->label lib)
                                               preaot (str label ".preaot")
                                               deps (->> (get lib->deps lib)
                                                         (mapv (fn [lib]
                                                                 (str ":" (library->label lib)))))
                                               extra-deps-key (jar->label (select-keys args [:jar->lib :deps-repo-tag]) jarpath)
                                               extra-deps (-> deps-bazel
                                                              (get-in [:extra-deps extra-deps-key]))
                                               aot (or
                                                    (:aot extra-deps)
                                                    (mapv str (find/find-namespaces [(fs/path->file jarpath)])))

                                               extra-deps (dissoc extra-deps :aot)]
                                           (when extra-deps
                                             (println lib "extra-deps:" extra-deps))
                                           (assert (re-find #".jar$" (str jarpath)) "only know how to handle jars for now")

                                           [(emit-bazel (list 'java_import (kwargs (merge-with into
                                                                                               {:name (if (seq aot)
                                                                                                        preaot
                                                                                                        label)
                                                                                                :jars [(fs/path-relative-to deps-build-dir jarpath)]}
                                                                                               (when (seq deps)
                                                                                                 {:deps deps
                                                                                                  :runtime_deps deps})
                                                                                               extra-deps))))
                                            (when (seq aot)
                                              (emit-bazel (list 'clojure_library (kwargs {:name label
                                                                                          :deps [preaot]
                                                                                          :aot aot}))))]
                                           ))))))
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
  (let [deps-edn-path (-> deps-edn-path fs/->path fs/absolute)
        deps-out-dir (-> deps-out-dir fs/->path fs/absolute)
        deps-build-dir (-> deps-build-dir fs/->path fs/absolute)
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

    (gen-deps-build {:deps-bazel deps-bazel
                     :deps-out-dir deps-out-dir
                     :deps-build-dir deps-build-dir
                     :deps-repo-tag deps-repo-tag
                     :jar->lib jar->lib
                     :lib->jar lib->jar
                     :lib->deps lib->deps})))

(defn srcs [{:keys [deps-out-dir deps-edn-path deps-repo-tag aliases workspace-root aot-default]
             :or {deps-repo-tag "@deps"}}]
  (assert (re-find #"^@" deps-repo-tag) (print-str "deps repo tag must start with @"))
  (assert workspace-root)
  (let [deps-edn-path (-> deps-edn-path fs/->path fs/absolute)
        deps-out-dir (-> deps-out-dir fs/->path fs/absolute)
        read-deps (#'read-deps deps-edn-path)
        deps-bazel (parse-deps-bazel read-deps)
        aliases (or (mapv keyword aliases) [])
        basis (make-basis {:read-deps read-deps
                           :aliases aliases
                           :deps-out-dir deps-out-dir
                           :deps-edn-path deps-edn-path})
        jar->lib (->jar->lib basis)
        lib->jar (set/map-invert jar->lib)
        class->jar (->class->jar basis)
        lib->deps (->lib->deps basis)
        args {:aliases aliases
              :deps-bazel deps-bazel
              :deps-edn-path deps-edn-path
              :deps-out-dir deps-out-dir
              :deps-repo-tag deps-repo-tag
              :workspace-root workspace-root
              :basis basis
              :jar->lib jar->lib
              :class->jar class->jar}]
    (gen-source-paths args)))

(defn -main [& args]
  (let [cmd (first args)
        cmd (keyword cmd)
        opts (apply hash-map (rest args))
        opts (into {} (map (fn [[k v]]
                             [(edn/read-string k) v]) opts))
        opts (-> opts
                 (update :aliases (fn [aliases] (-> aliases
                                                    (edn/read-string)
                                                    (#(mapv keyword %)))))
                 (cond->
                   (:workspace-root opts) (update :workspace-root (comp fs/absolute fs/->path))))

        f (case cmd
            :deps deps
            :srcs srcs)]
    (set! *print-length* 100)
    (f opts)))
