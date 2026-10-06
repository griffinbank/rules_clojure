(ns release.version
  "Computes the release version X.Y.N for a commit on main.

  X.Y is the `version` in MODULE.bazel, which humans edit to bump major/minor.
  N is the number of first-parent commits on main since X.Y last changed, so the
  commit that changes X.Y is X.Y.0 and each later merge to main gets the next N.
  The same commit always maps to the same version. See RELEASING.md.

  Requires full (non-shallow) history of main."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn git
  "Run git, returning trimmed stdout. Throws on non-zero exit."
  [& args]
  (-> (apply p/shell {:out :string} "git" args) :out str/trim))

(def ^:private version-re #"(?m)^\s*version\s*=\s*\"([^\"]+)\"")

(defn module-version
  "The `version` in MODULE.bazel at `rev`, or nil if there's no MODULE.bazel."
  [rev]
  (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                    "git" "show" (str rev ":MODULE.bazel"))]
    (when (zero? exit)
      (second (re-find version-re out)))))

(defn rewrite-module-version
  "MODULE.bazel contents `s` with its module version replaced by `version`."
  [s version]
  (str/replace-first s version-re (fn [[m old]] (str/replace m old version))))

(defn- major-minor
  "e.g. 0.5.7 -> 0.5"
  [v]
  (when v
    (str/join "." (take 2 (str/split v #"\.")))))

(defn compute
  "The release version X.Y.N for commit `rev`. Throws ex-info if it can't be
  determined."
  [rev]
  (when (= "true" (git "rev-parse" "--is-shallow-repository"))
    (throw (ex-info "shallow clone; run 'git fetch --unshallow' first" {})))
  (let [sha (git "rev-parse" "--verify" (str rev "^{commit}"))
        xy (module-version sha)
        _ (when-not (and xy (re-matches #"\d+\.\d+" xy))
            (throw (ex-info (format "MODULE.bazel version at %s is \"%s\"; it must be exactly X.Y (CI appends the patch number)" sha (or xy "")) {})))
        ;; The most recent commit on main whose first parent had a different
        ;; major.minor. Changes that keep major.minor (e.g. 0.5.7 -> 0.5) don't
        ;; reset N.
        base (->> (str/split-lines (git "log" "--first-parent" "--format=%H" sha "--" "MODULE.bazel"))
                  (remove str/blank?)
                  (some (fn [c]
                          (when (not= xy (major-minor (module-version (str c "^"))))
                            c))))
        _ (when-not base
            (throw (ex-info (format "couldn't find the commit that set version %s" xy) {})))
        n (git "rev-list" "--count" "--first-parent" (str base ".." sha))]
    (str xy "." n)))

(defn run-main
  "Call `f`, and on failure print the error to stderr and exit 1. A failed
  subprocess has already printed its own stderr, so only its program and
  subcommand are shown: the full command line can contain a token."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [cmd exit]} (ex-data e)]
        (binding [*out* *err*]
          (println (if cmd
                     (format "`%s` failed (exit %s)" (str/join " " (take 2 cmd)) exit)
                     (ex-message e)))))
      (System/exit 1))))

(defn -main [& [rev]]
  (run-main #(println (compute (or rev "HEAD")))))
