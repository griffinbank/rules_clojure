(ns rules-clojure.compile
  (:require [clojure.string]))

(defn deftype? [ns v]
  (and (class? v)
       (-> v
           (.getName)
           (clojure.string/starts-with? (munge (name ns))))
       (= (count (clojure.string/split (.getName v) #"\."))
          (inc (count (clojure.string/split (name ns) #"\."))))))

(defn protocol? [val]
  (and (map? val)
       (class? (:on-interface val))
       (map? (:sigs val))
       (map? (:method-map val))))

(defn contains-protocols? [ns]
         (->> ns
              ns-interns
              vals
              (some protocol?)))

(defn contains-deftypes? [ns]
  (->> ns
       ns-map
       vals
       (some (fn [v]
               (deftype? ns v)))))

(defn non-transitive-compile [dep-nses compile-ns]
  {:pre [(every? symbol? dep-nses)
         (symbol? compile-ns)
         (not (contains? (set dep-nses) compile-ns))]}

  (when (seq dep-nses)
    (apply require dep-nses))
  (compile compile-ns)
  (when (or (contains-protocols? compile-ns)
            (contains-deftypes? compile-ns))
    (str :rules-clojure.compile/reload)))
