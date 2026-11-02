# 13 — Performance and Future Work

Parent: [SPEC.md](SPEC.md). Related: [04-graph.md](04-graph.md),
[07-sessions.md](07-sessions.md), [09-testing.md](09-testing.md),
[10-workflow.md](10-workflow.md), [12-filters.md](12-filters.md).

This is the single place for performance budgets, benchmarks, memory trade-offs, and the
performance backlog. Feature semantics stay in their feature documents; this document covers how we
measure and improve them.

## Principles

- **Measure before optimizing.** Change code only for a measured bottleneck, not an assumed one.
- `bench` is separate from `check`; it is run explicitly on the 50k and 200k repositories.
- Recording a number is preferred over a fragile hard assertion.
- Filters and graph loads stay streaming and cancellable; no unbounded in-memory commit lists.

## Budgets

| Metric | Target |
|---|---|
| First visible rows | < 400 ms |
| Full graph | < 3 s; overhead over raw `git log` ≤ 30% |
| Layout throughput | ≥ 300,000 rows/s |
| Graph memory | ≤ 400 bytes/commit |
| Fast scroll | no frame > 16 ms attributable to our code |
| Tab switch | < 50 ms to frozen snapshot |
| Status refresh | dominated by `git status`; our overhead ≤ ~50 ms |

## Execution rules

- Parse/layout on `Dispatchers.Default`; I/O on `Dispatchers.IO`.
- One writer per `LaneLayoutEngine`; the UI reads immutable snapshots.
- Diffs and commit details load on demand and are cancelable.
- Recommend `git commit-graph` in the README; never run it automatically.
- Measure before optimizing.

## Benchmarks

- **50k in-memory DAG** (`SyntheticDagGenerator`): fast CI benchmark, seeded, ~20 authors, ~30
  branches, merges, tags. Record the number; no fragile hard assertion.
- **200k real repository** (QA): `git fast-import`, deterministic seed, ~15 authors, ~300 branches,
  a merge every ~10 commits, ~100 tags, 5 years. Cache in `build/perf-repo/`; use for the manual
  `bench` task and acceptance.
- Run with `./gradlew bench` (see [09-testing.md](09-testing.md) for the surrounding test gates).
  The benchmark is not part of `check`.

## Memory trade-offs

- **Graph storage:** target ≤ 400 bytes/commit on the 200k benchmark. If exceeded, the only allowed
  optimization is delta-encoded `PASS` lanes with checkpoints every 256 rows, behind
  `SegmentsView` ([04-graph.md](04-graph.md)).
- **Inactive tabs:** each inactive tab keeps its last snapshot (about 80 MB for 200k commits).
  Accepted for MVP; add a cap or LRU only if real usage requires it.
- **Large diffs:** the 2 MB cap, binary detection, and opt-in full load are safety decisions, not a
  performance target; see [03-git.md](03-git.md) and [11-edge-cases.md](11-edge-cases.md).

## Branch filters

The filter engine and its `BranchScan`/`ScanPlan` API are defined in
[12-filters.md](12-filters.md). The baseline is `FullScan`: every displayed branch and every
reachable commit. `FilterStats` records scanned commits, candidate branches, include/exclude branch
counts, and duration for diagnostics and tests.

### Baseline cost

- One extra streaming history walk when a filter is applied, followed by the normal graph load.
- `O(commits + edges)` in time and streaming memory; no full commit list is retained.
- Recompute only when the filter, refs, hidden refs, remotes, or scan mode changes (the session
  caches the last resolution digest).

### Phase 2 — unmerged-work scan (opt-in)

- **Important branches** come from a global Preferences list (defaults `main`, `master`), matched by
  short name; `origin/main` counts as `main`. The definition may expand later.
- Important branches are always visible and unfiltered.
- Candidates are displayed branches not fully merged into any important branch.
- Scan range = commits reachable from candidate tips but not from important branches
  (`git rev-list <candidates> --not <important>`).
- Nearest mapping is restricted to candidates.
- Fully merged branches are omitted while a filter is active; their work lives in the important
  branch.
- Default off; enabled by an Advanced option in the Filters dialog.

Implementation notes:

- Batch merged detection with `git for-each-ref --merged=<important>`.
- Use the existing `LogSpec.excludeRevs` foundation (`^ref` lines to `git log --stdin`).
- Detect the repository default branch from `refs/remotes/origin/HEAD` / `symbolic-ref`, cached per
  session.
- If no important branch exists, fall back to full scan.
- Verify the scan does not walk important-branch history.

### Optimization backlog

1. **Baseline measurement:** add a `RandomDag` benchmark (~200k commits) for `FullScan` and record
   scanned commits/time/memory.
2. **Phase 2 fast scan:** `UnmergedWorkScan`, Preferences important branches, Advanced toggle,
   benchmark against full scan.
3. **Nearest-branch index cache:** mapping depends only on refs/topology, not rules; cache it per
   refs snapshot so rule edits do not rescan.
4. **Visible-ref cache:** key by `(filter, refs digest, head, toggles, scanMode)`.
5. **Git predicate pushdown:** push `--author` / `--grep` / `--since` / `--until` into the scanned
   range.
6. **Parallel branch walks** where the scan can be partitioned safely.
7. **Inactive-snapshot cap/LRU** only if measured memory pressure justifies it.

Phases: items 1–2 are phase 2; item 3 and 5–6 are phase 4; item 7 is deferred. Commit-level
filtering is a feature phase in [12-filters.md](12-filters.md), not a performance item.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| 200k `--topo-order` slow | commit-graph advice; measure; optimize Git/layout first. |
| Filter full scan is a second history walk | measure with `FilterStats`; enable the unmerged-work scan. |
| Inactive snapshot memory | retain for MVP; cap/LRU only if measured. |
| Cached nearest-branch index grows too large | cache compactly per refs snapshot; invalidate on refs change. |
| Graph memory budget exceeded | the only allowed graph optimization is delta-encoded `PASS` lanes, behind `SegmentsView`. |

## Gates

- Budgets met, or deviations documented with the measured number and a follow-up item here.
- `./gradlew check` stays green; `bench` remains explicit and out of CI.
- Any optimization lands with before/after numbers from the 50k or 200k benchmark.
