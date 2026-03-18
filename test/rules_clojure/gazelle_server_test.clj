(ns rules-clojure.gazelle-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [rules-clojure.gazelle-server :as gs])
  (:import (java.io BufferedReader BufferedWriter File StringReader StringWriter)))

(deftest read-request-parses-json-line
  (let [input "{\"type\":\"init\",\"count\":42}\n"
        reader (BufferedReader. (StringReader. input))
        result (gs/read-request reader)]
    (is (= {:type "init" :count 42} result))
    (is (keyword? (first (keys result))))))

(deftest read-request-returns-nil-on-eof
  (let [reader (BufferedReader. (StringReader. ""))]
    (is (nil? (gs/read-request reader)))))

(deftest write-response-produces-json-line
  (let [sw (StringWriter.)
        writer (BufferedWriter. sw)]
    (gs/write-response writer {:type "init" :data [1 2 3]})
    (let [output (.toString sw)
          parsed (json/read-str (clojure.string/trim output) :key-fn keyword)]
      (is (clojure.string/ends-with? output "\n"))
      (is (= "init" (:type parsed)))
      (is (= [1 2 3] (:data parsed))))))

(deftest read-write-roundtrip
  (let [msg {:type "parse" :ns_deps {"foo.bar" ["baz.quux"]}}
        sw (StringWriter.)
        writer (BufferedWriter. sw)]
    (gs/write-response writer msg)
    (let [reader (BufferedReader. (StringReader. (.toString sw)))
          result (gs/read-request reader)]
      (is (= "parse" (:type result)))
      (is (= {"foo.bar" ["baz.quux"]} (:ns_deps result))))))

(deftest handle-init-response-shape
  (testing "init handler returns expected keys using project deps.edn"
    (let [;; Use the project's own deps.edn as fixture
          workspace-root (System/getProperty "user.dir")
          deps-edn-path (str workspace-root "/deps.edn")
          ;; Use a temp dir as repository-dir (maven local repo)
          repo-dir (str (System/getProperty "java.io.tmpdir")
                        "/gazelle-server-test-"
                        (System/currentTimeMillis))
          _ (.mkdirs (java.io.File. repo-dir))
          resp (gs/handle-init {:deps_edn_path deps-edn-path
                                :repository_dir repo-dir
                                :deps_repo_tag "@deps"
                                :aliases []})]
      ;; Wire response keys
      (is (= "init" (:type resp)))
      (is (map? (:dep_ns_labels resp)))
      (is (contains? (:dep_ns_labels resp) "clj"))
      (is (contains? (:dep_ns_labels resp) "cljs"))
      (is (map? (:deps_bazel resp)))
      (is (vector? (:ignore_paths resp)))
      (is (vector? (:source_paths resp)))
      (is (pos? (count (:source_paths resp))))

      ;; Internal state present
      (is (map? (:_state resp)))
      (let [state (:_state resp)]
        (is (map? (:basis state)))
        (is (map? (:jar->lib state)))
        (is (map? (:class->jar state)))
        (is (map? (:dep-ns->label state)))
        (is (some? (:deps-edn-path state)))
        (is (some? (:deps-edn-dir state))))

      ;; _state keys should NOT appear in JSON wire format
      (let [wire (dissoc resp :_state)
            json-str (json/write-str wire)
            reparsed (json/read-str json-str :key-fn keyword)]
        (is (not (contains? reparsed :_state)))
        (is (= "init" (:type reparsed)))))))

(defn- write-temp-clj!
  "Write a .clj file into dir with the given filename and content string."
  [^File dir filename content]
  (let [f (File. dir filename)]
    (spit f content)
    f))

(deftest handle-parse-clj-file
  (testing "parse handler extracts namespace info from a .clj file"
    (let [dir (File. (System/getProperty "java.io.tmpdir")
                     (str "gazelle-parse-test-" (System/currentTimeMillis)))
          _ (.mkdirs dir)
          _ (write-temp-clj! dir "core.clj"
              "(ns my.core (:require [clojure.string :as str] [clojure.set]))")
          state {:jar->lib {}
                 :class->jar {}
                 :deps-repo-tag "@deps"}
          resp (gs/handle-parse state {:dir (str dir) :files ["core.clj"]})]
      (is (= "parse" (:type resp)))
      (is (= 1 (count (:namespaces resp))))
      (let [ns-info (first (:namespaces resp))]
        (is (= "my.core" (:ns ns-info)))
        (is (= "core.clj" (:file ns-info)))
        (is (= ["clj"] (:platforms ns-info)))
        (is (contains? (:requires ns-info) "clj"))
        (is (contains? (set (get-in ns-info [:requires "clj"])) "clojure.string"))
        (is (contains? (set (get-in ns-info [:requires "clj"])) "clojure.set"))))))

(deftest handle-parse-cljc-file
  (testing "parse handler reports both platforms for .cljc"
    (let [dir (File. (System/getProperty "java.io.tmpdir")
                     (str "gazelle-parse-cljc-" (System/currentTimeMillis)))
          _ (.mkdirs dir)
          _ (write-temp-clj! dir "util.cljc" "(ns my.util)")
          state {:jar->lib {} :class->jar {} :deps-repo-tag "@deps"}
          resp (gs/handle-parse state {:dir (str dir) :files ["util.cljc"]})]
      (is (= 1 (count (:namespaces resp))))
      (let [ns-info (first (:namespaces resp))]
        (is (= "my.util" (:ns ns-info)))
        (is (= ["clj" "cljs"] (:platforms ns-info)))))))

(deftest handle-parse-js-files
  (testing "parse handler returns minimal entries for .js files"
    (let [state {:jar->lib {} :class->jar {} :deps-repo-tag "@deps"}
          resp (gs/handle-parse state {:dir "/tmp" :files ["app.js" "lib.js"]})]
      (is (= "parse" (:type resp)))
      (is (= 2 (count (:namespaces resp))))
      (is (every? #(= ["js"] (:platforms %)) (:namespaces resp)))
      (is (= #{"app.js" "lib.js"} (set (map :file (:namespaces resp))))))))

(deftest handle-parse-mixed-files
  (testing "parse handler handles clj and js files together"
    (let [dir (File. (System/getProperty "java.io.tmpdir")
                     (str "gazelle-parse-mixed-" (System/currentTimeMillis)))
          _ (.mkdirs dir)
          _ (write-temp-clj! dir "core.clj" "(ns mixed.core)")
          state {:jar->lib {} :class->jar {} :deps-repo-tag "@deps"}
          resp (gs/handle-parse state {:dir (str dir) :files ["core.clj" "bundle.js"]})]
      (is (= 2 (count (:namespaces resp))))
      (let [by-file (into {} (map (juxt :file identity) (:namespaces resp)))]
        (is (= "mixed.core" (:ns (get by-file "core.clj"))))
        (is (= ["clj"] (:platforms (get by-file "core.clj"))))
        (is (= ["js"] (:platforms (get by-file "bundle.js"))))))))

(deftest handle-parse-ns-metadata
  (testing "parse handler captures ns metadata"
    (let [dir (File. (System/getProperty "java.io.tmpdir")
                     (str "gazelle-parse-meta-" (System/currentTimeMillis)))
          _ (.mkdirs dir)
          _ (write-temp-clj! dir "aot.clj"
              "(ns my.aot {:bazel/clojure_library {:aot true}} (:gen-class))")
          state {:jar->lib {} :class->jar {} :deps-repo-tag "@deps"}
          resp (gs/handle-parse state {:dir (str dir) :files ["aot.clj"]})]
      (let [ns-info (first (:namespaces resp))]
        (is (= {:bazel/clojure_library {:aot true}} (:ns_meta ns-info)))))))
