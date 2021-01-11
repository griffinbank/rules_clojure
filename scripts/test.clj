(require '[clojure.test :as test])
(require '[clojure.java.io :as io])
(import (java.io PushbackReader))

(defn ns-symbol [file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (loop [form (read reader false ::done)]
      (if (and (list? form) (= 'ns (first form)))
        (second form)
        (when-not (= ::done form) (recur reader))))))

(defn ns-path [file]
  (-> file ns-symbol name (.replace \- \_) (.replace \. \/) (str ".clj")))

(def test-ns (-> *command-line-args* first symbol))

(println "requiring" test-ns)
(require test-ns)

(let [{:keys [fail error]} (apply test/run-tests (map ns-symbol sources))]
  (if-not (= 0 fail error) (System/exit 1)))
