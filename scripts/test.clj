(require '[clojure.java.io :as io])
(require '[clojure.test :as test])
(import (java.io PushbackReader))

(def sources (map io/file *command-line-args*))

(defn namespace-of [file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (loop [form (read reader false ::done)]
      (if (and (list? form) (= 'ns (first form)))
        form
        (when-not (= ::done form) (recur reader))))))

(doseq [source sources]
  (load-file (.getCanonicalPath source)))

(def nses (map #(-> % namespace-of second str symbol) sources))

(let [{:keys [fail error]} (apply test/run-tests nses)]
  (if-not (= 0 fail error) (System/exit 1)))

