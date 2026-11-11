(ns rules-clojure.deadlock-test
  "A namespace that loads another from its body while holding a global lock
  must not deadlock the parallel compile.

  This is the shape of dtype-next's `export-symbols`: the init of each exported
  var runs `(.lock load-lib-lock) (load-lib nil 'src-ns) (.unlock ...)`. Under
  rules-clojure, `load-lib` waits on another thread's compile of `src-ns` while
  the lock is held.

  The fixtures are in test/rules_clojure/deadlock_fixtures:

    importer  `(export-symbols src answer)`, loads src under the lock
    src       requires waiter, so loading it waits for waiter's compile
    waiter    asks for the lock once importer holds it
    top       requires importer

  importer holds the lock and waits for src, src waits for waiter, and waiter
  waits for the lock. top and waiter are compiled at the same time.

  The importer must already be AOT-compiled. Compiling it from source expands
  `export-symbols`, which loads src outside the lock and avoids the problem.
  An already-compiled importer skips macroexpansion, so loading it takes the
  lock first. Each step runs in its own JVM, as a script: compile! needs the
  DynamicClassLoader that clojure.main sets up for scripts."
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [rules-clojure.test-utils :as test-utils])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def fixture-prefix "rules-clojure.deadlock-fixtures.")

(defn java-bin []
  (str (System/getProperty "java.home") "/bin/"))

(defn run-java
  "Run `code` as a script with `java -cp classpath clojure.main`. Returns {:exit :out}, with
  :exit ::timeout and a thread dump in :out if it doesn't finish in
  `timeout-ms`."
  [classpath code timeout-ms]
  (let [script (doto (File/createTempFile "deadlock-test" ".clj")
                 (.deleteOnExit)
                 (spit code))
        pb (doto (ProcessBuilder. ^java.util.List [(str (java-bin) "java")
                                                   "-cp" classpath
                                                   "clojure.main" (str script)])
             (.redirectErrorStream true))
        proc (.start pb)
        out (future (slurp (.getInputStream proc)))]
    (if (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)
      {:exit (.exitValue proc) :out @out}
      (let [dump (try
                   (let [p (.start (doto (ProcessBuilder. ^java.util.List [(str (java-bin) "jcmd")
                                                                           (str (.pid proc))
                                                                           "Thread.print"])
                                     (.redirectErrorStream true)))]
                     (slurp (.getInputStream p)))
                   (catch Exception e (str "no thread dump: " e)))]
        (.destroyForcibly proc)
        {:exit ::timeout :out (str @out dump)}))))

(defn temp-dir ^File []
  (.toFile (Files/createTempDirectory "rules-clojure-deadlock" (make-array FileAttribute 0))))

(defn aot-compile-importer!
  "AOT-compile the importer into `dir`, keeping only its class files, so the
  other fixtures are loaded from source."
  [classpath dir]
  (let [resp (run-java classpath
                       (str "(binding [*compile-path* " (pr-str (str dir)) "]"
                            " (compile '" fixture-prefix "importer))")
                       60000)]
    (is (zero? (:exit resp)) (:out resp))
    (doseq [^File f (file-seq dir)
            :when (.isFile f)]
      (if (str/starts-with? (.getName f) "importer")
        ;; Clojure loads a class only if it is newer than the source. Bazel
        ;; dates the source in the fixtures jar 2038.
        (.setLastModified f (.toEpochMilli (java.time.Instant/parse "2040-01-01T00:00:00Z")))
        (.delete f)))))

(deftest export-symbols-style-load-does-not-deadlock
  (let [jars (map str (test-utils/runfiles-jars "TEST_JARS"))
        aot-dir (temp-dir)
        classpath (str/join File/pathSeparator (cons (str aot-dir) jars))]
    (aot-compile-importer! (str/join File/pathSeparator jars) aot-dir)
    (is (some #(= "importer__init.class" (.getName ^File %)) (file-seq aot-dir))
        "importer was compiled")
    (let [resp (run-java classpath
                         (str "(require 'rules-clojure.compile)"
                              "(rules-clojure.compile/compile! "
                              (pr-str (str (temp-dir)))
                              " [" (pr-str (str fixture-prefix "top")) " "
                              (pr-str (str fixture-prefix "waiter")) "] *out*)"
                              "(shutdown-agents)"
                              "(System/exit 0)")
                         60000)]
      (is (not= ::timeout (:exit resp))
          (str "compile did not finish: importer holds the lock and waits for src, "
               "src waits for waiter, waiter waits for the lock\n" (:out resp)))
      (is (= 0 (:exit resp)) (:out resp)))))
