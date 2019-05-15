(require '[clojure.java.io :as io])
(import (java.io File BufferedReader PushbackReader InputStreamReader))
; *read-eval* -Dclojure.read.eval=false

(def aot (filter (fn [s] (not (empty? s))) (-> (or (System/getProperty "clojure.compile.aot")) (.split ",") seq)))

(def sources (map io/file *command-line-args*))

(defn n [file]
      (with-open [reader (PushbackReader. (io/reader file))]
                 (loop [form (read reader false ::done)]
                       (if (and (list? form) (= 'ns (first form)))
                         form
                         (when-not (= ::done form) (recur reader))))))

(defn path [namespace]
      (-> namespace second str (.replace \- \_) (.replace \. \/)))

(defn target [file]
      (io/file *compile-path* (str (-> file n path) ".clj")))

(doseq [source sources]
       (io/copy source (target source)))

(doseq [n aot]
       (-> n symbol compile))
