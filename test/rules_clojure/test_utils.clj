(ns rules-clojure.test-utils
  (:require [clojure.string :as str]
            [rules-clojure.fs :as fs])
  (:import [com.google.devtools.build.runfiles Runfiles]))

(defn runfiles
  "Returns all runfiles as (coll-of nio.file.Paths)"
  []
  (fs/ls-r (fs/->path "../")))

(defn runfiles-env
  "Look up runfiles passed via bazel env var. see ./BUILD target( env={})"
  [env]
  (let [runfiles (Runfiles/create)]
    (-> (System/getenv env)
        (str/split #" ")
        (->> (map (fn [p]
                    (.rlocation runfiles p)))))))

 (defn runfiles-jars
  "return a seq of all jars on the runfiles path"
  [env]
   (->>
   (runfiles-env env)
   (filter (fn [p] (re-find #".jar$" (str p))))))
