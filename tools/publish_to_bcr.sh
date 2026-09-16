#!/usr/bin/env bash
# Publish a tagged release of rules_clojure to the Bazel Central Registry.
#
# Run from the root of a rules_clojure checkout with the release tag checked out.
# Intended to run in CircleCI on tag pushes (see .circleci/config.yml), but can
# be run locally too.
#
# Requires: git, gh, curl, bazelisk-as-bazel, and GITHUB_TOKEN with permission to
#   - create releases on griffinbank/rules_clojure
#   - push to the BCR fork (griffinbank/bazel-central-registry)
#   - open PRs against bazelbuild/bazel-central-registry
#
# Steps:
#   1. derive VERSION from the tag and check MODULE.bazel agrees
#   2. build a source tarball with `git archive` and attach it to a GitHub release
#   3. clone the BCR, generate modules/rules_clojure/<VERSION> with the BCR's own
#      add_module tool (which also runs bcr_validation --fix)
#   4. push a branch to our BCR fork and open a PR upstream
set -euo pipefail

MODULE=rules_clojure
GH_OWNER=griffinbank
GH_REPO="$GH_OWNER/$MODULE"
BCR_UPSTREAM=bazelbuild/bazel-central-registry
BCR_FORK="$GH_OWNER/bazel-central-registry"

TAG="${1:-${CIRCLE_TAG:-}}"
if [[ -z "$TAG" ]]; then
  echo "usage: $0 vX.Y.Z   (or set CIRCLE_TAG)" >&2
  exit 1
fi
if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "tag '$TAG' must look like vX.Y.Z" >&2
  exit 1
fi
VERSION="${TAG#v}"

SRC_ROOT="$(git rev-parse --show-toplevel)"
cd "$SRC_ROOT"

# --- 1. version sanity -------------------------------------------------------
MODULE_VERSION="$(sed -nE 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' MODULE.bazel | head -1)"
if [[ "$MODULE_VERSION" != "$VERSION" ]]; then
  echo "MODULE.bazel says version \"$MODULE_VERSION\" but tag is $TAG; bump MODULE.bazel first" >&2
  exit 1
fi
for f in .bcr/metadata.json .bcr/presubmit.yml; do
  [[ -f "$f" ]] || { echo "missing $f" >&2; exit 1; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --- 2. GitHub release with a stable source tarball --------------------------
STRIP_PREFIX="$MODULE-$VERSION"
TARBALL="$STRIP_PREFIX.tar.gz"
ARCHIVE_URL="https://github.com/$GH_REPO/releases/download/$TAG/$TARBALL"

git archive --format=tar.gz --prefix="$STRIP_PREFIX/" -o "$WORK/$TARBALL" "$TAG"

if gh release view "$TAG" --repo "$GH_REPO" >/dev/null 2>&1; then
  echo "release $TAG already exists; uploading tarball if missing"
  gh release upload "$TAG" "$WORK/$TARBALL" --repo "$GH_REPO" --clobber
else
  gh release create "$TAG" "$WORK/$TARBALL" --repo "$GH_REPO" \
    --title "$TAG" --generate-notes
fi

# Release assets can take a few seconds to become downloadable.
for _ in $(seq 1 30); do
  if curl -sfLI "$ARCHIVE_URL" -o /dev/null; then break; fi
  sleep 5
done
curl -sfLI "$ARCHIVE_URL" -o /dev/null || { echo "$ARCHIVE_URL not downloadable" >&2; exit 1; }

# --- 3. generate the registry entry -----------------------------------------
BCR="$WORK/bcr"
gh repo sync "$BCR_FORK" --source "$BCR_UPSTREAM" --force
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
  "module_dot_bazel": "$SRC_ROOT/MODULE.bazel",
  "url": "$ARCHIVE_URL",
  "strip_prefix": "$STRIP_PREFIX",
  "deps": [],
  "patches": [],
  "patch_strip": 0,
  "build_file": null,
  "presubmit_yml": "$SRC_ROOT/.bcr/presubmit.yml",
  "build_targets": [],
  "test_module_path": null,
  "test_module_build_targets": [],
  "test_module_test_targets": [],
  "matrix_bazel_versions": [],
  "matrix_platforms": []
}
EOF

# Runs bcr_validation --check=rules_clojure@VERSION --fix as its last step, which
# fills in metadata.json's versions list.
bazel run //tools:add_module -- --input="$WORK/module.json"
bazel run //tools:bcr_validation -- "--check=$MODULE@$VERSION"

# --- 4. PR -------------------------------------------------------------------
git add "modules/$MODULE"
git commit -m "$MODULE@$VERSION"
git remote add fork "https://x-access-token:${GITHUB_TOKEN}@github.com/$BCR_FORK.git"
git push -f fork "$BRANCH"

BODY="Release: https://github.com/$GH_REPO/releases/tag/$TAG"
gh pr create --repo "$BCR_UPSTREAM" \
  --head "$GH_OWNER:$BRANCH" --base main \
  --title "$MODULE@$VERSION" --body "$BODY"
