(ns rules-clojure.worker-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.deps.alpha :as deps]
            [clojure.test :refer :all]
            [rules-clojure.worker :as worker]
            [rules-clojure.fs :as fs]))


(deftest starts
  (worker/-main))

(def basic-req {:classes-dir "classes"
                :output-jar "test.jar"
                :srcs ["rules-clojure/worker.jar"]
                :src-dir ""
                :classpath (map str (clojure.java.classpath/classpath))
                :aot-nses ["rules-clojure.worker"]})


(defn with-temp-output [req]
  (assoc req :output-jar (str (fs/new-temp-file "/tmp" "rules-clojure-test" ".jar"))))

(deftest ephemeral
  (let [arg-path (fs/new-temp-file "/tmp" "args" ".txt")
        req (-> basic-req with-temp-output)
        output-jar-path (-> req :output-jar fs/->path)]
    #p output-jar-path
    (is (not (fs/exists? output-jar-path)))
    (spit (-> arg-path fs/path->file) (json/write-str req))
    (is (= req (-> arg-path fs/path->file slurp (json/read-str :key-fn keyword))))
    (worker/-main (str "@" arg-path))
    (is (fs/exists? output-jar-path))))

(defn old-clojure-req []
  (let [old-coords '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                            org.projectodd.shimdandy/shimdandy-api {:mvn/version "1.2.1"},
                            org.projectodd.shimdandy/shimdandy-impl {:mvn/version "1.2.1"}}}
        basis (deps/create-basis {:project old-coords :user nil})]
    #p basis
    (assoc basic-req :classpath (->> basis :classpath-roots))))

(deftest classloader-isolation
  (let [worker-version *clojure-version*
        req (old-clojure-req)]
    (is (= 11 (:minor worker-version)))
    (worker/discard-shim-env)
    (worker/ensure-compile-runtime (:classpath req))
    (is (= 10 (worker/shim-invoke "clojure.core/eval" (worker/shim-invoke "clojure.core/read-string" "(get *clojure-version* :minor)"))))))

(defn bootstrap-compile-req []
  (let [basis (deps/create-basis {:user nil})]
    {:srcs ["rules-clojure/fs.clj" "rules-clojure/worker.clj" "rules-clojure/parse.clj"]
     :classes-dir (str (fs/new-temp-dir "classes"))
     :classpath (->> basis :classpath-roots)
     :resources ["rules_clojure/compile.clj"]
     :src-dir "src"
     :aot-nses ["rules-clojure.worker"]
     :output-jar (str (fs/new-temp-file "/tmp" "rules-clojure-test" ".jar"))}))

(deftest bootstrap
  (let [req (bootstrap-compile-req)]
    #p (:output-jar req)
    (s/explain ::worker/compile-request req)
    (is (worker/process-request req))
    (is (fs/exists? (fs/->path (:output-jar req))))))

(deftest persistent)
