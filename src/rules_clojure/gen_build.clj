(ns rules-clojure.gen-build
  "Tools for generating BUILD.bazel files for clojure deps"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.tools.deps :as deps]
            [clojure.tools.deps.util.concurrent :as concurrent]
            [rules-clojure.fs :as fs]
            [rules-clojure.namespace.find :as find]
            [rules-clojure.namespace.parse :as parse])
  (:import (clojure.lang IPersistentList IPersistentMap IPersistentVector Keyword Var)
           (java.nio.file Path)

           (java.util.jar JarEntry JarFile))
  (:gen-class))

(set! *warn-on-reflection* true)

(defn update-existing
  "Like `update`, but only applies `f` when `k` is present in `m`."
  [m k f & args]
  (if (contains? m k)
    (apply update m k f args)
    m))

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

(defn rewrite-label
  "Rewrite a Bazel label to use the canonical root module reference (@@//)
  when it references the root module by its workspace/module name."
  [root-module-name label]
  (if (and (not (str/blank? root-module-name))
           (str/starts-with? label (str "@" root-module-name "//")))
    (str "@@" (subs label (inc (count root-module-name))))
    label))

(defn normalize-self-repo-label
  "Strip the @<repo>// prefix from a label that references the deps repo itself,
  leaving just the local label. Labels referencing other repos are left unchanged.
  e.g. \"@deps//:ns_org_clojure_tools_logging\" -> \":ns_org_clojure_tools_logging\"
       \"@@//src/griffin:logging\" -> \"@@//src/griffin:logging\" (unchanged)"
  [deps-repo-prefix label]
  (if (str/starts-with? label deps-repo-prefix)
    (subs label (count deps-repo-prefix))
    label))

(defn normalize-deps-overrides
  "Normalize all labels in a deps-bazel override map:
  - Strip @<repo>// prefix from keys and self-referencing values
  - Rewrite @<root-module> references to canonical @@// form"
  [deps-map deps-repo-prefix root-module-name]
  (let [normalize-label (fn [label]
                          (let [label (normalize-self-repo-label deps-repo-prefix label)]
                            (if (not (str/blank? root-module-name))
                              (rewrite-label root-module-name label)
                              label)))
        normalize-val (fn [v]
                        (cond
                          (string? v) (normalize-label v)
                          (vector? v) (mapv normalize-label v)
                          :else v))]
    (into {} (map (fn [[k v]]
                    [(if (string? k) (normalize-label k) k)
                     (if (map? v)
                       (into {} (map (fn [[k2 v2]] [k2 (normalize-val v2)])) v)
                       (normalize-val v))])) deps-map)))

(defn parse-deps-bazel
  "extra data under `:bazel` in a deps.edn file for controlling gen-build. Supported keys:

  :deps - (map-of bazel-target to (s/keys :opt-un [:srcs :deps])), extra targets to include on a clojure_library. This is useful for e.g. adding native library dependencies onto a .clj file"
  [read-deps root-module-name]
  {:post [(validate! ::deps-bazel %)]}
  (let [bazel (or (:bazel read-deps) {})
        ;; Determine the deps repo prefix from any key in the :deps map
        deps-repo-prefix (when-let [k (some #(when (and (string? %) (str/includes? % "//")) %) (keys (:deps bazel)))]
                           (subs k 0 (+ 2 (str/index-of k "//"))))]
    (cond-> bazel
      (:deps bazel) (update :deps normalize-deps-overrides (or deps-repo-prefix "") root-module-name))))

(s/fdef ->jar->lib :args (s/cat :b ::basis) :ret ::jar->lib)
(defn ->jar->lib
  "Return a map of jar path to library name ('org.clojure/clojure)"
  [basis]
  {:post [(map? %)]}
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
  (let [prefix (if deps-repo-tag (str deps-repo-tag "//") "")]
    (str prefix ":ns_" (library->label lib) "_" (library->label ns))))

(s/def ::dep-ns->label (s/map-of keyword? (s/map-of symbol? string?)))

(defn jar-files [path]
  (-> (JarFile. (str path))
      (.entries)
      (enumeration-seq)
      (->> (map (fn [^JarEntry e]
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
  (let [root-resource (#'clojure.core/root-resource ns)
        class-file (str (.substring ^String root-resource 1) "__init.class")]
    (some #(= class-file %) (classpath-files path))))

(def special-namespaces '#{clojure.core clojure.core.specs.alpha})

(defn should-compile-namespace? [deps-bazel path ns]
  (and (not (contains? special-namespaces ns))
       (not (contains? (get-in deps-bazel [:no-aot]) ns))
       (not (is-aoted? path ns))))

(defn ns-matches-path?
  [ns path]
  (str/starts-with?
   path
   (str/escape (str ns) {\. "/" \- "_"})))

(defn ->dep-ns->label [{:keys [basis deps-bazel] :as args}]
  {:pre [(map? basis)
         deps-bazel]
   :post [(map? %)]}
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

(s/def ::class->jar (s/map-of symbol? fs/path?))
(s/fdef ->class->jar :args (s/cat :b ::basis) :ret ::class->jar)
(defn ->class->jar
  "returns a map of class symbol to jarpath for all jars on the classpath"
  [basis]
  {:post [(map? %)]}
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
              dep-map))) {})))

(s/fdef jar->label :args (s/cat :a (s/keys :req-un [::jar->lib] :opt-un [::deps-repo-tag]) :p fs/path?) :ret string?)
(defn jar->label
  "Given a .jar path, return the bazel label. `deps-repo-tag` is the name of the bazel repository where deps are held, e.g `@deps`"
  [{:keys [deps-repo-tag jar->lib] :as args} jarpath]
  (let [prefix (if deps-repo-tag (str deps-repo-tag "//") "")]
    (str prefix ":" (->> jarpath (get! jar->lib) library->label))))

(defn get-dep-ns->label [dep-ns->label platform ns]
  {:pre [(keyword? platform)
         (symbol? ns)]}
  (get-in dep-ns->label [platform ns]))

(s/fdef ns->label :args (s/cat :a (s/keys :req-un [(or ::src-ns->label ::dep-ns->label)]) :n symbol? :p keyword?))
(defn ns->label
  "given the ns-map and a namespace, return a map of `:src` or `:dep` to the file/jar where it is located"
  [{:keys [src-ns->label dep-ns->label deps-repo-tag] :as args} ns platform]
  {:pre [(keyword? platform)]}
  (let [prefix (if deps-repo-tag (str deps-repo-tag "//") "")
        label (or (get src-ns->label ns)
                  (when-let [label (get-dep-ns->label dep-ns->label platform ns)]
                    (str prefix ":" label)))]
    (when label
      {:deps [label]})))

(defn get-ns-decl [^Path path platform]
  (try
    (with-open [rdr (java.io.PushbackReader. (io/reader (.toFile path)))]
      (loop []
        (let [form (clojure.core/read {:read-cond :allow
                                       :features #{platform}
                                       :eof ::eof}
                                      rdr)]
          (cond
            (= ::eof form) nil
            (and (sequential? form) (= 'ns (first form))) form
            :else (recur)))))
    (catch Exception e
      (throw (ex-info (str "while reading " path) {:path path
                                                   :platform platform} e)))))

(defn get-ns-meta
  "return any metadata attached to the namespace, or nil"
  [ns-decl]
  (assert (= 'ns (first ns-decl)))
  (let [decl (nnext ns-decl)
        decl (if (string? (first decl)) (next decl) decl)]
    (when (map? (first decl))
      (first decl))))

(s/fdef ns-deps :args (s/cat :a (s/keys :req-un [::jar->lib]) :d ::ns-decl))
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

(s/fdef ns-import-deps :args (s/cat :a (s/keys :req-un [::deps-repo-tag ::class->jar ::jar->lib]) :n ::ns-decl))
(defn ns-import-deps
  "Given the ns declaration for a .clj file, return a map of {:srcs [labels], :data [labels]} for all :import statements"
  [{:keys [class->jar jar->lib deps-repo-tag] :as args} ns-decl]
  (assert class->jar)
  (let [[_ns _name & refs] ns-decl]
    (->> refs
         (filter (fn [r]
                   (= :import (first r))))
         (mapcat rest)
         (mapcat (fn [form]
                   (cond
                     (symbol? form) [form]
                     (sequential? form) (let [package (first form)
                                              classes (rest form)]
                                          (map (fn [c]
                                                 (symbol (str package "." c))) classes)))))
         (map (fn [class]
                (when-let [jar (get class->jar class)]
                  {:deps [(jar->label {:jar->lib jar->lib :deps-repo-tag deps-repo-tag} jar)]})))
         (filter identity)
         (distinct)
         (apply merge-with concat))))

(defn ns-gen-class-deps
  "Given the ns declaration for a .clj file, return extra {:deps} from a :gen-class :extends"
  [{:keys [class->jar jar->lib deps-repo-tag] :as args} ns-decl]
  (let [[_ns _name & refs] ns-decl]
    (->> refs
         (filter (fn [r]
                   (= :gen-class (first r))))
         (first)
         ((fn [form]
            (let [args (apply hash-map (rest form))]
              (when-let [class (:extends args)]
                (when-let [jar (get class->jar class)]
                  {:deps [(jar->label {:jar->lib jar->lib :deps-repo-tag deps-repo-tag} jar)]}))))))))

(s/fdef clj-path? :args (s/cat :p fs/path?) :ret boolean?)
(defn clj-path? [path]
  (str/ends-with? (str path) ".clj"))

(defn cljc-path? [path]
  (str/ends-with? (str path) ".cljc"))

(defn cljs-path? [path]
  (str/ends-with? (str path) ".cljs"))

(defn clj*-path? [path]
  (let [s (str path)]
    (or (str/ends-with? s ".clj")
        (str/ends-with? s ".cljc")
        (str/ends-with? s ".cljs"))))

(defn js-path? [path]
  (str/ends-with? (str path) ".js"))

(defn test-path? [path]
  (str/includes? (str path) "_test.clj"))

(defn src-path? [path]
  (not (test-path? path)))

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
                 (.startsWith ^Path path ^Path (fs/->path deps-edn-dir p))))
       first))


(s/fdef ns-rules :args (s/cat :a (s/keys :req-un [::basis ::deps-edn-dir ::jar->lib ::deps-bazel]) :p (s/coll-of fs/path?)))
(defn ns-rules
  "given a group of paths (sharing a basename), return the Bazel rules for the namespace.
  Returns a vector of {:type keyword :attrs map} — pure data, no serialization."
  [{:keys [deps-bazel deps-repo-tag] :as args} paths]
  (assert (map? (:src-ns->label args)))
  (assert (sequential? paths))
  (try
    (let [clj? (some clj-path? paths)
          cljc? (some cljc-path? paths)
          js? (some js-path? paths)

          ns-decl-platforms (->>
                             (filter clj*-path? paths)
                             (mapcat (fn [path]
                                       (let [file-platforms (cond
                                                              (clj-path? path) #{:clj}
                                                              (cljs-path? path) #{:cljs}
                                                              (cljc-path? path) #{:clj :cljs})]
                                         (->> file-platforms
                                              (map (fn [platform]
                                                     [(get-ns-decl path platform) platform])))))))
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
          ns-library-meta (some-> (get ns-meta :bazel/clojure_library)
                                  (update-existing :deps #(mapv name %))
                                  (update-existing :runtime_deps #(mapv name %)))
          ns-test-meta (some-> (get ns-meta :bazel/clojure_test)
                               (update-existing :tags #(mapv name %))
                               (update-existing :size name)
                               (update-existing :timeout name))
          ns-binary-meta (some-> (get ns-meta :bazel/clojure_binary)
                                 (update-existing :jvm_flags #(mapv name %)))

          aot (if (and (or clj? cljc?) (not test?) (get ns-library-meta :aot true))
                [(str ns-name)]
                [])
          ns-library-meta (dissoc ns-library-meta :aot)]

      (->>
       [(when (seq ns-decls)
          {:type :clojure_library
           :attrs (-> (merge-with into
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
                              (seq (:deps m)) (update :deps (comp vec dedupe sort)))))})
        (when (and (or clj?
                       cljc?) test?)
          {:type :clojure_test
           :attrs (merge-with into
                              {:name test-label
                               :test_ns (str ns-name)
                               :deps [(str ":" ns-label)]}
                              clojure-test-args
                              ns-test-meta)})
        (when ns-binary-meta
          (let [binary-name (or (:name ns-binary-meta) (str ns-label ".bin"))
                base-jvm-flags (vec (:jvm_flags clojure-library-args))]
            {:type :clojure_binary
             :attrs (-> (merge
                         {:main_class "clojure.main"
                          :args ["-m" (str ns-name)]}
                         (dissoc ns-binary-meta :name))
                        (assoc :name binary-name)
                        (update :jvm_flags #(into base-jvm-flags %))
                        (update :runtime_deps (fnil conj []) (str ":" ns-label)))}))
        (when js?
          {:type :java_library
           :attrs {:name ns-label
                   :resources (->> paths (filter js-path?) (mapv fs/filename))
                   :resource_strip_prefix (strip-path (select-keys args [:basis :deps-edn-dir]) path)}})]

       (filterv identity)))
    (catch Throwable t
      (println "while processing" paths)
      (throw t))))

(defn emit-rule
  "Serialize a {:type :attrs} rule map to a Bazel string."
  [{:keys [type attrs]}]
  (emit-bazel (list (symbol (name type)) (kwargs attrs))))

(s/fdef gen-dir :args (s/cat :a (s/keys :req-un [::deps-edn-dir ::basis ::jar->lib ::deps-repo-tag]) :f fs/path?))
(defn gen-dir
  "given a source directory, write a BUILD.bazel for all .clj files in the directory. non-recursive"
  [{:keys [deps-edn-dir] :as args} dir]
  (assert (map? (:src-ns->label args)))
  (let [paths (->> (fs/ls dir)
                   (filter (fn [p]
                             (or (clj*-path? p)
                                 (js-path? p))))
                   (sort-by str))
        subdirs (->> dir
                     fs/ls
                     (filter (fn [^Path p]
                               (-> p .toFile fs/directory?)))
                     (sort-by str))
        clj-subdirs (->>
                     subdirs
                     (filter (fn [p]
                               (some clj*-path? (seq (fs/ls-r p))))))
        [has-binary? rules] (reduce (fn [[hb? acc] rule]
                                      [(or hb? (= :clojure_binary (:type rule)))
                                       (conj acc (emit-rule rule))])
                                    [false []]
                                    (->> paths
                                         (group-by fs/basename)
                                         (mapcat (fn [[_base paths]]
                                                   (ns-rules args paths)))))

        content (str "#autogenerated, do not edit\n"
                     (emit-bazel (list 'package (kwargs {:default_visibility ["//visibility:public"]})))
                     "\n"
                     (emit-bazel (apply list 'load "@rules_clojure//:rules.bzl"
                                        (cond-> ["clojure_library" "clojure_test"]
                                          has-binary? (conj "clojure_binary"))))
                     "\n"
                     "\n"
                     (str/join "\n\n" rules)
                     "\n"
                     "\n"
                     (when (or (seq paths) (seq clj-subdirs))
                       (str
                        (emit-bazel (list 'clojure_library (kwargs {:name "__clj_lib"
                                                                    :deps (vec
                                                                           (concat
                                                                            (dedupe (map (fn [p] (str ":" (fs/basename p))) paths))
                                                                            (map (fn [p]
                                                                                   (str "//" (fs/path-relative-to deps-edn-dir p) ":__clj_lib")) clj-subdirs)))})))
                        "\n"
                        "\n"
                        (emit-bazel (list 'filegroup (kwargs (merge
                                                              {:name "__clj_files"
                                                               :srcs (mapv fs/filename paths)
                                                               :data (mapv (fn [p]
                                                                             (str "//" (fs/path-relative-to deps-edn-dir p) ":__clj_files")) clj-subdirs)})))))))]
    (let [build-file (-> dir (fs/->path "BUILD.bazel") fs/path->file)
          changed? (not= content (when (.exists build-file) (slurp build-file :encoding "UTF-8")))]
      (when changed?
        (spit build-file content :encoding "UTF-8"))
      {:files (count paths) :wrote (if changed? 1 0)})))

(s/fdef gen-source-paths- :args (s/cat :a (s/keys :req-un [::deps-edn-dir ::src-ns->label ::dep-ns->label ::jar->lib ::deps-bazel]) :paths (s/coll-of fs/path?)))
(defn gen-source-paths-
  "gen-dir for every directory on the classpath."
  [args paths]
  (assert (map? (:src-ns->label args)))
  (let [results (->> paths
                     (mapcat (fn [path]
                               (fs/ls-r path)))
                     (map fs/dirname)
                     (distinct)
                     (sort-by (comp count str))
                     (reverse)
                     (pmap (fn [dir]
                             (gen-dir args dir))))]
    (reduce (fn [acc m] (merge-with + acc m))
            {:files 0 :wrote 0 :dirs (count results)}
            results)))

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
        (basis-absolute-source-paths deps-edn-path))))

(s/fdef source-paths :args (s/cat :a (s/keys :req-un [::basis ::aliases ::deps-edn-dir])) :ret (s/coll-of fs/path?))
(defn source-paths
  "return the set of source directories on the classpath"
  [{:keys [basis deps-edn-dir aliases] :as args}]
  {:post [%]}
  (let [ignore (ignore-paths (select-keys args [:basis :deps-edn-dir]))]
    (->>
     (:paths basis)
     (map (fn [path]
            (fs/->path deps-edn-dir path)))
     (remove (fn [path]
               (contains? ignore path))))))

(s/fdef gen-source-paths :args (s/cat :a (s/keys :req-un [::deps-edn-path ::deps-bazel ::repository-dir ::basis ::jar->lib ::deps-bazel]
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

(s/fdef gen-deps-build :args (s/cat :a (s/keys :req-un [::repository-dir ::dep-ns->label ::deps-build-dir ::jar->lib ::lib->jar ::lib->deps ::deps-bazel])))
(defn gen-deps-build
  "generates the BUILD file for @deps//: with a single target containing all deps.edn-resolved dependencies"
  [{:keys [repository-dir deps-build-dir dep-ns->label jar->lib lib->jar lib->deps deps-repo-tag deps-bazel] :as args}]
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
                                               external-label (jar->label (select-keys args [:jar->lib]) jarpath)
                                               extra-args (-> deps-bazel
                                                              (get-in [:deps external-label]))
                                               _ (assert (re-find #".jar$" (str jarpath)) "only know how to handle jars for now")
                                               jarfile (JarFile. (fs/path->file jarpath))]
                                           (vec
                                            (concat
                                             [(emit-bazel (list 'java_import (kwargs (merge-with into
                                                                                                 {:name label
                                                                                                  :jars [(fs/path-relative-to deps-build-dir jarpath)]}
                                                                                                 (when (seq deps)
                                                                                                   {:deps deps
                                                                                                    :runtime_deps deps})
                                                                                                 extra-args))))]
                                             (->>
                                               ;; TODO: Update to tools.namespace 1.4.1 which has metadata of the source path on the ns-decl so this dance isn't needed.
                                              (find/sources-in-jar jarfile find/clj)
                                              (keep
                                               (fn [^JarEntry entry]
                                                 (when-let [ns-decl (find/read-ns-decl-from-jarfile-entry jarfile entry find/clj)]
                                                   (when (ns-matches-path? (parse/name-from-ns-decl ns-decl) (.getRealName entry))
                                                     ns-decl))))
                                              (filter
                                               (fn [ns-decl]
                                                 (should-compile-namespace? deps-bazel jarpath (parse/name-from-ns-decl ns-decl))))
                                              (group-by parse/name-from-ns-decl)
                                              (map (fn [[ns ns-decls]]
                                                     (let [extra-deps (-> deps-bazel (get-in [:deps (str (when deps-repo-tag (str deps-repo-tag "//")) ":" (internal-dep-ns-aot-label lib ns))]))]
                                                       (emit-bazel (list 'clojure_library (kwargs (->
                                                                                                   (merge-with
                                                                                                    into
                                                                                                    {:name (internal-dep-ns-aot-label lib ns)
                                                                                                     :aot [(str ns)]
                                                                                                     :deps [(str (when deps-repo-tag (str deps-repo-tag "//")) ":" label)
                                                                                                            (str (when deps-repo-tag (str deps-repo-tag "//")) ":org_clojure_clojure")]
                                                                                                        ;; TODO the source jar doesn't need to be in runtime_deps
                                                                                                     :runtime_deps []}
                                                                                                    (apply merge-with into (map #(ns-deps (select-keys args [:dep-ns->label :jar->lib :deps-repo-tag]) % :clj) ns-decls))
                                                                                                    extra-deps)
                                                                                                   (as-> m
                                                                                                         (cond-> m
                                                                                                           (seq (:deps m)) (update :deps (comp vec distinct))
                                                                                                           (:deps m) (update :deps (comp vec distinct))))))))))))))))))
                          [(emit-bazel (list 'java_library (kwargs
                                                            {:name "__all"
                                                             :runtime_deps (->> jar->lib
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

(defn deps [{:keys [repository-dir deps-build-dir deps-edn-path aliases root-module-name]}]
  (let [deps-edn-path (-> deps-edn-path fs/->path fs/absolute)
        repository-dir (-> repository-dir fs/->path fs/absolute)
        deps-build-dir (-> deps-build-dir fs/->path fs/absolute)
        read-deps (#'read-deps deps-edn-path)
        deps-bazel (parse-deps-bazel read-deps (or root-module-name ""))
        basis (make-basis {:read-deps read-deps
                           :aliases (or (mapv keyword aliases) [])
                           :repository-dir repository-dir
                           :deps-edn-path deps-edn-path})
        jar->lib (->jar->lib basis)
        lib->jar (set/map-invert jar->lib)
        lib->deps (->lib->deps basis)
        dep-ns->label (->dep-ns->label {:basis basis
                                        :deps-bazel deps-bazel})]

    (gen-deps-build {:dep-ns->label dep-ns->label
                     :deps-bazel deps-bazel
                     :repository-dir repository-dir
                     :deps-build-dir deps-build-dir
                     :jar->lib jar->lib
                     :lib->jar lib->jar
                     :lib->deps lib->deps})))

(defn srcs [{:keys [repository-dir deps-edn-path deps-repo-tag aliases aot-default root-module-name]
             :or {deps-repo-tag "@deps"}}]
  {:pre [(re-find #"^@" deps-repo-tag) deps-edn-path repository-dir]}
  (let [deps-edn-path (-> deps-edn-path fs/->path fs/absolute)
        repository-dir (-> repository-dir fs/->path fs/absolute)
        read-deps (#'read-deps deps-edn-path)
        deps-bazel (parse-deps-bazel read-deps (or root-module-name ""))
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
    (let [result (f opts)]
      (when (map? result)
        (println (format "gen_srcs: checked %d files across %d dirs, wrote %d BUILD files"
                         (:files result) (:dirs result) (:wrote result))))
      (shutdown-agents))))
