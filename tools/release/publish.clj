(ns release.publish
  "Publish a commit on main as a release of rules_clojure to the Bazel Central
  Registry.

  Run as `bb publish-to-bcr [commit]` from the root of a rules_clojure checkout
  with full history of main. CircleCI runs it on every commit to main (see
  .circleci/config.yml), but it can be run locally too. Commits whose message
  contains \"[skip release]\" are skipped.

  Requires: git, gh, curl, bazelisk-as-bazel, and GITHUB_TOKEN with permission to
    - create tags and releases on griffinbank/rules_clojure
    - push to the BCR fork (griffinbank/bazel-central-registry)
    - open PRs against bazelbuild/bazel-central-registry

  Steps:
    1. compute VERSION (X.Y.N) for the commit with release.version
    2. build a source tarball with `git archive`, with MODULE.bazel's version
       rewritten from X.Y to X.Y.N, and attach it to a GitHub release, creating
       the vX.Y.N tag on the commit
    3. clone the BCR, generate modules/rules_clojure/<VERSION> with the BCR's own
       add_module tool (which also runs bcr_validation --fix)
    4. push a branch to our BCR fork and open a PR upstream

  Re-running for the same commit is safe: the version is derived from history,
  and an existing release and its tarball are reused as-is.

  DRY_RUN=1 does steps 1-3 (including creating the GitHub release), skips the
  fork push and PR, and leaves the BCR clone on disk so you can point a consumer
  at it with --registry=file://<path>. See RELEASING.md \"Testing locally\"."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [release.version :as version]))

(def module "rules_clojure")
(def gh-owner "griffinbank")
(def gh-repo (str gh-owner "/" module))
(def bcr-upstream "bazelbuild/bazel-central-registry")
(def bcr-fork (str gh-owner "/bazel-central-registry"))

(defn- fail! [msg]
  (throw (ex-info msg {})))

(defn- out
  "Run a command, returning trimmed stdout. Throws on non-zero exit."
  [opts & cmd]
  (-> (apply p/shell (merge {:out :string} opts) cmd) :out str/trim))

(defn- try-out
  "Run a command, returning trimmed stdout, or nil on non-zero exit."
  [& cmd]
  (let [{:keys [exit out]} (apply p/shell {:out :string :err :string :continue true} cmd)]
    (when (zero? exit)
      (str/trim out))))

(defn- skip-release? [sha]
  (str/includes? (out {} "git" "log" "-1" "--format=%B" sha) "[skip release]"))

(defn- check-tag!
  "The tag is created by `gh release create` below. If it already exists (a
  re-run), it must be on this commit."
  [tag sha]
  (let [tag-sha (try-out "gh" "api" (str "repos/" gh-repo "/commits/" tag) "--jq" ".sha")]
    (when (and (seq tag-sha) (not= tag-sha sha))
      (fail! (format "tag %s already exists on %s, not %s" tag tag-sha sha)))))

(defn- build-tarball!
  "Archive a synthetic commit: the release commit's tree with the published
  MODULE.bazel, and the release commit's dates, so the tarball is reproducible."
  [{:keys [sha version work module-bazel strip-prefix tarball]}]
  (let [index-env {"GIT_INDEX_FILE" (str (fs/path work "index"))}
        _ (out {:extra-env index-env} "git" "read-tree" sha)
        blob (out {} "git" "hash-object" "-w" (str module-bazel))
        _ (out {:extra-env index-env} "git" "update-index" "--cacheinfo" (str "100644," blob ",MODULE.bazel"))
        tree (out {:extra-env index-env} "git" "write-tree")
        date (out {} "git" "log" "-1" "--format=%cI" sha)
        archive-commit (out {:extra-env {"GIT_AUTHOR_NAME" module "GIT_AUTHOR_EMAIL" "ci@griffin.com" "GIT_AUTHOR_DATE" date
                                         "GIT_COMMITTER_NAME" module "GIT_COMMITTER_EMAIL" "ci@griffin.com" "GIT_COMMITTER_DATE" date}}
                            "git" "commit-tree" tree "-p" sha "-m" (str module " " version))]
    (p/shell "git" "archive" "--format=tar.gz" (str "--prefix=" strip-prefix "/")
             "-o" (str tarball) archive-commit)))

(defn- release!
  "Create the GitHub release (and tag) with the tarball. Never replaces a tarball
  that's already published: BCR records its hash."
  [{:keys [sha tag tarball]}]
  (if (try-out "gh" "release" "view" tag "--repo" gh-repo)
    (if (some #{(str (fs/file-name tarball))}
              (str/split-lines (out {} "gh" "release" "view" tag "--repo" gh-repo "--json" "assets" "--jq" ".assets[].name")))
      (println (str "release " tag " already has " (fs/file-name tarball) "; reusing it"))
      (p/shell "gh" "release" "upload" tag (str tarball) "--repo" gh-repo))
    (p/shell "gh" "release" "create" tag (str tarball) "--repo" gh-repo
             "--target" sha "--title" tag "--generate-notes")))

(defn- downloadable? [url]
  (zero? (:exit (p/shell {:continue true} "curl" "-sfLI" url "-o" "/dev/null"))))

(defn- wait-for-download!
  "Release assets can take a few seconds to become downloadable."
  [url]
  (when-not (some (fn [_]
                    (or (downloadable? url)
                        (do (Thread/sleep 5000) false)))
                  (range 30))
    (fail! (str url " not downloadable"))))

(defn- generate-entry!
  "Clone the BCR and generate and validate modules/rules_clojure/<version>."
  [{:keys [src-root work version module-bazel archive-url strip-prefix dry-run?]}]
  (let [bcr (fs/path work "bcr")
        branch (str module "-" version)
        in-bcr (fn [& cmd] (apply p/shell {:dir (str bcr)} cmd))]
    (when-not dry-run?
      (p/shell "gh" "repo" "sync" bcr-fork "--source" bcr-upstream "--force"))
    (p/shell "git" "clone" "--depth=1" (str "https://github.com/" bcr-upstream ".git") (str bcr))
    (in-bcr "git" "config" "user.name" (or (System/getenv "GIT_AUTHOR_NAME") "griffinbank-ci"))
    (in-bcr "git" "config" "user.email" (or (System/getenv "GIT_AUTHOR_EMAIL") "ci@griffin.com"))
    (in-bcr "git" "checkout" "-b" branch)

    ;; add_module prompts interactively for maintainers on a brand-new module, so
    ;; seed metadata.json from our checked-in copy the first time round.
    (let [metadata (fs/path bcr "modules" module "metadata.json")]
      (when-not (fs/exists? metadata)
        (fs/create-dirs (fs/parent metadata))
        (fs/copy (fs/path src-root ".bcr" "metadata.json") metadata)))

    (let [module-json (fs/path work "module.json")]
      (spit (str module-json)
            (json/generate-string
             {:name module
              :version version
              :compatibility_level nil
              :module_dot_bazel (str module-bazel)
              :url archive-url
              :strip_prefix strip-prefix
              :deps []
              :patches []
              :patch_strip 0
              :build_file nil
              :presubmit_yml (str (fs/path src-root ".bcr" "presubmit.yml"))
              :build_targets []
              :test_module_path "examples/simple"
              :test_module_build_targets []
              :test_module_test_targets ["//..."]
              :matrix_bazel_versions ["8.x"]
              :matrix_platforms ["macos" "debian10" "ubuntu2004"]}
             {:pretty true}))
      ;; Runs bcr_validation --check=rules_clojure@VERSION --fix as its last
      ;; step, which fills in metadata.json's versions list.
      (in-bcr "bazel" "run" "//tools:add_module" "--" (str "--input=" module-json)))

    ;; Exit 42 means validation passed but a BCR maintainer must review the PR
    ;; (always true for a module's first version); that's expected, not a failure.
    (let [{:keys [exit]} (p/shell {:dir (str bcr) :continue true}
                                  "bazel" "run" "//tools:bcr_validation" "--" (str "--check=" module "@" version))]
      (when-not (#{0 42} exit)
        (fail! (str "bcr_validation failed (exit " exit ")"))))

    (in-bcr "git" "add" (str "modules/" module))
    (in-bcr "git" "commit" "-m" (str module "@" version))
    {:bcr bcr :branch branch}))

(defn- open-pr! [{:keys [bcr branch version tag]}]
  (let [in-bcr (fn [& cmd] (apply p/shell {:dir (str bcr)} cmd))
        token (or (System/getenv "GITHUB_TOKEN") (fail! "GITHUB_TOKEN is not set"))]
    (in-bcr "git" "remote" "add" "fork" (str "https://x-access-token:" token "@github.com/" bcr-fork ".git"))
    (in-bcr "git" "push" "-f" "fork" branch)
    (p/shell "gh" "pr" "create" "--repo" bcr-upstream
             "--head" (str gh-owner ":" branch) "--base" "main"
             "--title" (str module "@" version)
             "--body" (str "Release: https://github.com/" gh-repo "/releases/tag/" tag))))

(defn publish! [rev]
  (let [dry-run? (= "1" (System/getenv "DRY_RUN"))
        src-root (version/git "rev-parse" "--show-toplevel")
        sha (version/git "rev-parse" "--verify" (str rev "^{commit}"))]
    (if (skip-release? sha)
      (println "commit" sha "says [skip release]; not publishing")
      (let [version (version/compute sha)
            tag (str "v" version)
            _ (println "publishing" sha "as" module version)
            _ (doseq [f [".bcr/metadata.json" ".bcr/presubmit.yml"]]
                (when-not (fs/exists? (fs/path src-root f))
                  (fail! (str "missing " f))))
            _ (check-tag! tag sha)
            work (fs/create-temp-dir)
            strip-prefix (str module "-" version)
            ctx {:src-root src-root
                 :sha sha
                 :version version
                 :tag tag
                 :work work
                 :dry-run? dry-run?
                 :module-bazel (fs/path work "MODULE.bazel")
                 :strip-prefix strip-prefix
                 :tarball (fs/path work (str strip-prefix ".tar.gz"))
                 :archive-url (format "https://github.com/%s/releases/download/%s/%s.tar.gz" gh-repo tag strip-prefix)}]
        (try
          ;; MODULE.bazel as published: the checked-in X.Y version replaced with X.Y.N.
          (spit (str (:module-bazel ctx))
                (version/rewrite-module-version (:out (p/shell {:out :string} "git" "show" (str sha ":MODULE.bazel"))) version))
          (build-tarball! ctx)
          (release! ctx)
          (wait-for-download! (:archive-url ctx))
          (let [ctx (merge ctx (generate-entry! ctx))]
            (if dry-run?
              (println (format "
DRY_RUN: skipped fork push and PR. Registry entry is committed on branch %s in:
  %s
Test it from a consumer with:
  bazel build --registry=file://%s --registry=https://bcr.bazel.build --lockfile_mode=off //your:target
Clean up with:
  rm -rf %s
  gh release delete %s --repo %s --cleanup-tag --yes   # if this release was only a test"
                               (:branch ctx) (:bcr ctx) (:bcr ctx) work tag gh-repo))
              (open-pr! ctx)))
          (finally
            (when-not dry-run?
              (fs/delete-tree work))))))))

(defn -main [& [rev]]
  (version/run-main #(publish! (or rev (System/getenv "CIRCLE_SHA1") "HEAD"))))
