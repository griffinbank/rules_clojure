(ns rules-clojure.parse)

;; fork of tools.namespace functionality to handle CLJS, based on
;; https://github.com/babashka/tools.namespace. Not using that
;; directly because it doesn't expose maven-accessible jars

(def ^:private ns-clause-head-names
  "Set of symbol/keyword names which can appear as the head of a
  clause in the ns form."
  #{"use" "require" "require-macros"})

(def ^:private ns-clause-heads
  "Set of all symbols and keywords which can appear at the head of a
  dependency clause in the ns form."
  (set (mapcat (fn [name] (list (keyword name)
                                (symbol name)))
               ns-clause-head-names)))

(defn ns-decl?
  "Returns true if form is a (ns ...) declaration."
  [form]
  (and (list? form) (= 'ns (first form))))

(def clj-read-opts
  "Map of options for tools.reader/read allowing reader conditionals
  with the :clj feature enabled."
  {:read-cond :allow
   :features #{:clj}})

(def cljs-read-opts
  "Map of options for tools.reader/read allowing reader conditionals
  with the :cljs feature enabled."
  {:read-cond :allow
   :features #{:cljs}})

(defn read-ns-decl
  "Same as c.t.n.s.parse/read-ns-decl, but takes the ns-decl form"
  [ns-decl read-opts]
  (let [form (read-string read-opts (str ns-decl))]
    (when (ns-decl? form)
      form)))

(defn- prefix-spec?
  "Returns true if form represents a libspec prefix list like
  (prefix name1 name1) or [com.example.prefix [name1 :as name1]]"
  [form]
  (and (sequential? form)  ; should be a list, but often is not
       (symbol? (first form))
       (not-any? keyword? form)
       (< 1 (count form))))

(defn- option-spec?
  "Returns true if form represents a libspec vector containing optional
  keyword arguments like [namespace :as alias] or
  [namespace :refer (x y)] or just [namespace]"
  [form]
  (and (sequential? form)  ; should be a vector, but often is not
       (or (symbol? (first form))
           (string? (first form)))
       (or (keyword? (second form))  ; vector like [foo :as f]
           (= 1 (count form)))))

(defn- deps-from-libspec [prefix form]
  (cond (prefix-spec? form) (mapcat (fn [f] (deps-from-libspec
                                             (symbol (str (when prefix (str prefix "."))
                                                          (first form)))
                                             f))
                                    (rest form))
	(option-spec? form) (when-not (= :as-alias (second form))
                              (deps-from-libspec prefix (first form)))
	(symbol? form) (list (symbol (str (when prefix (str prefix ".")) form)))
	(keyword? form) nil  ;; Some people write (:require ... :reload-all)
        (string? form) nil ;;npm  require
	:else (throw (ex-info "Unparsable namespace form"
                              {:reason ::unparsable-ns-form
                               :form form}))))

(defn- deps-from-ns-form [form]
  (when (and (sequential? form)  ; should be list but sometimes is not
	     (contains? ns-clause-heads (first form)))
    (mapcat #(deps-from-libspec nil %) (rest form))))

(defn deps-from-ns-decl
  "Given an (ns...) declaration form (unevaluated), returns a set of
  symbols naming the dependencies of that namespace.  Handles :use and
  :require clauses but not :load."
  [decl]
  (set (mapcat deps-from-ns-form decl)))

(defn name-from-ns-decl
  "Given an (ns...) declaration form (unevaluated), returns the name
  of the namespace as a symbol."
  [decl]
  (second decl))
