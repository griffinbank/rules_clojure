# Releasing rules_clojure

Every commit to `main` is released to the
[Bazel Central Registry](https://github.com/bazelbuild/bazel-central-registry)
(BCR) by CircleCI. Consumers then use:

```starlark
bazel_dep(name = "rules_clojure", version = "X.Y.N")
```

## Versions

`MODULE.bazel` holds only `X.Y` (e.g. `version = "0.5"`). To bump the major or
minor version, change it in a PR; nothing else needs doing by hand.

CI computes the patch number `N` with `bb release-version`: the number of
commits on `main` (first-parent) since `X.Y` last changed. The commit that
changes `X.Y` is released as `X.Y.0`, and each later merge as the next `N`. A
given commit always gets the same version, so you can check one locally:

```sh
bb release-version origin/main
```

`N` has gaps when a commit isn't released (see below), which semver allows.

## Skipping a release

Put `[skip release]` in the commit message (for a merge commit, the PR title
works, since GitHub includes it in the message). The commit still uses up its
`N`.

## What happens on each commit

CircleCI runs the `release` workflow: tests, then `publish-to-bcr`, which runs
`bb publish-to-bcr <commit>` (`tools/release/publish.clj`). That script:

- computes `X.Y.N` and builds `rules_clojure-X.Y.N.tar.gz` with `git archive`,
  with `MODULE.bazel`'s version rewritten to `X.Y.N` (BCR requires the archive's
  `MODULE.bazel` to match the registry's). The tarball is reproducible;
- creates a GitHub release for it, which also creates the `vX.Y.N` tag on the
  commit (BCR prefers release assets over `archive/refs/tags/...` URLs because
  tag archives aren't byte-stable). Tag pushes don't trigger CircleCI
  workflows;
- clones the BCR and generates `modules/rules_clojure/X.Y.N/` with the BCR's
  own `add_module` and `bcr_validation` tools, using `.bcr/presubmit.yml` and
  (first release only) `.bcr/metadata.json` from this repo;
- pushes a branch to the `griffinbank/bazel-central-registry` fork and opens a
  PR against upstream.

Publishes run one at a time, in order. BCR CI then builds `examples/simple`
against the new entry. A maintainer listed in `.bcr/metadata.json` approves,
then the `bazel-io` bot merges. The version is usually resolvable within an
hour.

The `test` job runs `examples/simple` with the same extra flags BCR presubmit
uses, so most BCR failures show up on the PR instead of after merge.

## When a publish fails

Fix the cause if it's in CI, and re-run the job from CircleCI. Re-running is
safe: the version comes from history, an existing release and tarball are
reused as-is (never replaced, since BCR records the tarball's hash), and the
fork branch is force-pushed.

If the BCR PR fails because of a bug in rules_clojure, merge the fix to `main`;
that commit is released as the next version. Close the failed BCR PR.

## Maintainers

`.bcr/metadata.json` lists who can approve BCR PRs for this module and who gets
pinged on them. It is only copied to the BCR when the module is first created;
to change maintainers later, edit `modules/rules_clojure/metadata.json` directly
in a BCR PR. `github_user_id` is the numeric id from
`https://api.github.com/users/<login>`.

## Yanking a bad release

Don't delete the tag or release. Open a BCR PR adding the version to
`yanked_versions` in `modules/rules_clojure/metadata.json` with a reason, and
publish a fixed version.

## Testing locally

BCR validation only accepts `https://github.com/griffinbank/rules_clojure/...`
archive URLs, so there's no fully offline mode. You can run everything except
the fork push and PR, for a commit on `main`:

```sh
GITHUB_TOKEN=<token> DRY_RUN=1 bb publish-to-bcr <commit>
```

This creates the GitHub release, generates and validates the registry entry,
and prints the path of a BCR clone with the entry committed. Requires
[babashka](https://babashka.org) (`bb`), `gh`, `bazelisk` as `bazel` (the BCR
pins its own Bazel version), and a JDK.

Then point a consumer at that clone as a local registry. In banksy, remove the
`archive_override` for `rules_clojure`, set the `bazel_dep` version, and run:

```sh
bazel build --registry=file:///path/printed/by/the/script/bcr \
    --registry=https://bcr.bazel.build --lockfile_mode=off //some:target
```

The dry run creates the real GitHub release and `vX.Y.N` tag. That's fine for a
commit CI will publish anyway, since CI reuses them. Otherwise, remove them:

```sh
gh release delete vX.Y.N --repo griffinbank/rules_clojure --cleanup-tag --yes
```
