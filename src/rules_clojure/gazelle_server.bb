#!/usr/bin/env bb

;; Gazelle Clojure plugin parser, babashka edition.
;;
;; Speaks the same newline-delimited JSON-line protocol the Go plugin
;; (gazelle/clojureparser) expects, but bypasses tools.deps entirely:
;;
;;   - `init`: read deps.edn + parse @deps/BUILD.bazel for the resolved
;;     coord -> jar/label mapping. Cache the per-jar ns scan to disk keyed
;;     on a sha that mixes @deps/BUILD.bazel content, the cache-format
;;     version, and the deps.edn :bazel :no-aot set, so subsequent
;;     invocations skip the jar walk altogether.
;;   - `parse`: for each basename group in the directory, derive the
;;     `{:type ... :attrs ...}` rule specs the Go plugin translates into
;;     Gazelle *rule.Rule.
;;
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.shell :as sh]
         '[clojure.string :as str]
         '[cognitect.transit :as transit]
         '[edamame.core :as edamame])

(import '[java.io BufferedReader InputStreamReader OutputStreamWriter
          BufferedWriter ByteArrayInputStream ByteArrayOutputStream]
        '[java.security MessageDigest]
        '[java.util.jar JarFile JarEntry])

;; ---------------------------------------------------------------------------
;; Utilities
;; ---------------------------------------------------------------------------

(defn sha256 [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes s "UTF-8"))
    (apply str (map #(format "%02x" %) (.digest md)))))

;; ---------------------------------------------------------------------------
;; NS form parsing (edamame, supports reader conditionals)
;; ---------------------------------------------------------------------------

;; edamame opts shared by parse-ns-form (source files) and
;; read-ns-from-jar-entry (jar contents). :readers passes unknown
;; reader tags through unchanged so a tagged literal in an unrelated
;; form doesn't blow up ns-form extraction. :auto-resolve mirrors
;; tools.reader's ::current → 'user behaviour for top-level forms.
(def ^:private edamame-base-opts
  {:read-cond :allow
   :all true
   :readers (fn [_tag] identity)
   :regex #(str "REGEX:" %)
   :auto-resolve (fn [x] (if (= :current x) 'user x))})

(defn- edamame-opts [features]
  (assoc edamame-base-opts :features features))

(defn parse-ns-form
  "Find the (ns ...) form in source. Reads all top-level forms so a leading
  (set! ...) or in-ns before (ns ...) doesn't hide the namespace. Returns nil
  on parse failure or when no ns form is present."
  [source features]
  (try
    (->> (edamame/parse-string-all source (edamame-opts features))
         (filter #(and (list? %) (= 'ns (first %))))
         first)
    (catch Exception e
      (binding [*out* *err*]
        (println "gazelle-server.bb: ns-form parse failed:"
                 (.getName (class e)) (.getMessage e)))
      nil)))

(defn deps-from-libspec [prefix form]
  (cond
    (and (sequential? form) (symbol? (first form))
         (not-any? keyword? form) (> (count form) 1))
    (mapcat #(deps-from-libspec
              (symbol (str (when prefix (str prefix ".")) (first form))) %)
            (rest form))

    (and (sequential? form)
         (or (symbol? (first form)) (string? (first form)))
         (or (keyword? (second form)) (= 1 (count form))))
    (when-not (= :as-alias (second form))
      (deps-from-libspec prefix (first form)))

    (symbol? form)
    [(symbol (str (when prefix (str prefix ".")) form))]

    :else nil))

(defn deps-from-ns-form [form]
  (when (and (sequential? form)
             (contains? #{:use :require :require-macros 'use 'require 'require-macros}
                        (first form)))
    (mapcat #(deps-from-libspec nil %) (rest form))))

(defn deps-from-ns-decl [decl]
  (set (mapcat deps-from-ns-form decl)))

(defn imports-from-ns-decl [decl]
  (let [[_ns _name & refs] decl]
    (->> refs
         (filter #(and (sequential? %) (= :import (first %))))
         (mapcat rest)
         (mapcat (fn [form]
                   (cond
                     (symbol? form) [form]
                     (sequential? form)
                     (map #(symbol (str (first form) "." %)) (rest form)))))
         set)))

(defn gen-class-extends [decl]
  (let [[_ns _name & refs] decl]
    (some->> refs
             (filter #(and (sequential? %) (= :gen-class (first %))))
             first rest (apply hash-map) :extends)))

(defn get-ns-meta [ns-decl]
  (when ns-decl
    (let [items (drop 2 ns-decl)
          items (if (string? (first items)) (rest items) items)]
      (when (and (seq items) (map? (first items)))
        (first items)))))

;; ---------------------------------------------------------------------------
;; @deps/BUILD.bazel scan + on-disk cache
;; ---------------------------------------------------------------------------

(defn- read-ns-from-jar-entry
  "Read the (ns ...) form from a jar entry; return the ns symbol or nil.
  Lenient: any parse failure (unknown reader tag, malformed form, IO)
  logs a one-line warning and returns nil. Walks all top-level forms so a
  leading (set! ...) before (ns ...) doesn't hide the namespace. The
  .cljs/.cljc path in scan-jar calls this so that an in-jar ns declaration
  that disagrees with its file path is respected; the .clj path uses cheap
  path-derivation since disagreements there are rare and not worth the
  slurp+parse cost per entry."
  [^JarFile jf ^JarEntry entry features]
  (try
    (let [content (slurp (.getInputStream jf entry))]
      (->> (edamame/parse-string-all content (edamame-opts features))
           (filter #(and (list? %) (= 'ns (first %))))
           first
           second))
    (catch Exception e
      (binding [*out* *err*]
        (println "gazelle-server.bb: skipping jar entry" (.getName entry)
                 "in" (.getName jf) "(" (.getName (class e)) ":" (.getMessage e) ")"))
      nil)))

(defn- jar-path->ns-sym
  "Convert a jar-entry path like `cljs/spec/alpha` to its ns symbol
  `cljs.spec.alpha`."
  [path]
  (symbol (str/replace (str/replace path "/" ".") "_" "-")))

(defn- scan-jar
  "Scan a jar for class entries + clj/cljc/cljs ns entries; return
  {:classes :clj-nses :cljs-nses :aoted}. Uses with-open so the JarFile
  is closed even on exception. Uses str/ends-with?+subs instead of
  per-entry regex (measured ~20x faster on banksy scale). Uses
  transients to avoid the 4-atom CAS overhead.

  Outer exception (corrupt jar, IOException, etc.) logs to *err* and
  returns empty maps. The caller's dep map will be missing entries
  from this jar, but the user sees *which* jar failed."
  [^String jar-path label]
  (try
    (with-open [jf (JarFile. jar-path)]
      (let [result
            (reduce
             (fn [{:keys [classes clj-nses cljs-nses init-classes] :as acc} ^JarEntry e]
               (let [nm (.getName e)]
                 (cond
                   (str/ends-with? nm ".class")
                   (let [class-name (subs nm 0 (- (count nm) 6))]
                     (cond-> acc
                       (not= class-name "module-info")
                       (assoc :classes
                              (assoc! classes (symbol (str/replace class-name "/" ".")) label))
                       (str/ends-with? nm "__init.class")
                       (assoc :init-classes
                              (assoc! init-classes
                                      (jar-path->ns-sym
                                       (subs nm 0 (- (count nm) (count "__init.class"))))
                                      true))))

                   (str/ends-with? nm ".cljc")
                   (let [base (subs nm 0 (- (count nm) 5))
                         clj-ns (jar-path->ns-sym base)
                         cljs-ns (read-ns-from-jar-entry jf e #{:cljs})]
                     (cond-> (assoc acc :clj-nses (assoc! clj-nses clj-ns label))
                       cljs-ns (assoc :cljs-nses (assoc! cljs-nses cljs-ns label))))

                   (str/ends-with? nm ".clj")
                   (assoc acc :clj-nses
                          (assoc! clj-nses
                                  (jar-path->ns-sym (subs nm 0 (- (count nm) 4)))
                                  label))

                   (str/ends-with? nm ".cljs")
                   (if-let [cljs-ns (read-ns-from-jar-entry jf e #{:cljs})]
                     (assoc acc :cljs-nses (assoc! cljs-nses cljs-ns label))
                     acc)

                   :else acc)))
             {:classes (transient {})
              :clj-nses (transient {})
              :cljs-nses (transient {})
              :init-classes (transient {})}
             (enumeration-seq (.entries jf)))]
        {:classes (persistent! (:classes result))
         :clj-nses (persistent! (:clj-nses result))
         :cljs-nses (persistent! (:cljs-nses result))
         :aoted (set (keys (persistent! (:init-classes result))))}))
    (catch Exception e
      (binding [*out* *err*]
        (println "gazelle-server.bb: scan-jar failed for" jar-path
                 "(" (.getName (class e)) ":" (.getMessage e) ")"))
      {:classes {} :clj-nses {} :cljs-nses {} :aoted #{}})))

(def ^:private special-namespaces '#{clojure.core clojure.core.specs.alpha})

(defn- parse-deps-build
  "Walks @deps/BUILD.bazel's top-level clojure_library + java_import blocks
  and returns {:clj-ns->label :cljs-ns->label :class->label :sha256}.
  Identifies each block by opening-line regex, then re-finds attrs inside
  the multi-line block text. (A single-line regex over the whole file
  would match zero rules: @deps/BUILD.bazel emits multi-line forms.)"
  [deps-build-path no-aot-set]
  (let [content (slurp deps-build-path)
        lines (vec (str/split-lines content))
        n (count lines)
        aot-ns->label (atom {})
        jar-imports (atom [])
        block-end (fn [start]
                    (loop [j start]
                      (cond
                        (>= j n) j
                        (re-find #"^\)\s*$" (nth lines j)) j
                        :else (recur (inc j)))))]
    (loop [i 0]
      (when (< i n)
        (let [line (nth lines i)]
          (cond
            (re-find #"^clojure_library\(\s*$" line)
            (let [end (block-end (inc i))
                  block (str/join "\n" (subvec lines i (min (inc end) n)))
                  cl-name (some-> (re-find #"name\s*=\s*\"([^\"]+)\"" block) second)
                  aot-block (some-> (re-find #"aot\s*=\s*\[([^\]]*)\]" block) second)
                  aot-nses (when aot-block
                             (mapv second (re-seq #"\"([^\"]+)\"" aot-block)))]
              (when cl-name
                (doseq [aot-ns aot-nses]
                  (swap! aot-ns->label assoc (symbol aot-ns) cl-name)))
              (recur (inc end)))

            (re-find #"^java_import\(\s*$" line)
            (let [end (block-end (inc i))
                  block (str/join "\n" (subvec lines i (min (inc end) n)))
                  ji-name (some-> (re-find #"name\s*=\s*\"([^\"]+)\"" block) second)
                  jars-block (some-> (re-find #"(?s)jars\s*=\s*\[([^\]]*)\]" block) second)]
              (when (and ji-name jars-block)
                (doseq [[_ jar-rel] (re-seq #"\"([^\"]+\.jar)\"" jars-block)]
                  (swap! jar-imports conj {:label ji-name :jar-rel jar-rel})))
              (recur (inc end)))

            :else
            (recur (inc i))))))

    (let [deps-dir (str (fs/parent deps-build-path))
          jar-results (->> @jar-imports
                           (pmap (fn [{:keys [label jar-rel]}]
                                   (let [jar-path (str (fs/path deps-dir jar-rel))]
                                     (if (fs/exists? jar-path)
                                       (assoc (scan-jar jar-path label) :label label)
                                       (do (binding [*out* *err*]
                                             (println "gazelle-server.bb: jar referenced in @deps/BUILD.bazel"
                                                      "but missing on disk:" jar-path
                                                      "(label" label "(skipping))"))
                                           nil)))))
                           (filter some?))
          class->label (into {} (mapcat :classes) jar-results)
          all-aoted (into #{} (mapcat :aoted) jar-results)
          clj-lib-ns->label (into {} (mapcat :clj-nses) jar-results)
          cljs-lib-ns->label (into {} (mapcat :cljs-nses) jar-results)
          clj-ns->label (reduce-kv (fn [m ns-sym lib-label]
                                     (let [aot-label (get @aot-ns->label ns-sym)
                                           should-compile? (and (not (contains? special-namespaces ns-sym))
                                                                (not (contains? no-aot-set ns-sym))
                                                                (not (contains? all-aoted ns-sym)))]
                                       (assoc m ns-sym (if (and should-compile? aot-label)
                                                         aot-label
                                                         lib-label))))
                                   {} clj-lib-ns->label)
          cljs-ns->label cljs-lib-ns->label]
      {:clj-ns->label clj-ns->label
       :cljs-ns->label cljs-ns->label
       :class->label class->label
       :sha256 (sha256 content)})))

(defn- transit-write [data]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) data)
    (.toByteArray out)))

(defn- transit-read [^bytes bs]
  (transit/read (transit/reader (ByteArrayInputStream. bs) :json)))

(defn- cache-dir-for [project-root]
  (str (fs/path project-root "target" "gazelle_server_cache")))

;; Bump cache-format-version when the on-disk shape of the transit
;; files changes (extra fields, different value types). The sha is
;; mixed into the cache key so a format bump invalidates every cache,
;; even when deps.edn / @deps/BUILD.bazel are unchanged.
(def ^:private cache-format-version "v1")

(defn- read-cached-data
  "Read a transit file. Throws ex-info on corruption so load-or-build-cache
  can react by treating the cache as missing and rebuilding."
  [path]
  (try (transit-read (fs/read-all-bytes path))
       (catch Throwable t
         (throw (ex-info (str "cache transit read failed at " path)
                         {:path path :cause-class (.getName (class t))}
                         t)))))

(defn- delete-cache-dir [dir-path]
  (try (fs/delete-tree dir-path) (catch Throwable _)))

(defn- load-or-build-cache
  "Load or rebuild the disk cache. Cache key = sha256(cache-format-version
  + @deps/BUILD.bazel content + sorted no-aot-set), because no-aot-set
  participates in parse-deps-build's clj-ns->label decision and a stale
  key would silently keep wrong labels after a deps.edn :bazel :no-aot
  change. A corrupt cache (truncated transit, deserialisation error) is
  treated as missing: the cache dir is dropped and the build path runs."
  [project-root deps-build-path no-aot-set]
  (let [current-sha (sha256 (str cache-format-version "\n"
                                 (slurp deps-build-path) "\n"
                                 (pr-str (sort no-aot-set))))
        dir-path (cache-dir-for project-root)
        sha-file (str (fs/path dir-path "sha256"))
        cached-sha (when (fs/exists? sha-file)
                     (try (str/trim (slurp sha-file))
                          (catch java.io.IOException e
                            (binding [*out* *err*]
                              (println "gazelle-server.bb: cache sha read failed:"
                                       (.getMessage e) "(rebuilding)"))
                            nil)))
        cached-result
        (when (= current-sha cached-sha)
          (try
            {:clj-ns->label (read-cached-data (str (fs/path dir-path "clj-ns.transit")))
             :cljs-ns->label (read-cached-data (str (fs/path dir-path "cljs-ns.transit")))
             :class->label (delay (read-cached-data (str (fs/path dir-path "class.transit"))))
             :sha256 current-sha}
            (catch Throwable t
              (binding [*out* *err*]
                (println "gazelle-server.bb: cache corrupted at" dir-path
                         "(" (.getMessage t) ") - rebuilding"))
              (delete-cache-dir dir-path)
              nil)))]
    (or cached-result
        (let [result (parse-deps-build deps-build-path no-aot-set)
              class-map (:class->label result)]
          (fs/create-dirs dir-path)
          ;; Write data files first, sha sentinel last. If interrupted
          ;; mid-write, the next run sees no/stale sha and rebuilds rather
          ;; than reading partially-written transit data with a valid sha.
          (fs/write-bytes (str (fs/path dir-path "clj-ns.transit"))
                          (transit-write (:clj-ns->label result)))
          (fs/write-bytes (str (fs/path dir-path "cljs-ns.transit"))
                          (transit-write (:cljs-ns->label result)))
          (fs/write-bytes (str (fs/path dir-path "class.transit"))
                          (transit-write class-map))
          (spit sha-file current-sha)
          (assoc result :class->label (delay class-map) :sha256 current-sha)))))

;; ---------------------------------------------------------------------------
;; Path / label helpers
;; ---------------------------------------------------------------------------

(defn- ns->dep-label [deps-repo-tag ns->label ns-sym]
  (when-let [label (get ns->label ns-sym)]
    (str deps-repo-tag "//:" label)))

(defn- class->dep-label [deps-repo-tag *class->label class-sym]
  (when-let [label (get @*class->label class-sym)]
    (str deps-repo-tag "//:" label)))

(defn- clj-file?  [path]
  (let [s (str path)]
    (or (str/ends-with? s ".clj") (str/ends-with? s ".cljc") (str/ends-with? s ".cljs"))))
(defn- clj-path?  [path] (str/ends-with? (str path) ".clj"))
(defn- cljc-path? [path] (str/ends-with? (str path) ".cljc"))
(defn- cljs-path? [path] (str/ends-with? (str path) ".cljs"))
(defn- js-path?   [path] (str/ends-with? (str path) ".js"))
(defn- test-path? [path] (boolean (or (str/ends-with? (str path) "_test.clj")
                                      (str/ends-with? (str path) "_test.cljc"))))

(defn- filename [path] (str (fs/file-name path)))

(defn- basename [path]
  (str (fs/strip-ext (filename path))))

(defn- ext->platforms [ext]
  (case ext
    "clj"  #{:clj}
    "cljs" #{:cljs}
    "cljc" #{:clj :cljs}
    #{}))

(defn- file-ext [path]
  (let [ext (fs/extension path)]
    (when (seq ext) ext)))

;; ---------------------------------------------------------------------------
;; Source ns resolver (for namespaces whose Bazel label comes from the
;; repo's own src tree, not from @deps. Lazy + memoized.
;; ---------------------------------------------------------------------------

(defn- make-lazy-src-ns-resolver
  "Build a fn that resolves an ns symbol to its bazel label by probing
  source-paths × {.clj .cljc .cljs}. Results are memoized in an atom.

  Top-level namespaces (no dots) have no parent path segment. Emit
  `//src-path:leaf` rather than `//src-path/:leaf` (the latter is an
  invalid Bazel label)."
  [root source-paths]
  (let [cache (atom {})]
    (fn [ns-name]
      (if (contains? @cache ns-name)
        (get @cache ns-name)
        (let [rel (-> (str ns-name) (str/replace "." "/") (str/replace "-" "_"))
              parent (fs/parent rel)
              leaf (str (fs/file-name rel))
              result (some (fn [src-path]
                             (let [base (str (fs/path root src-path rel))]
                               (when (some (fn [ext] (fs/exists? (str base ext)))
                                           [".clj" ".cljc" ".cljs"])
                                 (if parent
                                   (str "//" src-path "/" parent ":" leaf)
                                   (str "//" src-path ":" leaf)))))
                           source-paths)]
          (swap! cache assoc ns-name result)
          result)))))

;; ---------------------------------------------------------------------------
;; Rule construction
;; ---------------------------------------------------------------------------

(defn- cljs-auto-alias
  "Mirror cljs.analyzer/aliasable-clj-ns?: rewrite clojure.X → cljs.X when
  clojure.X has no CLJS/CLJC source and cljs.X does. Returns the cljs.X
  symbol, else nil."
  [src-ns-resolver cljs-ns->label ns]
  (let [s (str ns)]
    (when (and (str/starts-with? s "clojure.")
               (not (src-ns-resolver ns))
               (not (get cljs-ns->label ns)))
      (let [alt (symbol (str "cljs." (subs s (count "clojure."))))]
        (when (or (src-ns-resolver alt)
                  (get cljs-ns->label alt))
          alt)))))

(defn- resolve-ns-deps
  "Resolve required namespaces to bazel labels via src tree first,
  then @deps. Unresolved requires (neither found) are logged to *err*
  with the source ns + platform so users see *which* require didn't
  resolve, rather than silently producing a rule with a missing dep.

  In practice most unresolved cases are typos or aliases that haven't
  been added to deps.edn yet; the warning lets the user notice
  immediately rather than chasing a confusing compile error."
  [{:keys [src-ns-resolver clj-ns->label cljs-ns->label deps-repo-tag rel-dir]} ns-decl ns-name platform]
  (let [required-nses (disj (deps-from-ns-decl ns-decl) ns-name)
        dep-ns-map (if (= platform :cljs) cljs-ns->label clj-ns->label)
        unresolved (atom #{})
        labels (->> required-nses
                    (keep (fn [dep-ns]
                            (let [lookup-ns (or (when (= platform :cljs)
                                                  (cljs-auto-alias src-ns-resolver cljs-ns->label dep-ns))
                                                dep-ns)]
                              (or (src-ns-resolver lookup-ns)
                                  (ns->dep-label deps-repo-tag dep-ns-map lookup-ns)
                                  (do (swap! unresolved conj dep-ns) nil)))))
                    vec)]
    (when (seq @unresolved)
      (binding [*out* *err*]
        (println "gazelle-server.bb: unresolved" (name platform) "requires in"
                 ns-name (str "(at " (or rel-dir "?") "):") (sort @unresolved))))
    labels))

(defn- resolve-import-deps [{:keys [class->label deps-repo-tag]} ns-decl]
  (->> (imports-from-ns-decl ns-decl)
       (keep #(class->dep-label deps-repo-tag class->label %))
       vec))

(defn- resolve-gen-class-deps [{:keys [class->label deps-repo-tag]} ns-decl]
  (when-let [cls (gen-class-extends ns-decl)]
    (when-let [label (class->dep-label deps-repo-tag class->label cls)]
      [label])))

(defn- parse-file
  "Slurp + parse the ns form once per platform the file covers. Returns
  {:path :decl-platforms} where :decl-platforms is a vector of
  [ns-decl platform] tuples (one entry per :clj/:cljs platform present
  in the file). Hits the disk once per file and the edamame parser
  once per platform (twice for .cljc); ns-rules / per-ns-requires /
  parse-group all consume this pre-parsed shape so duplicate
  slurp+parse work is avoided per request."
  [path]
  (let [source (slurp (str path))
        platforms (ext->platforms (file-ext path))]
    {:path path
     :decl-platforms
     (->> platforms
          (keep (fn [platform]
                  (when-let [decl (parse-ns-form source #{platform})]
                    [decl platform])))
          vec)}))

(defn- ns-rules
  "Keyword-shaped rule specs for one basename group. Returns
  [{:type :clojure_library :attrs {…}} …]. wire-converted at the
  response boundary. Takes pre-parsed file shapes from parse-file."
  [{:keys [global-lib-deps lib-jvm-flags test-jvm-flags] :as ctx} parsed-files paths src-path]
  (let [clj? (some clj-path? paths)
        cljc? (some cljc-path? paths)
        js? (some js-path? paths)
        ns-decl-platforms (->> parsed-files (mapcat :decl-platforms))
        ns-decls (map first ns-decl-platforms)
        ns-name (some-> ns-decls first second)
        path (first paths)
        ns-label (basename path)
        test? (test-path? path)
        all-deps (->> ns-decl-platforms
                      (mapcat (fn [[decl platform]]
                                (concat
                                 (resolve-ns-deps ctx decl ns-name platform)
                                 (resolve-import-deps ctx decl)
                                 (resolve-gen-class-deps ctx decl))))
                      distinct vec)
        ns-meta (->> ns-decls (keep get-ns-meta) (apply merge-with into))
        ns-library-meta (some-> ns-meta
                                (get :bazel/clojure_library)
                                (as-> m
                                      (cond-> m
                                        (:deps m) (update :deps (partial mapv name))
                                        (:runtime_deps m) (update :runtime_deps (partial mapv name)))))
        ns-test-meta (some-> ns-meta
                             (get :bazel/clojure_test)
                             (as-> m
                                   (cond-> m
                                     (:tags m) (update :tags (partial mapv name))
                                     (:size m) (update :size name)
                                     (:timeout m) (update :timeout name))))
        ns-binary-meta (some-> ns-meta
                               (get :bazel/clojure_binary)
                               (as-> m
                                     (cond-> m
                                       (:jvm_flags m) (update :jvm_flags (partial mapv name)))))
        aot (if (and (or clj? cljc?) (not test?) (get ns-library-meta :aot true))
              [(str ns-name)]
              [])
        ns-library-meta (some-> ns-library-meta (dissoc :aot))
        library-attrs (-> (merge-with into
                                      {:name ns-label
                                       :deps [(str (:deps-repo-tag ctx) "//:org_clojure_clojure")]
                                       :resources (mapv filename paths)
                                       :resource_strip_prefix src-path}
                                      (when (seq aot)
                                        {:srcs (mapv filename paths) :aot aot})
                                      {:deps all-deps}
                                      {:deps (vec global-lib-deps)
                                       :jvm_flags (vec lib-jvm-flags)}
                                      ns-library-meta)
                          (update :deps (comp vec dedupe sort)))
        test-attrs (merge-with into
                               {:name (str ns-label ".test")
                                :test_ns (str ns-name)
                                :deps [(str ":" ns-label)]}
                               {:jvm_flags (vec test-jvm-flags)}
                               ns-test-meta)]
    (filterv some?
             [(when (seq ns-decls)
                {:type :clojure_library :attrs library-attrs})
              (when (and (or clj? cljc?) test?)
                {:type :clojure_test :attrs test-attrs})
              (when ns-binary-meta
                {:type :clojure_binary
                 :attrs (let [binary-name (or (:name ns-binary-meta) (str ns-label ".bin"))]
                          (-> (merge {:main_class "clojure.main"
                                      :args ["-m" (str ns-name)]}
                                     (dissoc ns-binary-meta :name))
                              (assoc :name binary-name)
                              (update :jvm_flags #(into (vec lib-jvm-flags) %))
                              (update :runtime_deps (fnil conj []) (str ":" ns-label))))})
              (when js?
                {:type :java_library
                 :attrs {:name ns-label
                         :resources (->> paths (filter js-path?) (mapv filename))
                         :resource_strip_prefix src-path}})])))

(defn- ext-platforms-of [files]
  (->> files
       (filter clj-file?)
       (mapcat #(ext->platforms (file-ext %)))
       set))

(defn- per-ns-requires
  "{\"clj\" [...] \"cljs\" [...]} (sorted, deduped) collected from each
  platform's ns-decl after reader-conditional resolution. Reuses the
  pre-parsed file shapes from parse-file; no extra slurp/parse work."
  [parsed-files]
  (let [decl-platforms (mapcat :decl-platforms parsed-files)
        platforms (into #{} (map second) decl-platforms)]
    (into (sorted-map)
          (for [plat platforms
                :let [deps (->> decl-platforms
                                (filter #(= plat (second %)))
                                (mapcat (fn [[decl _]] (deps-from-ns-decl decl)))
                                (map str)
                                distinct
                                sort
                                vec)]
                :when (seq deps)]
            [(name plat) deps]))))

(defn- primary-file [paths]
  (or (some #(when (clj-path? %) %) paths)
      (some #(when (cljs-path? %) %) paths)
      (some #(when (cljc-path? %) %) paths)
      (first paths)))

(defn- parse-group
  "Build a NamespaceInfo-shaped map for one basename group.
  When every clj/cljc/cljs file in the group fails to parse (every
  parse-file produces no decl-platforms), log to *err* and return nil.
  Returning nil (rather than an empty NamespaceInfo) preserves any
  pre-existing Gazelle rules: with the Go-side language Empty implementation
  returning nil, Gazelle leaves untouched any rules it can't regenerate."
  [ctx paths src-path]
  (let [clj-files (filter clj-file? paths)
        parsed-files (mapv parse-file clj-files)
        rules (ns-rules ctx parsed-files paths src-path)
        js-only? (and (empty? clj-files) (seq paths))]
    (if js-only?
      {:file (filename (first paths))
       :platforms ["js"]
       :rules (vec rules)}
      (let [first-decl (->> parsed-files (mapcat :decl-platforms) (map first) first)
            ns-name (some-> first-decl second str)
            primary (primary-file paths)]
        (if first-decl
          {:ns ns-name
           :file (filename primary)
           :requires (per-ns-requires parsed-files)
           :platforms (vec (sort (map name (ext-platforms-of paths))))
           :rules (vec rules)}
          (do (binding [*out* *err*]
                (println "gazelle-server.bb: no parseable ns form in"
                         (mapv #(filename (str %)) clj-files)
                         "(file(s) skipped; pre-existing rules left untouched)"))
              nil))))))

(defn- rollup-rules
  [{:keys [lib-deps src-files clojure-subdir-paths]}]
  (let [subdir-lib-deps  (mapv #(str "//" % ":__clj_lib") clojure-subdir-paths)
        subdir-file-deps (mapv #(str "//" % ":__clj_files") clojure-subdir-paths)
        local-lib-deps   (mapv #(str ":" %) lib-deps)
        clj-lib-deps     (into local-lib-deps subdir-lib-deps)
        clj-files-srcs   (vec src-files)]
    (cond-> []
      (seq clj-lib-deps)
      (conj {:type :clojure_library
             :attrs {:name "__clj_lib"
                     :deps clj-lib-deps}})
      (or (seq clj-files-srcs) (seq subdir-file-deps))
      (conj {:type :filegroup
             :attrs (cond-> {:name "__clj_files"}
                      (seq clj-files-srcs) (assoc :srcs clj-files-srcs)
                      (seq subdir-file-deps) (assoc :data subdir-file-deps))}))))

;; ---------------------------------------------------------------------------
;; Server protocol
;; ---------------------------------------------------------------------------

(defn- rule-spec->wire
  "Convert internal rule-spec {:type :keyword :attrs {kw v}} to wire shape
  {:kind \"string\" :attrs {\"string\" v}}. The server uses :type for
  case dispatch; the wire uses :kind to match Go's RuleSpec.Kind."
  [{:keys [type attrs]}]
  {:kind (name type)
   :attrs (update-keys attrs name)})

(defn- find-output-base
  "Resolve Bazel's output_base for `root`. Cached on disk between runs;
  cache invalidates when the directory no longer exists OR no longer
  contains an `external/` subdirectory (the marker for an active
  output_base; would be missing if the user switched --output_base or
  the cache survived a `bazel clean --expunge`)."
  [root]
  (let [dir-path (cache-dir-for root)
        cache-file (str (fs/path dir-path "output_base"))
        cached (when (fs/exists? cache-file)
                 (let [v (str/trim (slurp cache-file))]
                   (when (and (seq v)
                              (fs/directory? v)
                              (fs/directory? (str (fs/path v "external"))))
                     v)))]
    (or cached
        (let [{:keys [out err exit]} (sh/sh "bazel" "info" "output_base" :dir root)
              result (str/trim (or out ""))]
          ;; Always surface bazel's stderr; deprecation warnings show up even
          ;; on successful runs and the user should see them.
          (when (seq err)
            (binding [*out* *err*]
              (println "gazelle-server.bb: bazel stderr:" err)))
          (when (not= 0 exit)
            (throw (ex-info (str "`bazel info output_base` exited " exit
                                 " at " root
                                 " - is bazel on PATH and the workspace valid?")
                            {:root root :exit exit :out out :err err})))
          (when (seq result)
            (fs/create-dirs dir-path)
            (spit cache-file result))
          result))))

(defn- resolve-deps-build-override
  "Resolve the GAZELLE_DEPS_BUILD override, if any. Returns the override
  path when set and valid, nil when unset. Throws when set but the path
  doesn't exist (so a typo fails loudly instead of silently falling
  through to bazel info). Takes getenv-fn for testability."
  [getenv-fn]
  (when-let [override (getenv-fn "GAZELLE_DEPS_BUILD")]
    (if (fs/exists? override)
      override
      (throw (ex-info (str "GAZELLE_DEPS_BUILD points at missing path: " override)
                      {:env-var "GAZELLE_DEPS_BUILD" :path override})))))

(defn- probe-bzlmod-deps-build
  "Probe canonical and apparent repo names under <output_base>/external for
  @deps/BUILD.bazel. Returns the first match's path or nil."
  [output-base]
  (let [ext-dir (fs/path output-base "external")
        ;; The canonical bzlmod name is <root-module>++<extension>+<repo>;
        ;; 'rules_clojure++deps+deps' matches the standard rules_clojure
        ;; deps extension. 'deps' covers the apparent repo name (also the
        ;; fallback when the user has remapped via MODULE.bazel).
        candidates ["rules_clojure++deps+deps" "deps"]]
    (some (fn [dir]
            (let [p (fs/path ext-dir dir "BUILD.bazel")]
              (when (fs/exists? p) (str p))))
          candidates)))

(defn- find-deps-build
  "Resolve @deps/BUILD.bazel. Honours GAZELLE_DEPS_BUILD (and fails loud
  if the env var is set but points at a missing path). Otherwise probes
  the canonical and apparent repo names under <output_base>/external."
  [root]
  (or (resolve-deps-build-override #(System/getenv %))
      (when-let [output-base (find-output-base root)]
        (probe-bzlmod-deps-build output-base))))

(defn- handle-init
  [{:keys [deps_edn_path deps_repo_tag aliases]}]
  (let [deps-edn-path (str (fs/absolutize deps_edn_path))
        _ (when-not (fs/exists? deps-edn-path)
            (throw (ex-info (str "deps.edn not found at " deps-edn-path)
                            {:deps-edn-path deps-edn-path})))
        root (str (fs/parent deps-edn-path))
        deps-edn (try (edn/read-string (slurp deps-edn-path))
                      (catch Exception e
                        (throw (ex-info (str "failed to parse deps.edn at " deps-edn-path)
                                        {:deps-edn-path deps-edn-path}
                                        e))))
        bazel-config (or (:bazel deps-edn) {})
        ignore-dirs (set (or (:ignore bazel-config) []))
        no-aot-set (set (get-in bazel-config [:no-aot]))
        ;; Aliases drive which :extra-paths add to the source-path set.
        ;; When the request supplies explicit aliases, use them. Otherwise
        ;; default to every alias in deps.edn (matching gen_srcs, which
        ;; is configured via `deps.install(aliases = [...])` in MODULE.bazel
        ;; with the project's full alias list). The Go plugin can't read
        ;; MODULE.bazel's aliases for us cheaply, so we approximate by
        ;; merging extra-paths from every alias; the `:bazel :ignore` set
        ;; below filters out any source roots the user doesn't want bazel
        ;; to walk.
        alias-kws (if (seq aliases)
                    (mapv (fn [a]
                            (keyword (cond-> a (str/starts-with? a ":") (subs 1))))
                          aliases)
                    (vec (keys (:aliases deps-edn))))
        extra-paths (->> alias-kws
                         (mapcat #(get-in deps-edn [:aliases % :extra-paths]))
                         (filter some?))
        base-paths (:paths deps-edn)
        source-paths (->> (concat base-paths extra-paths)
                          distinct
                          (remove ignore-dirs)
                          vec)
        deps-build-path (or (find-deps-build root)
                            (throw (ex-info
                                    (str "@deps/BUILD.bazel not found under "
                                         (find-output-base root)
                                         "/external. Run `bazel build @deps//:__all` first.")
                                    {:root root})))
        cache (load-or-build-cache root deps-build-path no-aot-set)
        src-ns-resolver (make-lazy-src-ns-resolver root source-paths)
        state {:root root
               :deps-edn-path deps-edn-path
               :deps-bazel bazel-config
               :deps-repo-tag deps_repo_tag
               :source-paths source-paths
               :ignore-paths (vec ignore-dirs)
               :clj-ns->label (:clj-ns->label cache)
               :cljs-ns->label (:cljs-ns->label cache)
               :class->label (:class->label cache)
               :src-ns-resolver src-ns-resolver
               :global-lib-deps (vec (get-in bazel-config [:clojure_library :deps] []))
               :lib-jvm-flags (vec (get-in bazel-config [:clojure_library :jvm_flags] []))
               :test-jvm-flags (vec (get-in bazel-config [:clojure_test :jvm_flags] []))}
        dep-ns-labels {"clj"  (into (sorted-map) (:clj-ns->label cache))
                       "cljs" (into (sorted-map) (:cljs-ns->label cache))}]
    {:response {:type "init"
                :dep_ns_labels dep-ns-labels
                :deps_bazel (dissoc bazel-config :no-aot :ignore)
                :ignore_paths (vec ignore-dirs)
                :source_paths source-paths}
     :state state}))

(defn- handle-parse
  [state {:keys [dir files clojure_subdir_paths]}]
  (let [root (:root state)
        abs-dir (let [d (fs/path dir)]
                  (if (fs/absolute? d) (str d) (str (fs/path root dir))))
        rel-dir (str (.relativize (fs/path root) (fs/path abs-dir)))
        ;; Pick the LONGEST matching source-path. If both `src` and `src/cljs`
        ;; are configured, a file under src/cljs should be reported as belonging
        ;; to `src/cljs`, not `src` (whichever happens to appear first in
        ;; :source-paths order).
        src-path (->> (:source-paths state)
                      (filter (fn [sp]
                                (or (= rel-dir sp)
                                    (str/starts-with? rel-dir (str sp "/")))))
                      (sort-by (comp - count))
                      first)
        _ (when (nil? src-path)
            (throw (ex-info
                    (str "parse request for directory not under any configured source-path; "
                         "Go-side pathUnder should have filtered this. Add the directory to "
                         "deps.edn :paths/:extra-paths or to :bazel :ignore.")
                    {:rel-dir rel-dir
                     :source-paths (:source-paths state)})))
        relevant (->> files
                      (filter (fn [f] (or (clj-file? f) (js-path? f))))
                      sort)
        groups (->> relevant
                    (map (fn [f] [(basename f) (str (fs/path abs-dir f))]))
                    (group-by first)
                    (into (sorted-map))
                    vals
                    (map (fn [pairs] (mapv second pairs))))
        ;; pmap across groups so file slurp+parse for sibling basename
        ;; groups runs in parallel. Each group itself is small; cross-group
        ;; parallelism is where the win lives.
        group-ctx (assoc state :rel-dir rel-dir)
        parsed (->> groups
                    (pmap (fn [paths] (parse-group group-ctx paths src-path)))
                    (keep identity)
                    doall)
        rollup-kinds #{:clojure_library :java_library}
        lib-names (into [] (comp (mapcat :rules)
                                 (filter (comp rollup-kinds :type))
                                 (map (comp :name :attrs))
                                 (distinct))
                        parsed)
        src-files (into [] (comp (mapcat :rules)
                                 (filter (comp rollup-kinds :type))
                                 (mapcat (comp :resources :attrs))
                                 (distinct))
                        parsed)
        rollup-specs (rollup-rules
                      {:lib-deps lib-names
                       :src-files src-files
                       :clojure-subdir-paths (or clojure_subdir_paths [])})]
    {:type "parse"
     :namespaces (mapv (fn [ns-info]
                         (update ns-info :rules (partial mapv rule-spec->wire)))
                       parsed)
     :rollup_rules (mapv rule-spec->wire rollup-specs)}))

(defn- handle-request [!state request]
  (case (:type request)
    "init"  (let [{:keys [response state]} (handle-init request)]
              (reset! !state state)
              response)
    "parse" (if (nil? @!state)
              {:type "error" :message "parse received before init"}
              (handle-parse @!state request))
    {:type "error" :message (str "unknown request type: " (:type request))}))

(defn- exception-chain
  "Render a Throwable + every cause as `class: msg -> class: msg ...`.
  Strips an outer java.util.concurrent.ExecutionException (pmap wraps
  worker failures in one and the inner cause is the actionable error)."
  [^Throwable t]
  (let [t (if (instance? java.util.concurrent.ExecutionException t)
            (or (.getCause t) t)
            t)]
    (->> (iterate #(.getCause ^Throwable %) t)
         (take-while some?)
         (mapv (fn [^Throwable x]
                 (let [klass (.getName (class x))
                       msg (.getMessage x)]
                   (if msg (str klass ": " msg) klass))))
         (str/join " -> "))))

(defn- read-request [^BufferedReader reader]
  (when-let [line (.readLine reader)]
    (try
      (json/parse-string line true)
      (catch Exception e
        ;; Malformed JSON: return a sentinel so the loop can respond
        ;; with an error envelope and continue, rather than dying.
        {:_malformed true :_message (.getMessage e)}))))

(defn- write-response [^BufferedWriter writer resp]
  (.write writer (json/generate-string resp))
  (.newLine writer)
  (.flush writer))

;; Errors that signal JVM-wide failure (OOM, stack overflow, ThreadDeath,
;; LinkageError-class). Swallowing them in a catch would leave the JVM in
;; an unrecoverable state and let the next request misbehave silently.
(defn- fatal-error? [^Throwable t]
  (or (instance? VirtualMachineError t)
      (instance? ThreadDeath t)
      (instance? LinkageError t)))

(defn -main [& _args]
  (let [reader (BufferedReader. (InputStreamReader. System/in))
        writer (BufferedWriter. (OutputStreamWriter. System/out))
        !state (atom nil)]
    (loop []
      (let [request (try (read-request reader)
                         (catch Throwable t
                           (when (fatal-error? t) (throw t))
                           (binding [*out* *err*]
                             (println "gazelle-server.bb: stdin read failed:"
                                      (.getMessage t)))
                           ::eof))]
        (when (and (some? request) (not= ::eof request))
          (let [response
                (cond
                  (:_malformed request)
                  {:type "error"
                   :message (str "malformed JSON request: " (:_message request))}
                  :else
                  (try (handle-request !state request)
                       (catch Throwable t
                         (when (fatal-error? t) (throw t))
                         (binding [*out* *err*]
                           (println "gazelle-server.bb: handle-request failed for type="
                                    (:type request))
                           (.printStackTrace t))
                         {:type "error" :message (exception-chain t)})))]
            (write-response writer response)
            (recur)))))))

;; Only auto-invoke -main when this file is run directly via `bb script.bb`
;; (so test files that `load-file` this script can exercise its defs without
;; the server loop blocking on stdin).
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
