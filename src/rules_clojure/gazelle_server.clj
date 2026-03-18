(ns rules-clojure.gazelle-server
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [rules-clojure.fs :as fs]
            [rules-clojure.gen-build :as gen-build]
            [rules-clojure.namespace.parse :as parse])
  (:import (java.io BufferedReader BufferedWriter))
  (:gen-class))

(set! *warn-on-reflection* true)

(defn read-request
  "Read a JSON line from reader, return parsed map with keyword keys or nil on EOF."
  [^BufferedReader reader]
  (when-let [line (.readLine reader)]
    (json/read-str line :key-fn keyword)))

(defn write-response
  "Write a map as a single JSON line to writer, then flush."
  [^BufferedWriter writer resp]
  (.write writer (json/write-str resp))
  (.newLine writer)
  (.flush writer))

(defn- stringify-keys
  "Convert map keys to strings (one level deep)."
  [m]
  (into {} (map (fn [[k v]] [(name k) v])) m))

(defn- dep-ns->label->wire
  "Convert dep-ns->label from {keyword {symbol string}} to {string {string string}}
   for JSON serialization."
  [dep-ns->label]
  (into {}
        (map (fn [[platform ns-map]]
               [(name platform)
                (into {} (map (fn [[ns-sym label]]
                                [(str ns-sym) label]))
                      ns-map)]))
        dep-ns->label))

(defn handle-init
  "Process an init request. Returns a map with the wire response and internal
   state under :_state (stripped before serialization)."
  [{:keys [deps_edn_path repository_dir deps_repo_tag aliases]}]
  (let [deps-edn-path (-> deps_edn_path fs/->path fs/absolute)
        deps-edn-dir  (fs/dirname deps-edn-path)
        repository-dir (fs/->path repository_dir)
        aliases        (mapv keyword aliases)
        read-deps      (gen-build/read-deps deps-edn-path)
        deps-bazel     (gen-build/parse-deps-bazel read-deps)
        basis          (gen-build/make-basis {:read-deps      read-deps
                                             :aliases        aliases
                                             :repository-dir repository-dir
                                             :deps-edn-path deps-edn-path})
        dep-ns->label  (gen-build/->dep-ns->label {:basis         basis
                                                   :deps-bazel    deps-bazel
                                                   :deps-repo-tag deps_repo_tag})
        jar->lib       (gen-build/->jar->lib basis)
        class->jar     (gen-build/->class->jar basis)
        ignore-paths   (get-in deps-bazel [:ignore] [])
        source-paths   (mapv str (:paths basis))
        deps-bazel-wire (-> deps-bazel
                            (dissoc :no-aot :ignore)
                            stringify-keys)]
    {:type           "init"
     :dep_ns_labels  (dep-ns->label->wire dep-ns->label)
     :deps_bazel     deps-bazel-wire
     :ignore_paths   (vec ignore-paths)
     :source_paths   source-paths
     :_state         {:basis      basis
                      :deps-bazel deps-bazel
                      :jar->lib   jar->lib
                      :class->jar class->jar
                      :dep-ns->label dep-ns->label
                      :deps-edn-path deps-edn-path
                      :deps-edn-dir  deps-edn-dir
                      :deps-repo-tag deps_repo_tag
                      :aliases    aliases}}))

(defn- ext->platforms
  "Map a file extension to the set of platforms it supports."
  [ext]
  (case ext
    "clj"  #{:clj}
    "cljs" #{:cljs}
    "cljc" #{:clj :cljs}
    #{}))

(defn- file-ext
  "Return the file extension (without dot) or nil."
  [filename]
  (let [s (str filename)]
    (when-let [i (clojure.string/last-index-of s ".")]
      (subs s (inc i)))))

(defn parse-namespace-group
  "Parse a group of files sharing a basename within dir. Returns a vector
   containing one map with namespace info, or empty vector on parse failure."
  [state ^String dir files]
  (try
    (let [;; Resolve dir to absolute path using workspace root (deps-edn-dir)
          abs-dir (let [d (fs/->path dir)]
                    (if (fs/absolute? d) d (fs/->path (:deps-edn-dir state) dir)))
          platforms (apply set/union (map #(ext->platforms (file-ext %)) files))
          primary-platform (if (contains? platforms :clj) :clj :cljs)
          primary-file (first (filter #(contains? (ext->platforms (file-ext %))
                                                  primary-platform)
                                      files))
          ;; Cache ns-decls to avoid re-reading files: [path platform] → decl
          decl-cache (atom {})
          get-decl (fn [path plat]
                     (let [k [path plat]]
                       (if-let [cached (get @decl-cache k)]
                         cached
                         (let [d (gen-build/get-ns-decl path plat)]
                           (swap! decl-cache assoc k d)
                           d))))
          primary-path (fs/->path abs-dir primary-file)
          ns-decl (get-decl primary-path primary-platform)]
      (if (nil? ns-decl)
        []
        (let [ns-name (str (second ns-decl))
              requires (into {}
                            (for [plat (sort-by name platforms)
                                  :let [f (first (filter #(contains?
                                                            (ext->platforms (file-ext %))
                                                            plat)
                                                         files))
                                        p (fs/->path abs-dir f)
                                        decl (get-decl p plat)
                                        deps (when decl
                                               (mapv str (parse/deps-from-ns-decl decl)))]
                                  :when (seq deps)]
                              [(name plat) (vec (sort deps))]))
              import-args (select-keys state [:deps-repo-tag :class->jar :jar->lib])
              clj-file (first (filter #(contains? (ext->platforms (file-ext %)) :clj) files))
              clj-decl (when (and (contains? platforms :clj) clj-file)
                         (get-decl (fs/->path abs-dir clj-file) :clj))
              import-deps (when clj-decl
                            (:deps (gen-build/ns-import-deps import-args clj-decl)))
              gen-class-deps (when clj-decl
                               (:deps (gen-build/ns-gen-class-deps import-args clj-decl)))
              ns-meta (gen-build/get-ns-meta ns-decl)]
          [{:ns        ns-name
            :file      (str primary-file)
            :requires  requires
            :import_deps    (vec (or import-deps []))
            :gen_class_deps (vec (or gen-class-deps []))
            ;; Preserve namespaced keyword keys like :bazel/clojure_library
            ;; (name strips the namespace, str preserves it as "bazel/clojure_library")
            :ns_meta   (into {} (map (fn [[k v]] [(subs (str k) 1) v]) (or ns-meta {})))
            :platforms (vec (sort (map name platforms)))}])))
    (catch Throwable t
      (binding [*out* *err*]
        (println "gazelle-server: error parsing" dir (mapv str files) (.getMessage t)))
      [])))

(defn handle-parse
  "Process a parse request. Returns a map with :type and :namespaces."
  [state {:keys [dir files]}]
  (let [clj-files (filterv #(gen-build/clj*-path? %) files)
        js-files  (filterv #(gen-build/js-path? %) files)
        ;; Group clj* files by basename
        groups (vals (group-by #(fs/basename (fs/->path %)) clj-files))
        ;; Parse each group
        parsed (into [] (mapcat #(parse-namespace-group state dir %)) groups)
        ;; Minimal entries for JS files
        js-entries (mapv (fn [f] {:file f :platforms ["js"]}) js-files)]
    {:type "parse"
     :namespaces (into parsed js-entries)}))

(defn -main [& _args]
  (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. System/in))
        writer (java.io.BufferedWriter. (java.io.OutputStreamWriter. System/out))
        state (atom nil)]
    (loop []
      (when-let [request (read-request reader)]
        (let [response (case (:type request)
                         "init" (let [result (handle-init request)]
                                  (reset! state (:_state result))
                                  (dissoc result :_state))
                         "parse" (handle-parse @state request)
                         {:type "error" :message (str "unknown request type: " (:type request))})]
          (write-response writer response)
          (recur))))))
