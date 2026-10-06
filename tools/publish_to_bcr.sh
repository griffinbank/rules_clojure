#!/usr/bin/env bash
# Publish a commit on main as a release of rules_clojure to the Bazel Central
# Registry.
#
# Run from the root of a rules_clojure checkout with full history of main.
# CircleCI runs it on every commit to main (see .circleci/config.yml), but it
# can be run locally too. Commits whose message contains "[skip release]" are
# skipped.
#
# Requires: git, gh, curl, bazelisk-as-bazel, and GITHUB_TOKEN with permission to
#   - create tags and releases on griffinbank/rules_clojure
#   - push to the BCR fork (griffinbank/bazel-central-registry)
#   - open PRs against bazelbuild/bazel-central-registry
#
# Steps:
#   1. compute VERSION (X.Y.N) for the commit with tools/compute_version.sh
#   2. build a source tarball with `git archive`, with MODULE.bazel's version
#      rewritten from X.Y to X.Y.N, and attach it to a GitHub release, creating
#      the vX.Y.N tag on the commit
#   3. clone the BCR, generate modules/rules_clojure/<VERSION> with the BCR's own
#      add_module tool (which also runs bcr_validation --fix)
#   4. push a branch to our BCR fork and open a PR upstream
#
# Re-running for the same commit is safe: the version is derived from history,
# and an existing release and its tarball are reused as-is.
#
# DRY_RUN=1 does steps 1-3 (including creating the GitHub release), skips the
# fork push and PR, and leaves the BCR clone on disk so you can point a consumer
# at it with --registry=file://<path>. See RELEASING.md "Testing locally".
set -euo pipefail

DRY_RUN="${DRY_RUN:-0}"

MODULE=rules_clojure
GH_OWNER=griffinbank
GH_REPO="$GH_OWNER/$MODULE"
BCR_UPSTREAM=bazelbuild/bazel-central-registry
BCR_FORK="$GH_OWNER/bazel-central-registry"

SRC_ROOT="$(git rev-parse --show-toplevel)"
cd "$SRC_ROOT"

REV="${1:-${CIRCLE_SHA1:-HEAD}}"
SHA="$(git rev-parse --verify "$REV^{commit}")"

if git log -1 --format=%B "$SHA" | grep -qF '[skip release]'; then
  echo "commit $SHA says [skip release]; not publishing"
  exit 0
fi

# --- 1. version --------------------------------------------------------------
VERSION="$(tools/compute_version.sh "$SHA")"
TAG="v$VERSION"
echo "publishing $SHA as $MODULE $VERSION"

for f in .bcr/metadata.json .bcr/presubmit.yml; do
  [[ -f "$f" ]] || { echo "missing $f" >&2; exit 1; }
done

# The tag is created by `gh release create` below. If it already exists (a
# re-run), it must be on this commit.
TAG_SHA="$(gh api "repos/$GH_REPO/commits/$TAG" --jq .sha 2>/dev/null || true)"
if [[ -n "$TAG_SHA" && "$TAG_SHA" != "$SHA" ]]; then
  echo "tag $TAG already exists on $TAG_SHA, not $SHA" >&2
  exit 1
fi

WORK="$(mktemp -d)"
if [[ "$DRY_RUN" != "1" ]]; then
  trap 'rm -rf "$WORK"' EXIT
fi

# MODULE.bazel as published: the checked-in X.Y version replaced with X.Y.N.
git show "$SHA:MODULE.bazel" \
  | awk -v v="$VERSION" '!done && /^[[:space:]]*version[[:space:]]*=/ { sub(/"[^"]*"/, "\"" v "\""); done = 1 } { print }' \
  > "$WORK/MODULE.bazel"

# --- 2. GitHub release with a stable source tarball --------------------------
STRIP_PREFIX="$MODULE-$VERSION"
TARBALL="$STRIP_PREFIX.tar.gz"
ARCHIVE_URL="https://github.com/$GH_REPO/releases/download/$TAG/$TARBALL"

# Archive a synthetic commit: the release commit's tree with the published
# MODULE.bazel, and the release commit's dates, so the tarball is reproducible.
export GIT_INDEX_FILE="$WORK/index"
git read-tree "$SHA"
git update-index --cacheinfo "100644,$(git hash-object -w "$WORK/MODULE.bazel"),MODULE.bazel"
TREE="$(git write-tree)"
unset GIT_INDEX_FILE
COMMIT_DATE="$(git log -1 --format=%cI "$SHA")"
ARCHIVE_COMMIT="$(GIT_AUTHOR_NAME=rules_clojure GIT_AUTHOR_EMAIL=ci@griffin.com GIT_AUTHOR_DATE="$COMMIT_DATE" \
  GIT_COMMITTER_NAME=rules_clojure GIT_COMMITTER_EMAIL=ci@griffin.com GIT_COMMITTER_DATE="$COMMIT_DATE" \
  git commit-tree "$TREE" -p "$SHA" -m "$MODULE $VERSION")"
git archive --format=tar.gz --prefix="$STRIP_PREFIX/" -o "$WORK/$TARBALL" "$ARCHIVE_COMMIT"

# Never replace a tarball that's already published: BCR records its hash.
if gh release view "$TAG" --repo "$GH_REPO" >/dev/null 2>&1; then
  if gh release view "$TAG" --repo "$GH_REPO" --json assets --jq '.assets[].name' | grep -qxF "$TARBALL"; then
    echo "release $TAG already has $TARBALL; reusing it"
  else
    gh release upload "$TAG" "$WORK/$TARBALL" --repo "$GH_REPO"
  fi
else
  gh release create "$TAG" "$WORK/$TARBALL" --repo "$GH_REPO" \
    --target "$SHA" --title "$TAG" --generate-notes
fi

# Release assets can take a few seconds to become downloadable.
for _ in $(seq 1 30); do
  if curl -sfLI "$ARCHIVE_URL" -o /dev/null; then break; fi
  sleep 5
done
curl -sfLI "$ARCHIVE_URL" -o /dev/null || { echo "$ARCHIVE_URL not downloadable" >&2; exit 1; }

# --- 3. generate the registry entry -----------------------------------------
BCR="$WORK/bcr"
if [[ "$DRY_RUN" != "1" ]]; then
  gh repo sync "$BCR_FORK" --source "$BCR_UPSTREAM" --force
fi
git clone --depth=1 "https://github.com/$BCR_UPSTREAM.git" "$BCR"
cd "$BCR"
git config user.name "${GIT_AUTHOR_NAME:-griffinbank-ci}"
git config user.email "${GIT_AUTHOR_EMAIL:-ci@griffin.com}"
BRANCH="$MODULE-$VERSION"
git checkout -b "$BRANCH"

# add_module prompts interactively for maintainers on a brand-new module, so
# seed metadata.json from our checked-in copy the first time round.
if [[ ! -f "modules/$MODULE/metadata.json" ]]; then
  mkdir -p "modules/$MODULE"
  cp "$SRC_ROOT/.bcr/metadata.json" "modules/$MODULE/metadata.json"
fi

cat > "$WORK/module.json" <<EOF
{
  "name": "$MODULE",
  "version": "$VERSION",
  "compatibility_level": null,
  "module_dot_bazel": "$WORK/MODULE.bazel",
  "url": "$ARCHIVE_URL",
  "strip_prefix": "$STRIP_PREFIX",
  "deps": [],
  "patches": [],
  "patch_strip": 0,
  "build_file": null,
  "presubmit_yml": "$SRC_ROOT/.bcr/presubmit.yml",
  "build_targets": [],
  "test_module_path": "examples/simple",
  "test_module_build_targets": [],
  "test_module_test_targets": ["//..."],
  "matrix_bazel_versions": ["8.x"],
  "matrix_platforms": ["macos", "debian10", "ubuntu2004"]
}
EOF

# Runs bcr_validation --check=rules_clojure@VERSION --fix as its last step, which
# fills in metadata.json's versions list.
bazel run //tools:add_module -- --input="$WORK/module.json"
# Exit 42 means validation passed but a BCR maintainer must review the PR
# (always true for a module's first version); that's expected, not a failure.
rc=0
bazel run //tools:bcr_validation -- "--check=$MODULE@$VERSION" || rc=$?
if [[ "$rc" != 0 && "$rc" != 42 ]]; then
  echo "bcr_validation failed (exit $rc)" >&2
  exit "$rc"
fi

# --- 4. PR -------------------------------------------------------------------
git add "modules/$MODULE"
git commit -m "$MODULE@$VERSION"

if [[ "$DRY_RUN" == "1" ]]; then
  cat <<EOF

DRY_RUN: skipped fork push and PR. Registry entry is committed on branch $BRANCH in:
  $BCR
Test it from a consumer with:
  bazel build --registry=file://$BCR --registry=https://bcr.bazel.build --lockfile_mode=off //your:target
Clean up with:
  rm -rf $WORK
  gh release delete $TAG --repo $GH_REPO --cleanup-tag --yes   # if this release was only a test
EOF
  exit 0
fi

git remote add fork "https://x-access-token:${GITHUB_TOKEN}@github.com/$BCR_FORK.git"
git push -f fork "$BRANCH"

BODY="Release: https://github.com/$GH_REPO/releases/tag/$TAG"
gh pr create --repo "$BCR_UPSTREAM" \
  --head "$GH_OWNER:$BRANCH" --base main \
  --title "$MODULE@$VERSION" --body "$BODY"
