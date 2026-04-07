(ns generator
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.nio.file Files Path FileVisitResult SimpleFileVisitor]
   [java.nio.file.attribute BasicFileAttributes]))

(set! *warn-on-reflection* true)

;; These settings generate 19,607 namespaces total (7 + 49 + 343 + 2,401 + 16,807).
(def width 7)
(def depth 5)

(defn- alphabet-chars []
  (map char (range (int \a) (inc (int \z)))))

(defn- generate-names-at-depth [width depth parents]
  (let [chars (take width (alphabet-chars))]
    (if (= depth 1)
      (map str chars)
      (for [parent parents c chars] (str parent "." c)))))

(defn namespace-levels [width depth]
  (let [chars (vec (map str (take width (alphabet-chars))))]
    (->> (iterate (fn [parents]
                    (for [parent parents
                          c chars]
                      (str parent "." c)))
                  chars)
         (take depth))))

(defn count-namespaces [width depth]
  (reduce + (take depth (iterate #(* % width) width))))

(defn- children-of [ns-name width]
  (let [chars (take width (alphabet-chars))]
    (map #(str ns-name "." %) chars)))

(defn- generate-ns-form-str [ns-name depth-level max-depth width]
  (if (< depth-level max-depth)
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

(defn- generate-source [ns-name depth-level max-depth width]
  (str (generate-ns-form-str ns-name depth-level max-depth width)
       "\n\n"
       (generate-functions-str ns-name 100)
       "\n"))

(defn- ns->file-path [ns-name]
  (str (str/replace ns-name "." "/") ".clj"))

(defn- delete-tree! [^Path root]
  (when (Files/exists root (make-array java.nio.file.LinkOption 0))
    (Files/walkFileTree
     root
     (proxy [SimpleFileVisitor] []
       (visitFile [^Path file ^BasicFileAttributes _attrs]
         (Files/delete file)
         FileVisitResult/CONTINUE)
       (postVisitDirectory [^Path dir _exc]
         (Files/delete dir)
         FileVisitResult/CONTINUE)))))

(defn reset-src! [^java.io.File src-dir]
  (delete-tree! (.toPath src-dir))
  (.mkdirs src-dir))

(defn write-project! [out-dir width depth]
  (let [src-dir (io/file out-dir "src")]
    (reset-src! src-dir)
    (doseq [[idx namespaces-at-depth] (map-indexed vector (namespace-levels width depth))
            :let [depth-level (inc idx)]
            ns-name namespaces-at-depth]
      (let [file-path (io/file src-dir (ns->file-path ns-name))
            parent (.getParentFile file-path)]
        (when parent (.mkdirs parent))
        (spit file-path (generate-source ns-name depth-level depth width))))))

(defn -main [& _args]
  (let [ws-root (or (System/getenv "BUILD_WORKSPACE_DIRECTORY") ".")
        out-dir ws-root]
    (println "Generating benchmark src tree...")
    (println "  width:" width)
    (println "  depth:" depth)
    (println "  output:" out-dir)

    (let [namespace-count (count-namespaces width depth)
          start (System/nanoTime)]
      (println "  namespaces:" namespace-count)
      (write-project! out-dir width depth)
      (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
        (println)
        (println "Generated" namespace-count "namespaces in" (format "%.0f" elapsed-ms) "ms")
        (println "Output directory:" out-dir)
        (println)))))
