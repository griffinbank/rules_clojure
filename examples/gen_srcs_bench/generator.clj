(ns generator
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def default-width 7)
(def default-depth 5)

(defn- alphabet-chars []
  (map char (range (int \a) (inc (int \z)))))

(defn- generate-names-at-depth [width depth parents]
  (let [chars (take width (alphabet-chars))]
    (if (= depth 1)
      (map str chars)
      (for [parent parents c chars] (str parent "." c)))))

(defn generate-namespaces [width depth]
  (reduce (fn [names d]
            (concat names (generate-names-at-depth width d (take-last (int (Math/pow width (dec d))) names))))
          []
          (range 1 (inc depth))))

(defn has-children? [ns-name max-depth]
  (< (count (str/split ns-name #"\\.")) max-depth))

(defn- children-of [ns-name width]
  (let [chars (take width (alphabet-chars))]
    (map #(str ns-name "." %) chars)))

(defn- generate-ns-form-str [ns-name max-depth width]
  (if (has-children? ns-name max-depth)
    (let [children (children-of ns-name width)
          requires (str/join "\n   "
                             (for [child children]
                               (str "[" child " :as " (last (str/split child #"\\.")) "]")))]
      (str "(ns " ns-name "\n  (:require\n   " requires "))"))
    (str "(ns " ns-name ")")))

(defn- generate-functions-str [ns-name num-functions]
  (str/join "\n\n"
            (for [i (range 1 (inc num-functions))]
              (str "(defn fn-" i "\n  \"Generated function " i " for " ns-name "\"\n  [x]\n  (assoc x :" ns-name " " i "))"))))

(defn- generate-source [ns-name max-depth width]
  (str (generate-ns-form-str ns-name max-depth width)
       "\n\n"
       (generate-functions-str ns-name 100)
       "\n"))

(defn- ns->file-path [ns-name]
  (str (str/replace ns-name "." "/") ".clj"))

(defn- delete-generated-build-files! [^java.io.File src-dir]
  (when (.exists src-dir)
    (doseq [^java.io.File f (file-seq src-dir)
            :when (and (.isFile f)
                       (= "BUILD.bazel" (.getName f)))]
      (io/delete-file f))))

(defn reset-src! [^java.io.File src-dir]
  (when (.exists src-dir)
    (doseq [f (reverse (file-seq src-dir))]
      (io/delete-file f)))
  (.mkdirs src-dir))

(defn write-project! [namespaces out-dir]
  (let [src-dir (io/file out-dir "src")]
    (reset-src! src-dir)
    (doseq [ns-name namespaces]
      (let [file-path (io/file src-dir (ns->file-path ns-name))
            parent (.getParentFile file-path)]
        (when parent (.mkdirs parent))
        (spit file-path (generate-source ns-name default-depth default-width))))
    (delete-generated-build-files! src-dir)))

(defn -main [& _args]
  (let [ws-root (or (System/getenv "BUILD_WORKSPACE_DIRECTORY") ".")
        out-dir ws-root]
    (println "Generating benchmark src tree...")
    (println "  width:" default-width)
    (println "  depth:" default-depth)
    (println "  output:" out-dir)

    (let [start (System/nanoTime)
          namespaces (generate-namespaces default-width default-depth)
          gen-elapsed (/ (- (System/nanoTime) start) 1e6)]
      (println "  namespaces:" (count namespaces))
      (let [start (System/nanoTime)]
        (write-project! namespaces out-dir)
        (let [write-elapsed (/ (- (System/nanoTime) start) 1e6)
              total-elapsed (+ gen-elapsed write-elapsed)]
          (println)
          (println "Generated" (count namespaces) "namespaces in" (format "%.0f" total-elapsed) "ms")
          (println "Output directory:" out-dir)
          (println))))))
