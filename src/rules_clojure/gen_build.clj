(ns rules-clojure.gen-build
  "Tools for generating BUILD.bazel files for clojure deps"
  (:require [clojure.core.specs.alpha :as cs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.set :as set]
            [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.util.concurrent :as concurrent]
            [rules-clojure.parse :as parse]
            [clojure.tools.namespace.find :as find]
            [rules-clojure.fs :as fs])
  (:import [clojure.lang Keyword IPersistentVector IPersistentList IPersistentMap Var]
           [java.nio.file Path]
           [java.util.jar JarFile])
  (:gen-class))

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
(s/def ::deps-edn-dir ::fs/absolute-path) ;; dir that contains the deps.edn
(s/def ::repository-dir ::fs/absolute-path) ;; dir that contains M2
(s/def ::deps-build-dir ::fs/absolute-path) ;; dir for deps BUILD.bazel output

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

(defn validate! [spec val]
  (if (s/valid? spec val)
    val
    (throw (ex-info "value does not conform" (s/explain-data spec val)))))

(defn emit-bazel-dispatch [x]
  (class x))

(defrecord KeywordArgs [x])

(defn kwargs? [x]
  (instance? KeywordArgs x))

(s/fdef kwargs :args (s/cat :x ::bazel-map) :ret kwargs?)
(defn kwargs [x]
  (validate! ::bazel-map x)
  (->KeywordArgs x))

(s/def ::bazel-atom (s/or :s string? :k keyword? :p fs/path? :b boolean?))
(s/def ::bazel-map (s/map-of ::bazel-atom ::bazel))
(s/def ::bazel-vector (s/coll-of ::bazel))
(s/def ::fn-args (s/or :b ::bazel :kw kwargs?))
(s/def ::bazel-fn (s/cat :s symbol? :a (s/* ::fn-args)))
(s/def ::bazel (s/or :ba ::bazel-atom :m ::bazel-map :v ::bazel-vector :f ::bazel-fn))

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
  (let [args (when (seq args)
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

(s/fdef emit-bazel :args (s/cat :x ::bazel) :ret string?)
(defn emit-bazel
  "Given a string name and a dictionary of arguments, return a string of bazel"
  [x]
  (validate! ::bazel x)
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
(s/def ::deps (s/map-of ::target ::target-info))

(s/def ::clojure_library ::target-info)
(s/def ::clojure_test ::target-info)
(s/def ::ignore (s/coll-of string?))
(s/def ::no-aot (s/coll-of symbol?))

(s/def ::deps-bazel (s/keys :opt-un [::clojure_library
                                     ::clojure_test
                                     ::deps
                                     ::ignore
                                     ::no-aot]))

(defn parse-deps-bazel
  "extra data under `:bazel` in a deps.edn file for controlling gen-build. Supported keys:

  :deps - (map-of bazel-target to (s/keys :opt-un [:srcs :deps])), extra targets to include on a clojure_library. This is useful for e.g. adding native library dependencies onto a .clj file"
  [read-deps]
  {:post [(validate! ::deps-bazel %)]}
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

(defn internal-dep-aot-label
  "Given a dep library and a namespace inside it, return the name of the AOT target"
  [lib]
  (str "aot_" (library->label lib)))

(defn internal-dep-ns-aot-label
  "Given a dep library and a namespace inside it, return the name of the AOT target"
  [lib ns]
  (str "ns_" (library->label lib) "_" (library->label ns)))

(defn external-dep-ns-aot-label
  "Given a dep library and a namespace inside it, return the name of the AOT target"
  [{:keys [deps-repo-tag]} lib ns]
  {:pre [deps-repo-tag]}
  (str deps-repo-tag "//:ns_" (library->label lib) "_" (library->label ns)))

(s/def ::dep-ns->label (s/map-of keyword? (s/map-of symbol? string?)))

(def no-aot '#{clojure.core})

(defn jar-files [path]
  (-> (JarFile. (str path))
      (.entries)
      (enumeration-seq)
      (->> (map (fn [e]
                  (.getName e))))))

(defn dir-files [path]
  (->> path
       (fs/ls-r)
       (map (fn [sub-path]
              (str (fs/path-relative-to path sub-path))))))

(defn classpath-files
  "Given a single classpath item (a jar or a directory), return the set of files contained"
  [path]
  (cond
    (-> path fs/path->file (.isDirectory)) (dir-files path)
    (re-find #".jar$" (str path)) (jar-files path)))

(defn jar-classes
  "given the path to a jar, return a list of classes contained"
  [path]
  (->>
   (jar-files path)
   (map (fn [e]
          (when-let [[_ class-name] (re-find #"(.+).class$" e)]
            class-name)))
   (filter identity)
   (map (fn [e]
          (-> e
              (str/replace "/" ".")
              symbol)))))

(defn is-aoted?
  [path ns]
  (let [class-file (-> ns (#'clojure.core/root-resource) (.substring  1) (str "__init.class"))]
    (some #(= class-file %) (classpath-files path))))

(def special-namespaces '#{clojure.core.specs.alpha})

(defn should-compile-namespace? [deps-bazel path ns]
  (and (not (contains? special-namespaces ns))
       (not (contains? (set/union no-aot (get-in deps-bazel [:no-aot])) ns))
       (not (is-aoted? path ns))))

(defn ->dep-ns->label [{:keys [basis deps-bazel deps-repo-tag] :as args}]
  {:pre [(map? basis)
         deps-bazel]
   :post [(s/valid? ::dep-ns->label %)]}
  (->> basis
       :classpath
       (map (fn [[path {:keys [lib-name]}]]
              (when lib-name
                {:clj (->> (find/find-namespaces [(fs/path->file path)] find/clj)
                           (map (fn [n]
                                  [n (if (should-compile-namespace? deps-bazel path n)
                                       (internal-dep-ns-aot-label lib-name n)
                                       (library->label lib-name))]))
                           (into {}))
                 :cljs (->> (find/find-namespaces [(fs/path->file path)] find/cljs)
                            (map (fn [n]
                                   [n (library->label lib-name)]))
                            (into {}))})))
       (filter identity)
       (apply merge-with merge)))

(s/fdef src-path->label :args (s/cat :a (s/keys :req-un [::deps-edn-dir]) :p fs/path?) :ret string?)
(defn src-path->label [{:keys [deps-edn-dir]} path]
  {:pre [deps-edn-dir]}
  (let [path (fs/path-relative-to deps-edn-dir path)]
    (str "//" (fs/dirname path) ":" (str (fs/basename path)))))

(s/def ::src-ns->label (s/map-of symbol? string?))

(defn ->src-ns->label [{:keys [basis deps-edn-dir] :as args}]
  {:pre [(-> basis map?) (-> basis :classpath map?) deps-edn-dir]
   :post [(map? %)]}
  (->> basis
       :classpath
       (map (fn [[path {:keys [path-key]}]]
              (when path-key
                (let [nses (->>
                            (concat (find/find-namespaces [(fs/path->file path)] find/clj)
                                    (find/find-namespaces [(fs/path->file path)] find/cljs))
                            distinct)]
                  (->> nses
                       (map (fn [n]
                              [n (-> (resolve-src-location path n)
                                     (#(src-path->label (select-keys args [:deps-edn-dir]) %)))]))
                       (into {}))))))
       (filter identity)
       (apply merge)))

(defn ->lib->jar
  "Return a map of library name to jar"
  [jar->lib]
  (set/map-invert jar->lib))

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
      (find/find-ns-decls-in-jarfile find/clj)))

(s/def ::class->jar (s/map-of symbol? fs/path?))
(s/fdef ->class->jar :args (s/cat :b ::basis) :ret ::class->jar)
(defn ->class->jar
  "returns a map of class symbol to jarpath for all jars on the classpath"
  [basis]
  {:post [(s/valid? ::class->jar %)]}
  (into {}
        (mapcat
          (fn [path]
            (let [{:keys [lib-name]} (get-in basis [:classpath path])]
              (when lib-name
                (->> (jar-classes path)
                     (map (fn [c]
                            [c (fs/->path path)]))))))
          (:classpath-roots basis))))

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

(s/fdef src->label :args (s/cat :a (s/keys :req-un [::deps-edn-dir]) :p fs/path?) :ret string?)
(defn src->label [{:keys [deps-edn-dir]} path]
  (let [path (fs/path-relative-to deps-edn-dir path)]
    (str "//" (fs/dirname path) ":" (str (fs/basename path)))))

(s/fdef jar->label :args (s/cat :a (s/keys :req-un [::jar->lib] :opt-un [::deps-repo-tag]) :p fs/path?) :ret string?)
(defn jar->label
  "Given a .jar path, return the bazel label. `deps-repo-tag` is the name of the bazel repository where deps are held, e.g `@deps`"
  [{:keys [deps-repo-tag jar->lib] :as args} jarpath]
  (str deps-repo-tag "//:" (->> jarpath (get! jar->lib) library->label)))

(defn get-dep-ns->label [dep-ns->label platform ns]
  {:pre [(keyword? platform)
         (symbol? ns)]}
  (get-in dep-ns->label [platform ns]))

(s/fdef ns->label :args (s/cat :a (s/keys :req-un [(or ::src-ns->label ::dep-ns->label)]) :n symbol? :p keyword?))
(defn ns->label
  "given the ns-map and a namespace, return a map of `:src` or `:dep` to the file/jar where it is located"
  [{:keys [src-ns->label dep-ns->label deps-repo-tag] :as args} ns platform]
  {:pre [(keyword? platform)]}
  (let [label (or (get src-ns->label ns)
                  (when-let [label (get-dep-ns->label dep-ns->label platform ns)]
                    (assert deps-repo-tag)
                    (str deps-repo-tag "//:" label)))]
    (when label
      {:deps [label]})))

(defn get-ns-decl [path platform]
  (let [form (-> path
                 (.toFile)
                 (slurp)
                 (#(read-string {:read-cond :allow
                                 :features #{platform}} %)))]
    ;; the first form might not be `ns`, in which case ignore the file
    (when (and form (= 'ns (first form)))
      form)))

(defn get-ns-meta
  "return any metadata attached to the namespace, or nil"
  [ns-decl]
  (assert (= 'ns (first ns-decl)))
  (-> (s/conform ::cs/ns-form (rest ns-decl))
      :attr-map))

(s/fdef ns-deps :args (s/cat :a (s/keys :req-un [::jar->lib ::deps-repo-tag]) :d ::ns-decl))
(defn ns-deps
  "Given the ns declaration for a clojure file, return a map of {:srcs [labels], :data [labels]} for all :require statements. Platform must be a set containing one or both of :clj, :cljs"
  [{:keys [src-ns->label dep-ns->label jar->lib deps-repo-tag] :as args} ns-decl platform]
  (let [ns-name (-> ns-decl second)]
    (try
      (->> ns-decl
           parse/deps-from-ns-decl
           (#(disj % ns-name)) ;; cljs depending on .clj.
           (map (fn [ns]
                  (ns->label (select-keys args [:src-ns->label :dep-ns->label :deps-repo-tag]) ns platform)))
           (filter identity)
           (distinct)
           (apply merge-with (comp vec concat))))))

(s/def ::ns-decl any?)

(s/fdef ns-import-deps :args (s/cat :a (s/keys :req-un [::deps-repo-tag ::class->jar ::jar->lib]) :n ::ns-decl) )
(defn ns-import-deps
  "Given the ns declaration for a .clj file, return a map of {:srcs [labels], :data [labels]} for all :import statements"
  [{:keys [deps-repo-tag class->jar jar->lib] :as args} ns-decl]
  (assert class->jar)
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

(s/fdef clj-path? :args (s/cat :p fs/path?) :ret boolean?)
(defn clj-path? [path]
  (boolean (re-find #"\.clj$" (str path))))

(defn cljc-path? [path]
  (boolean (re-find #"\.cljc$" (str path))))

(defn cljs-path? [path]
  (boolean (re-find #"\.cljs$" (str path))))

(defn clj*-path? [path]
  (or (clj-path? path)
      (cljc-path? path)
      (cljs-path? path)))

(defn js-path? [path]
  (re-find #"\.js$" (str path)))

(defn test-path? [path]
  (boolean (re-find #"_test.clj" (str path))))

(defn src-path? [path]
  (not (test-path? path)))

(defn path-
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

(defn ignore-paths
  [{:keys [basis deps-edn-dir] :as args}]
  {:pre [deps-edn-dir]}
  (->>
   (get-in basis [:bazel :ignore])
   (map (fn [p]
          (fs/->path deps-edn-dir p)))
   (set)))

(defn strip-path
  "Given a file path relative to the deps.edn directory, return the path
  stripping off the prefix that matches a deps.edn :path"
  [{:keys [basis deps-edn-dir]} path]
  {:pre [basis deps-edn-dir]
   :post [%]}
  (->> basis
       :paths
       (filter (fn [p]
                 (.startsWith path (fs/->path deps-edn-dir p))))
       first))

(defn reader-features [path]
  (cond
    (clj-path? path) #{:clj}
    (cljs-path? path) #{:cljs}))

(s/fdef ns-rules :args (s/cat :a (s/keys :req-un [::basis ::deps-edn-dir ::jar->lib ::deps-repo-tag ::deps-bazel]) :p (s/coll-of fs/path?)))
(defn ns-rules
  "given a .clj path, return all rules for the file "
  [{:keys [basis deps-bazel deps-repo-tag deps-edn-dir] :as args} paths]
  (assert (map? (:src-ns->label args)))
  (assert (s/valid? (s/coll-of fs/path?) paths))
  (try
    (let [clj? (some clj-path? paths)
          cljs? (some cljs-path? paths)
          cljc? (some cljc-path? paths)
          js? (some js-path? paths)
          platforms (set/union (when clj? #{:clj}) (when cljs? #{:cljs}) (when cljc? #{:clj :cljs}))

          ns-decl-platforms (->>
                             (filter clj*-path? paths)
                             (mapcat (fn [path]
                                       (->> platforms
                                            (map (fn [platform]
                                                   [(get-ns-decl path platform) platform]))))))
          ns-decls (map first ns-decl-platforms)
          ns-name (-> ns-decls first second)
          path (first paths)
          ns-label (str (fs/basename path))

          deps (apply merge-with into (map (fn [[decl platform]]
                                             (merge-with into
                                                         (ns-deps (select-keys args [:deps-edn-dir :src-ns->label :dep-ns->label :jar->lib :deps-repo-tag]) decl platform)
                                                         (ns-import-deps args decl)
                                                         (ns-gen-class-deps args decl))) ns-decl-platforms))
          test? (test-path? path)

          ns-meta (->> ns-decls (map get-ns-meta) (filter identity) (apply merge-with into))
          test-label (str (fs/basename path) ".test")
          clojure-library-args (get-in deps-bazel [:clojure_library])
          clojure-test-args (get-in deps-bazel [:clojure_test])
          ns-library-meta (-> ns-meta
                              (get :bazel/clojure_library)
                              (as-> $
                                  (cond-> $
                                    (:deps $) (update :deps (partial mapv name))
                                    (:runtime_deps $) (update :runtime_deps (partial mapv name)))))
          ns-test-meta (-> ns-meta
                           (get :bazel/clojure_test)
                           (as-> $
                               (cond-> $
                                 (:tags $) (update :tags (partial mapv name))
                                 (:size $) (update :size name)
                                 (:timeout $) (update :timeout name))))

          aot (if (and (or clj? cljc?) (not test?) (get ns-library-meta :aot true))
                [(str ns-name)]
                [])
          ns-library-meta (dissoc ns-library-meta :aot)]

      (when ns-library-meta
        (println ns-name "extra:" ns-library-meta))
      (when ns-test-meta
        (println ns-name "test extra:" ns-test-meta))
      (->>
       [(when (seq ns-decls)
          (emit-bazel (list 'clojure_library (kwargs (-> (merge-with into
                                                                     {:name ns-label
                                                                      :deps [(str deps-repo-tag "//:org_clojure_clojure")]
                                                                      :resources (mapv fs/filename paths)
                                                                      :resource_strip_prefix (strip-path (select-keys args [:basis :deps-edn-dir]) path)}
                                                                     (when (seq aot)
                                                                       {:srcs (mapv fs/filename paths)
                                                                        :aot aot})
                                                                     deps
                                                                     clojure-library-args
                                                                     ns-library-meta)
                                                         (as-> m
                                                             (cond-> m
                                                               (seq (:deps m)) (update :deps (comp vec dedupe sort))
                                                               (:deps m) (update :deps (comp vec dedupe sort)))))))))
        (when (and (or clj?
                       cljc?) test?)
          (emit-bazel (list 'clojure_test (kwargs (merge-with into
                                                              {:name test-label
                                                               :test_ns (str ns-name)
                                                               :deps [(str ":" ns-label)]}
                                                              clojure-test-args
                                                              ns-test-meta)))))
        (when js?
          (emit-bazel (list 'java_library (kwargs {:name ns-label
                                                   :resources (->> paths (filter js-path?) (mapv fs/filename))
                                                   :resource_strip_prefix (strip-path (select-keys args [:basis :deps-edn-dir]) path)}))))]

       (filterv identity)))
    (catch Throwable t
      (println "while processing" paths)
      (throw t))))

(s/fdef gen-dir
  :args (s/cat :args (s/keys :req-un [::deps-edn-dir ::basis ::jar->lib ::deps-repo-tag])
               :dir fs/path?
               :clj-lib? ::clj-lib?))
(defn gen-dir
  "given a source directory, write a BUILD.bazel for all .clj files in the directory. non-recursive"
  [{:keys [deps-edn-dir] :as args} dir clj-lib?]
  (assert (map? (:src-ns->label args)))
  (let [paths (->> (fs/ls dir)
                   (filter (fn [p]
                             (or (clj*-path? p)
                                 (js-path? p))))
                   (sort-by str))
        subdirs (->> dir
                     fs/ls
                     (filter (fn [p]
                               (-> p .toFile fs/directory?)))
                     (sort-by str))
        clj-subdirs (->>
                      subdirs
                      (filter (fn [p]
                                (some clj*-path? (seq (fs/ls-r p))))))
        rules (->> paths
                   (group-by fs/basename)
                   (mapcat (fn [[_base paths]]
                             (ns-rules args paths)))
                   doall)
        library? (and clj-lib?
                      (or (seq paths) (seq clj-subdirs)))
        filegroup? (or (seq paths) (seq clj-subdirs))

        content (str "#autogenerated, do not edit\n"
                     (emit-bazel (list 'package (kwargs {:default_visibility ["//visibility:public"]})))
                     "\n"
                     (emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_library" "clojure_test"))
                     "\n"
                     "\n"
                     (str/join "\n\n" rules)
                     "\n"
                     "\n"
                     (when library?
                       (str
                         (emit-bazel (list 'clojure_library (kwargs {:name "__clj_lib"
                                                                     :deps (vec
                                                                             (concat
                                                                               (dedupe (map (fn [p] (str ":" (fs/basename p))) paths))
                                                                               (map (fn [p]
                                                                                      (str "//" (fs/path-relative-to deps-edn-dir p) ":__clj_lib")) clj-subdirs)))})))
                         "\n"
                         "\n"))

                     (when filegroup?
                       (emit-bazel (list 'filegroup (kwargs (merge
                                                              {:name "__clj_files"
                                                               :srcs (mapv fs/filename paths)
                                                               :data (mapv (fn [p]
                                                                             (str "//" (fs/path-relative-to deps-edn-dir p) ":__clj_files")) clj-subdirs)}))))))]
    (-> dir
        (fs/->path "BUILD.bazel")
        fs/path->file
        (spit content :encoding "UTF-8"))))

(s/fdef gen-source-paths-
  :args (s/cat :a (s/keys :req-un [::deps-edn-dir ::src-ns->label ::dep-ns->label ::jar->lib ::deps-repo-tag ::deps-bazel])
               :paths (s/coll-of ::source-path)))
(defn gen-source-paths-
  "gen-dir for every directory on the classpath."
  [args source-paths]
  (assert (map? (:src-ns->label args)))
  (->> source-paths
       (mapcat (fn [{:keys [path clj-lib?]}]
                 (for [path (->> (fs/ls-r path)
                                 (map fs/dirname))]
                   {:path path
                    :clj-lib? clj-lib?})))
       (distinct)
       (sort-by (comp count str :path))
       (reverse)
       (map (fn [{:keys [path clj-lib?]}]
              (gen-dir args path clj-lib?)))
       (dorun)))

(defn path->absolute
  [path deps-edn-path]
  (if (= "jar" (-> path fs/->path fs/extension))
    (fs/->path path)
    (fs/->path (fs/dirname deps-edn-path) path)))

(defn basis-absolute-source-paths
  "By default the source directories on the basis `:classpath` are relative to the deps.edn. Absolute-ize them"
  [basis deps-edn-path]
  (-> basis
      (update :classpath update-keys #(path->absolute % deps-edn-path))
      (update :classpath-roots (fn [cp-roots] (map #(path->absolute % deps-edn-path) cp-roots)))))

(s/fdef make-basis :args (s/cat :a (s/keys :req-un [::read-deps ::aliases ::repository-dir ::deps-edn-path])) :ret ::basis)
(defn make-basis
  "combine a set of aliases and return a complete deps map"
  [{:keys [read-deps aliases repository-dir deps-edn-path]}]
  {:pre [repository-dir]}
  (let [combined-aliases (deps/combine-aliases read-deps aliases)]
    (-> read-deps
        (merge {:mvn/local-repo (str repository-dir)})
        (deps/calc-basis {:resolve-args (merge combined-aliases {:trace true})
                          :classpath-args combined-aliases})
        (update :deps merge (:extra-deps combined-aliases))
        (update :paths concat (:extra-paths combined-aliases))
        (assoc :extra-paths (:extra-paths combined-aliases))
        (basis-absolute-source-paths deps-edn-path))))

(s/def ::path fs/->path)
(s/def ::clj-lib? boolean?)
(s/def ::source-path (s/keys :req-un [::path ::clj-lib?]))

(s/fdef source-paths
  :args (s/cat :a (s/keys :req-un [::basis ::aliases ::deps-edn-dir]))
  :ret (s/coll-of fs/path?))
(defn source-paths
  "return the set of source directories on the classpath"
  [{:keys [basis deps-edn-dir aliases] :as args}]
  {:post [%]}
  (let [ignore (ignore-paths (select-keys args [:basis :deps-edn-dir]))
        extra-paths (set (map (fn [extra]
                                (fs/->path deps-edn-dir extra)) (:extra-paths basis)))]
    (->> (:paths basis)
         (map (fn [path]
                (fs/->path deps-edn-dir path)))
         (remove (fn [path]
                   (contains? ignore path)))
         (map (fn [path]
                {:path path
                 :clj-lib? (not (contains? extra-paths path))})))))

(s/fdef gen-source-paths :args (s/cat :a (s/keys :req-un [::deps-edn-path ::deps-bazel ::repository-dir ::deps-repo-tag ::basis ::jar->lib ::deps-bazel]
                                                 :opt-un [::aliases])))
(defn gen-source-paths
  "Given the path to a deps.edn file, gen-dir every source file on the classpath

  deps-edn-path: path to the deps.edn file
  repository-dir: output directory in the bazel sandbox where deps should be downloaded
  deps-repo-tag: Bazel workspace repo for deps, typically `@deps`
  "
  [{:keys [deps-edn-path deps-bazel deps-repo-tag basis jar->lib aliases] :as args}]
  (let [args (merge args
                    {:src-ns->label (->src-ns->label args)
                     :dep-ns->label (->dep-ns->label args)
                     :jar->lib jar->lib})]
    (gen-source-paths- args (source-paths (select-keys args [:aliases :basis :deps-edn-dir :deps-bazel])))))

(s/fdef gen-deps-build :args (s/cat :a (s/keys :req-un [::repository-dir ::dep-ns->label ::deps-build-dir ::deps-repo-tag ::jar->lib ::lib->jar ::lib->deps ::deps-bazel])))
(defn gen-deps-build
  "generates the BUILD file for @deps//: with a single target containing all deps.edn-resolved dependencies"
  [{:keys [repository-dir deps-build-dir dep-ns->label jar->lib lib->jar lib->deps deps-repo-tag deps-bazel] :as args}]
  (println "writing to" (-> (fs/->path deps-build-dir "BUILD.bazel") fs/path->file))
  (spit (-> (fs/->path deps-build-dir "BUILD.bazel") fs/path->file)
        (str/join "\n\n" (concat
                          [(emit-bazel (list 'package (kwargs {:default_visibility ["//visibility:public"]})))
                           (emit-bazel (list 'load "@rules_clojure//:rules.bzl" "clojure_library"))]
                          (->> jar->lib
                               (sort-by (fn [[k v]] (library->label v)))
                               (mapcat (fn [[jarpath lib]]
                                         (let [label (library->label lib)
                                               deps (->> (get lib->deps lib)
                                                         (mapv (fn [lib]
                                                                 (str ":" (library->label lib)))))
                                               external-label (jar->label (select-keys args [:jar->lib :deps-repo-tag]) jarpath)
                                               extra-args (-> deps-bazel
                                                              (get-in [:deps external-label]))]
                                           (assert (re-find #".jar$" (str jarpath)) "only know how to handle jars for now")

                                           (vec
                                            (concat
                                             [(emit-bazel (list 'java_import (kwargs (merge-with into
                                                                                                 {:name label
                                                                                                  :jars [(fs/path-relative-to deps-build-dir jarpath)]}
                                                                                                 (when (seq deps)
                                                                                                   {:deps deps
                                                                                                    :runtime_deps deps})
                                                                                                 extra-args))))]
                                             (->> (find/find-ns-decls [(fs/path->file jarpath)] find/clj)
                                                  ;; markdown-to-hiccup contains a .cljs build, with two identical copies of `markdown/links.cljc`, and two distinct copies of `markdown/core.clj`. For correctness, probably need to get the path inside the jar, and remove files that aren't in the correct position to be loaded
                                                  (group-by parse/name-from-ns-decl)
                                                  vals
                                                  (map first)
                                                  (filter (fn [ns-decl]
                                                            (should-compile-namespace? deps-bazel jarpath (parse/name-from-ns-decl ns-decl))))
                                                  (map (fn [ns-decl]
                                                         (let [ns (parse/name-from-ns-decl ns-decl)
                                                               extra-deps (-> deps-bazel (get-in [:deps (str deps-repo-tag "//:" (internal-dep-ns-aot-label lib ns))]))]
                                                           (emit-bazel (list 'clojure_library (kwargs (->
                                                                                                       (merge-with
                                                                                                        into
                                                                                                        {:name (internal-dep-ns-aot-label lib ns)
                                                                                                         :aot [(str ns)]
                                                                                                         :deps [(str deps-repo-tag "//:" label)
                                                                                                                (str deps-repo-tag "//:org_clojure_clojure")]
                                                                                                         ;; TODO the source jar doesn't need to be in runtime_deps
                                                                                                         :runtime_deps []}
                                                                                                        (ns-deps (select-keys args [:dep-ns->label :jar->lib :deps-repo-tag]) ns-decl :clj)
                                                                                                        extra-deps)
                                                                                                       (as-> m
                                                                                                           (cond-> m
                                                                                                             (seq (:deps m)) (update :deps (comp vec distinct))
                                                                                                             (:deps m) (update :deps (comp vec distinct))))))))))))))))))
                          [(emit-bazel (list 'clojure_library (kwargs
                                                               {:name "__all"
                                                                :deps (->> jar->lib
                                                                           (mapv (comp library->label val)))})))]))

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

(defn deps [{:keys [repository-dir deps-build-dir deps-edn-path deps-repo-tag aliases]
             :or {deps-repo-tag "@deps"}}]
  (assert (re-find #"^@" deps-repo-tag) (print-str "deps repo tag must start with @"))
  (let [deps-edn-path (-> deps-edn-path fs/->path fs/absolute)
        repository-dir (-> repository-dir fs/->path fs/absolute)
        deps-build-dir (-> deps-build-dir fs/->path fs/absolute)
        read-deps (#'read-deps deps-edn-path)
        deps-bazel (parse-deps-bazel read-deps)
        basis (make-basis {:read-deps read-deps
                           :aliases (or (mapv keyword aliases) [])
                           :repository-dir repository-dir
                           :deps-edn-path deps-edn-path})
        jar->lib (->jar->lib basis)
        lib->jar (set/map-invert jar->lib)
        lib->deps (->lib->deps basis)
        dep-ns->label (->dep-ns->label {:basis basis
                                        :deps-bazel deps-bazel
                                        :deps-repo-tag deps-repo-tag})]

    (gen-deps-build {:dep-ns->label dep-ns->label
                     :deps-bazel deps-bazel
                     :repository-dir repository-dir
                     :deps-build-dir deps-build-dir
                     :deps-repo-tag deps-repo-tag
                     :jar->lib jar->lib
                     :lib->jar lib->jar
                     :lib->deps lib->deps})))

(defn srcs [{:keys [repository-dir deps-edn-path deps-repo-tag aliases aot-default]
             :or {deps-repo-tag "@deps"}}]
  {:pre [(re-find #"^@" deps-repo-tag) deps-edn-path repository-dir]}
  (let [deps-edn-path (-> deps-edn-path fs/->path fs/absolute)
        repository-dir (-> repository-dir fs/->path fs/absolute)
        read-deps (#'read-deps deps-edn-path)
        deps-bazel (parse-deps-bazel read-deps)
        aliases (or (mapv keyword aliases) [])
        basis (make-basis {:read-deps read-deps
                           :aliases aliases
                           :repository-dir repository-dir
                           :deps-edn-path deps-edn-path})
        jar->lib (->jar->lib basis)
        class->jar (->class->jar basis)
        args {:aliases aliases
              :deps-bazel deps-bazel
              :deps-edn-path deps-edn-path
              :deps-edn-dir (fs/dirname deps-edn-path)
              :repository-dir repository-dir
              :deps-repo-tag deps-repo-tag
              :basis basis
              :jar->lib jar->lib
              :class->jar class->jar}]
    (gen-source-paths args)))

(defn gen-namespace-loader
  "Given a seq of filenames, generate a namespace that requires all namespaces and contains a function returning all namespaces. Useful for static analysis and CLJS test runners.

  output-ns-name: the name of the namespace to generate
  output-fn-name: the name of the function to generate
  output-filename: the full output path of the file to generate, relative to workspace root
  in-dirs: a seq of directories to search for source files
  exclude-nses: a seq of namespaces to not include in the namespace loader. Including the namespace(s) that will require the loader is a good idea to avoid circular references
  platform: a string, :clj or :cljs
  "
  [{:keys [workspace-root output-filename output-ns-name output-fn-name exclude-nses in-dirs platform]}]
  (assert output-filename)
  (assert output-ns-name)
  (assert output-fn-name)

  (let [in-dirs (edn/read-string in-dirs)
        exclude-nses (edn/read-string exclude-nses)
        _ (assert (s/valid? (s/coll-of string?) in-dirs))
        in-dirs (map (fn [d] (fs/->path workspace-root d)) in-dirs)
        platform (case platform
                   ":clj" find/clj
                   ":cljs" find/cljs
                   find/clj)
        output-ns (symbol output-ns-name)
        nses (as-> in-dirs $
               (map (fn [f]
                      (-> f fs/->path fs/path->file)) $)
               (find/find-ns-decls $ platform)
               (map parse/name-from-ns-decl $)
               (filter identity $)
               (set $)
               (apply disj $ output-ns exclude-nses)
               (sort $))
        conditional-require? (re-matches #".*\.cljc$" output-filename)]
    (spit (fs/path->file (fs/->path workspace-root output-filename))
          (str ";;; Generated by bazel, do not edit\n\n"
               (str "(ns " output-ns "\n")
               (str "  "
                    (when conditional-require?
                      (str "#?(" (if (:cljs (get-in platform [:read-opts :features]))
                                   ":cljs"
                                   ":clj")))
                    "(:require " (str/join "\n    " (vec nses)) "))"
                    (when conditional-require?
                      ")")
                    "\n")
               "\n\n"
               "(defn " (symbol output-fn-name) " []\n"
               "    '[" (str/join "\n    " (vec nses)) "])") :encoding "UTF-8")))


(defn -main [& args]
  ;; binding [*print-length* 10
  ;;          *print-level* 3]
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
                     (System/getenv "BUILD_WORKSPACE_DIRECTORY") (assoc :workspace-root (-> (System/getenv "BUILD_WORKSPACE_DIRECTORY") fs/->path))
                     (:workspace-root opts) (update :workspace-root (comp fs/absolute fs/->path))))
        _ (assert (fs/path? (:workspace-root opts)))
        f (case cmd
            :deps deps
            :srcs srcs
            :ns-loader gen-namespace-loader)]
    (f opts)))
