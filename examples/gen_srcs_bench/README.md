# gen_srcs benchmark example

Synthetic benchmark workspace for `clojure_gen_srcs`.

## Targets

- `//:gen` — generates a deterministic Clojure source tree under `src/`
- `//:gen_srcs` — runs `rules_clojure.gen_build/srcs` via `clojure_gen_srcs`

## Usage

From this directory:

```bash
bazel run //:gen
bazel run //:gen_srcs
```

## Hyperfine benchmark

Generate the source tree once, then benchmark `gen_srcs` while deleting generated `BUILD.bazel` files before each run:

```bash
bazel run //:gen
hyperfine \
  --warmup 2 \
  --runs 10 \
  --prepare 'find src -name BUILD.bazel -type f -delete' \
  --export-json hyperfine.json \
  'bazel run //:gen_srcs'
```

This keeps the workload consistent while avoiding per-iteration `gen` churn that can add extra Bazel cache noise.
