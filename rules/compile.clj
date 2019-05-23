(require '[clojure.java.io :as io])
(import (java.io File BufferedReader PushbackReader InputStreamReader))

(def aot (filter (fn [s] (not (empty? s))) (-> (or (System/getProperty "clojure.compile.aot")) (.split ",") seq)))

(def sources (map io/file *command-line-args*))

(defn namespace-of [file]
      (with-open [reader (PushbackReader. (io/reader file))]
                 (loop [form (read reader false ::done)]
                       (if (and (list? form) (= 'ns (first form)))
                         form
                         (when-not (= ::done form) (recur reader))))))

(defn path [namespace]
      (-> namespace second str (.replace \- \_) (.replace \. \/)))

(defn target [file]
      (io/file *compile-path* (str (-> file namespace-of path) ".clj")))

(doseq [source sources]
       (let [target (target source)]
            (io/make-parents target)
            (io/copy source target)))

(doseq [namespace aot]
       (-> namespace symbol compile))
