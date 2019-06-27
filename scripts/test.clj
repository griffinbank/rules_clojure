(require '[clojure.test :as test])

(load-file "scripts/ns.clj")

(def sources (map io/file *command-line-args*))

(doseq [source sources]
  (load-file (.getCanonicalPath source)))

(let [{:keys [fail error]} (apply test/run-tests (map ns-symbol sources))]
  (if-not (= 0 fail error) (System/exit 1)))
