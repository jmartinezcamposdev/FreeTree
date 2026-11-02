# 09 — Testing and Quality Gates

Parent: [SPEC.md](SPEC.md). Contracts: [02-contracts.md](02-contracts.md).

## Test matrix

1. **Parser goldens:** real Git output fixtures. Cover spaces/Unicode/newlines in paths, renames,
   deletes, binary, empty repo, detached HEAD, annotated tag, tag to tree, symref, root commit,
   octopus merge.
2. **Graph properties:** seeded `RandomDag`; I1–I7 from [04-graph.md](04-graph.md); merges, missing
   parents, many columns; one-shot vs random-batch.
3. **Git oracles:** `GitTestRepo` builds a seeded real repo and compares against independent Git
   commands:
   - commits/parents vs `git rev-list --parents --topo-order`;
   - refs vs `git show-ref`;
   - files vs `git show --name-status --format= -M --first-parent`;
   - status vs `git status --porcelain=v1 -z`;
   - stage/unstage/discard/commit effects vs `git status` and `git diff --cached --name-only`.
   - Discard: staged tracked → index returns to HEAD, worktree unchanged; staged new → index entry
     removed, file remains untracked; unstaged tracked → worktree restored from index; untracked →
     deleted; both → staged keeps the worktree, unstaged keeps the index; conflicts excluded.
4. **Queries:** table tests for `parseQuery`/`resolve`; compare against equivalent `git rev-list`.
5. **Branch filters:** pure tests for field matching, Any/All, strict date boundaries, and
   nearest-branch mapping (divergence, merges, ties, current/local priority); real-git acceptance
   for include/exclude by author, email, message, and date plus `LogSpec.excludeRevs`; session tests
   for spec/labels/persistence/cancellation; UI tests for dialog CRUD, field selector, validation,
   and screenshots.
6. **Sessions/tabs:** T1–T7 with `FakeGitBackend`/`FakeWatcher` and `kotlinx-coroutines-test`:
   virtual time, late responses, cancellation, writes surviving tab switches.
7. **Watcher:** temporary directories; branches, commits, checkout, `git gc` with `packed-refs`.
   Tolerant waits (up to ~5 s); no event-order assumptions. Verify on macOS, then Linux, then
   Windows.
8. **Push/pull:** local bare repo as `origin`: push, fast-forward pull, divergence error, missing
   upstream error.
9. **UI smoke:** Compose Desktop test API with `FakeGitBackend`: graph appears, row selection shows
   files, stage moves a file, discard updates sections, tab switch preserves state. Save PNGs to
   `build/screenshots/`; use `xvfb-run` on headless Linux.
10. **Performance:** budgets, benchmark repositories, execution rules, and the optimization
    backlog live in [13-performance.md](13-performance.md). `bench` is separate from `check`.

## Quality gates

- `./gradlew check` green (unit/property/session/watcher/integration; not `bench`, not
  screen-dependent UI tests).
- `./gradlew :core:test` uses no external repo or network.
- No `TODO` without an entry in `temp/PROGRESS.md` (local-only; never tracked).
- No silent `catch`.
- No visual magic numbers outside `GraphStyle.kt`.
- `core` imports no Compose, AWT, Swing, or `java.awt`.
- Contract changes recorded in `temp/DECISIONS.md` (local-only; never tracked).

Performance budgets, benchmarks, and execution rules: [13-performance.md](13-performance.md).

## Adversarial checklist

Weird paths; empty repo; detached HEAD; thousands of branches; a commit with 100 parents;
worktrees/submodules; Unicode/CRLF messages; closing a tab during a load; Git failing mid-command;
watcher bursts; a 50 MB diff; discard on staged/unstaged/untracked files; discard of a staged new
file; deleting an untracked file; permission errors.
