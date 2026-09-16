# Releasing rules_clojure

Releases are published to the [Bazel Central Registry](https://github.com/bazelbuild/bazel-central-registry)
(BCR) by CircleCI whenever a `vX.Y.Z` tag is pushed. Consumers then use:

```starlark
bazel_dep(name = "rules_clojure", version = "X.Y.Z")
```

## Cutting a release

1. Bump `version` in `MODULE.bazel` to the new semver (e.g. `0.5.0`). The tag
   must be `v` + that exact string, or the publish job fails.
2. Merge to `main`, then tag and push:

   ```sh
   git checkout main && git pull
   git tag v0.5.0
   git push origin v0.5.0
   ```

3. CircleCI runs the `release` workflow: tests, then `publish-to-bcr`, which
   runs `tools/publish_to_bcr.sh`. That script:
   - builds `rules_clojure-X.Y.Z.tar.gz` with `git archive` and attaches it to
     a GitHub release for the tag (BCR prefers release assets over
     `archive/refs/tags/...` URLs because tag archives aren't byte-stable);
   - clones the BCR and generates `modules/rules_clojure/X.Y.Z/` with the BCR's
     own `add_module` and `bcr_validation` tools, using `.bcr/presubmit.yml`
     and (first release only) `.bcr/metadata.json` from this repo;
   - pushes a branch to the `griffinbank/bazel-central-registry` fork and opens
     a PR against upstream.
4. Watch the BCR PR. BCR CI builds `examples/simple` against the new entry. A
   maintainer listed in `.bcr/metadata.json` approves, then the `bazel-io`
   bot merges. The version is usually resolvable within an hour.

If the job fails partway, fix the cause and re-run it from CircleCI. The script
is idempotent: it reuses an existing GitHub release and force-pushes the fork
branch.

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
the fork push and PR:

```sh
git tag v0.5.0 && git push origin v0.5.0     # the real tag; delete it afterwards if this is only a test
GITHUB_TOKEN=<token> DRY_RUN=1 tools/publish_to_bcr.sh v0.5.0
```

This creates the GitHub release, generates and validates the registry entry,
and prints the path of a BCR clone with the entry committed. Requires `gh`,
`bazelisk` as `bazel` (the BCR pins its own Bazel version), and a JDK.

Then point a consumer at that clone as a local registry. In banksy, remove the
`archive_override` for `rules_clojure`, set the `bazel_dep` version, and run:

```sh
bazel build --registry=file:///path/printed/by/the/script/bcr \
    --registry=https://bcr.bazel.build --lockfile_mode=off //some:target
```

If this was a throwaway test, remove the tag and release before the real one:

```sh
gh release delete v0.5.0 --repo griffinbank/rules_clojure --cleanup-tag --yes
```

Re-running without `DRY_RUN` reuses an existing release and force-pushes the
fork branch, so a dry run followed by the real run for the same tag is fine.
