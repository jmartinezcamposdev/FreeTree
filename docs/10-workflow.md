# 10 — Workflow

Parent: [SPEC.md](SPEC.md).

## Workstreams

| Workstream | Owns | Delivers |
|---|---|---|
| **GIT** | `core/git/**`, `core/watch/**` | `GitRunner`, `CliGitBackend`, parsers, discard, watcher, classifier, tests |
| **GRAPH** | `core/graph/**`, `core/query/**` | lane engine, snapshot, chunks, query parser/resolver, I1–I7 |
| **SESSION** | `core/session/**` | `RepoSession`, `SessionManager`, persistence, T1–T7 |
| **UI** | `app/**` | Section 08 UI against fakes, screenshots |
| **QA** | test fixtures, acceptance tests, `bench` | fakes, `GitTestRepo`, `RandomDag`, 50k/200k repos, oracles |

Rules:

- Each workstream owns its paths; do not edit outside them.
- Contract changes go through the orchestrator and `temp/DECISIONS.md` (local-only; never tracked).
- Work in a branch/worktree; report what changed, commands run, output, and open questions.
- Deliver with your own tests green. QA writes independent acceptance tests.
- QA delivers fakes early; they unblock SESSION and UI.

## Phases

**Phase 0 — Bootstrap and contracts.** Migrate to `core` + `app`; create contracts; empty window;
`temp/PROGRESS.md`/`temp/DECISIONS.md` (local-only, never tracked). Gate: `./gradlew build`,
`:core:test`, `:app:run`.

**Phase 1 — Core.** GIT, GRAPH, and QA in parallel; SESSION starts when fakes exist. Gate: each
workstream's tests green.

**Phase 2 — Session and UI.** Connect sessions to UI with fakes. Gate: T1–T7 and UI smoke green.

**Phase 3 — Integration.** Real `CliGitBackend` and watcher; oracles; watcher integration; push/pull
bare repo; manual real-repo flow. Gate: `./gradlew check` and the full flow works.

**Phase 4 — Hardening.** Adversarial review, 200k benchmark, optimize only measured bottlenecks,
package `Dmg`, `Deb`, `Msi` as applicable. Gate: budgets met or deviations logged.

## Acceptance

- [ ] **R1** Stable lanes, merge arrows, ref chips, merge nodes; I1–I7 green.
- [ ] **R2** Commit selection shows files/diff; binary and huge diffs stay safe.
- [ ] **R3** Staged/unstaged/conflicts; per-file stage/unstage incl. deletes, renames, unborn HEAD;
      multiline commit; state matches `git status`.
- [ ] **R4** Push/pull work without prompts; full Git errors; no hung processes.
- [ ] **R5** Manual ref hiding and query filtering; flat mode; syntax errors do not lose the view.
- [ ] **R6** Performance budgets met or deviations documented.
- [ ] **R7** Independent tab state; T1–T7 green; inactive tabs consume no CPU/process/watcher.
- [ ] **R8** Runs on macOS, Linux, Windows in that priority; installer at least on macOS.
- [ ] **R9** Discard staged (index only; disk untouched), discard unstaged tracked (restore) and
      untracked (delete); confirmation for unstaged; conflicts excluded; state matches Git.
- [ ] Watcher is not required for correctness.
- [ ] Zero silent catches; zero visual magic numbers outside `GraphStyle.kt`.

## Risks

| Risk | Mitigation |
|---|---|
| Watcher misses events | Explicit refresh, focus refresh, polling fallback; never source of truth. |
| 200k `--topo-order` slow | commit-graph advice; measure; optimize Git/layout first. |
| Inactive snapshot memory | Retain for MVP; cap/LRU only if needed. |
| First-parent extra lane | Accepted; post-MVP visual improvement. |
| Huge diffs/untracked files | 2 MB cap, binary detection, opt-in full load. |
| Platform differences | macOS first, then Linux/Windows; use `-z`, UTF-8, no shell. |
| Java 25 vs Compose/Gradle | Verify in Phase 0; log the fallback JDK if incompatible. |
| Worktrees/submodules | Use `git rev-parse`; never assume `.git/` is a directory. |
| Discard accidents | Separate staged/unstaged actions; confirm unstaged and state what is lost on disk. |

## Deferred

LRU snapshots, joining an existing first-parent lane, dotted edges/re-parenting, `is:merge`,
`branch:` alias, draggable panes, syntax highlighting, side-by-side diff, hunk staging, conflict
resolution, branch/tag management.

## Tracking templates

`temp/PROGRESS.md` and `temp/DECISIONS.md` are **local-only working docs**. They live under `temp/`
and must never be tracked or committed to Git.

`temp/PROGRESS.md`:

```markdown
# Progress

## Phase 0 — Bootstrap
- [ ] Migrate to core + app
- [ ] Contracts
- [ ] Empty window
- Evidence: `./gradlew build` → ...

## Phase 1 — Core
### GIT
- [ ] ...
- Evidence: `./gradlew :core:test` → ...

### GRAPH
...

## Phase 2 — Session/UI
...

## Phase 3 — Integration
...

## Phase 4 — Hardening
...
```

`temp/DECISIONS.md`:

```markdown
| Date | ID | Decision | Reason | Alternatives |
|---|---|---|---|---|
| 2026-10-29 | D2 | Split core/app | Enforce no-Compose boundary; enable test fixtures | single module |
```
