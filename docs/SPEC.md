# FreeTree — Specification Index

> **Status:** normative. The old Claude/Gemini specs now live in `temp/` and are not tracked.
>
> **Product:** FreeTree, a desktop Git graph client built with Kotlin and Compose Multiplatform
> Desktop.
>
> **Platform priority:** macOS (development platform), then Linux, then Windows.
>
> **Language:** English. Code identifiers and comments are English.

Read this page first, then the numbered documents in order. Details live in the topic documents;
this page only holds scope, decisions, and the map.

## How to use

1. Follow the phases in [10-workflow.md](10-workflow.md). Each phase has a gate.
2. Decisions marked **[D]** are closed. Record changes in `DECISIONS.md`.
3. Keep `PROGRESS.md` and `DECISIONS.md` current. Templates: [10-workflow.md](10-workflow.md).
4. Never declare work done without running its verification command and reading the output.
5. Keep the main branch green: `./gradlew check` before merging.
6. Keep documents short: use the minimum words that preserve the information. Prefer tables and
   bullets; split by topic; do not duplicate.
7. **Code clarity is the top priority.** Prefer clear names and small functions. Comments are
   allowed only for non-obvious intent, invariants, or workarounds; never restate what the code
   does.
8. Use the versions already in `gradle/libs.versions.toml`; pin new dependencies after verifying
   compatibility. Do not copy versions from the old specs.

## Requirements

| ID | Requirement |
|---|---|
| R1 | Commit graph in the style of `git log --graph`, with merge arrows and branch/tag/HEAD labels. |
| R2 | Select a commit to see changed files and per-file diffs. |
| R3 | Working-tree status, per-file stage/unstage, and commit with a message. |
| R4 | `push` and `pull` against a configured `origin`, without credential prompts; show Git errors in full. |
| R5 | Hide/show refs interactively and filter with text queries. |
| R6 | Stay fluid on repositories of roughly 50k–300k commits. |
| R7 | Multiple tabs, one per repository; only the active repository runs engine work. |
| R8 | Compose Multiplatform Desktop on macOS, then Linux, then Windows. |
| R9 | Per-file discard: staged files are removed from the index (disk untouched); unstaged tracked files are restored from the index; untracked files are deleted from disk. Unstaged actions require confirmation. |

## Non-goals (MVP)

Conflict resolution, hunk/line staging, rebase/cherry-pick/reset/stash, branch/tag creation or
deletion, independent `fetch`, amend, credential management, syntax highlighting, side-by-side
diff, submodule management, file editing, draggable panes, LRU snapshot eviction, lane compaction,
mobile support.

Worktrees and submodules must not break the app; they may be shown as ordinary working trees.

## Decisions

| ID | Decision |
|---|---|
| D1 | Use the system `git` binary through `ProcessBuilder`; never JGit/libgit2, never a shell, never direct writes into `.git`. |
| D2 | Split into `core` (pure Kotlin/JVM, no Compose) and `app` (Compose Desktop). |
| D3 | Git is the single source of truth; the app parses and renders. |
| D4 | Queries resolve to a `LogSpec`; content filters switch the graph to flat mode. |
| D5 | One `RepoSession` per tab, exactly one active; inactive tabs keep `TabState` + the last immutable `GraphSnapshot`. |
| D6 | Writes are serialized and survive tab switches; a session with a write is `DRAINING`. |
| D7 | The watcher is a latency optimization, never required for correctness. |
| D8 | Graph storage is chunked; the UI is a fixed-height `LazyColumn` with one `Canvas` per row. |
| D9 | Product name **FreeTree**; root package `com.jmartinezcampos.freetree`. |
| D10 | The MVP is R1–R9. Anything outside them is post-MVP; the non-goals list is explicit. |
| D11 | Platform priority: macOS, then Linux, then Windows. |
| D12 | Develop on Java 25. If Compose Multiplatform or Gradle incompatibility is proven, log the fallback JDK in `DECISIONS.md`. |

## Architecture

```
app (Compose Desktop)
  TabBar · Toolbar · FilterBar · GraphList · CommitPanel · ChangesPanel · DiffView
        ▲ StateFlow<RepoUiState>                  actions ▼
core (pure Kotlin/JVM)
  SessionManager → RepoSession (one per tab; one ACTIVE)
    ├─ QueryResolver (pure)        ├─ LaneLayoutEngine (pure)
    ├─ RepoWatcher → EventClassifier → Debouncer
    └─ GitBackend → GitRunner → git process + parsers
```

Threading: process/stream I/O on `Dispatchers.IO`; parsing/layout on `Dispatchers.Default`; Compose
state on the main thread. One writer per `LaneLayoutEngine`; the UI reads immutable snapshots.

## Documentation map

| Document | Contents |
|---|---|
| [01-bootstrap.md](01-bootstrap.md) | Repository migration, target layout, dependencies, Phase 0 gate. |
| [02-contracts.md](02-contracts.md) | Models, `GitBackend`, watcher, tab/session, graph and query APIs. |
| [03-git.md](03-git.md) | Git process rules, command matrix, discard, diffs, errors. |
| [04-graph.md](04-graph.md) | Lane algorithm, geometry, invariants, storage. |
| [05-query.md](05-query.md) | Query grammar, ref selection, flat mode. |
| [06-watcher.md](06-watcher.md) | Watch paths, event classification, debounce, refresh. |
| [07-sessions.md](07-sessions.md) | Session lifecycle, tabs, concurrency, persistence. |
| [08-ui.md](08-ui.md) | Layout, graph list, panels, discard UI, `GraphStyle`. |
| [09-testing.md](09-testing.md) | Test matrix, quality gates, performance budgets. |
| [10-workflow.md](10-workflow.md) | Workstreams, phases, acceptance criteria, tracking. |
| [11-edge-cases.md](11-edge-cases.md) | Edge-case checklist. |

## Roadmap

| Phase | Goal | Gate |
|---|---|---|
| 0 | Bootstrap, module migration, contracts, empty window | `./gradlew build`, `:core:test`, `:app:run` |
| 1 | Git, graph, query, watcher, fakes | each workstream's tests green |
| 2 | Sessions and UI against fakes | T1–T7 and UI smoke green |
| 3 | Real Git integration | `./gradlew check`; manual real-repo flow |
| 4 | Hardening, performance, packaging | budgets met or deviations logged |

Acceptance: see [10-workflow.md](10-workflow.md).
