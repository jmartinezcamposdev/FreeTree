# 07 — Sessions and Tabs

Parent: [SPEC.md](SPEC.md). Contracts: [02-contracts.md](02-contracts.md).

## Lifecycle

`RepoSession` is `INACTIVE ⇄ ACTIVE`, with `DRAINING` while a write runs.

| Transition | Actions |
|---|---|
| `activate()` | New read scope → start watcher → show frozen snapshot → `refs()` + `head()`; reload graph if refs changed or absent → `status()` always. |
| `deactivate()` | Cancel read scope → stop watcher → keep `TabState` + last snapshot. A running write moves to `DRAINING`, then `INACTIVE`, and notifies its result. |
| `closeTab()` | `deactivate()` + discard snapshot + remove tab + activate neighbor. A running write delays closing until done. |

## Concurrency

- Read scope: `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- Writes (stage, discard, commit, push, pull) use a separate session-owned write scope and a
  `Mutex`, so they survive tab switches and never race on `index.lock`.
- Reads are parallel and cancelable. A new selection cancels the previous detail/diff load (100 ms
  debounce for keyboard navigation).
- `busy` is visible and disables the relevant buttons.
- After every write, refresh status and refs explicitly.
- Late results from a deactivated session must not modify state.

## Graph loading

- **Initial/Filter:** cancel previous load, clear, stream. Publish every 2,000 rows or 100 ms; final
  snapshot has `complete = true`.
- **Branch filter:** resolve before loading the graph. The resolver streams candidate branches,
  maps matching commits to their nearest branch, updates `filterVisibleRefs` + labels, then the
  graph loads with only those refs. Re-resolve when refs, hidden refs, remotes, or the filter
  change; cancellation follows the graph-load sequence.
- **Refresh:** build in the background, swap atomically, preserve selection by hash and scroll anchor
  (first visible hash; fall back to index).
- At most one load in flight.

## Selection

- `WorkingTree` → `ChangesPanel` (staged/unstaged/conflicts, message, Commit, discard actions).
- `Commit(hash)` → `CommitPanel` (metadata, files, diff).
- Graph item 0 is always “Changes (N)”; commit row `r` is item `r + 1`.
- A file may be both staged and unstaged. Untracked files are unstaged only.

## Tabs and persistence

- `SessionManager` persists `TabState` and the active tab to:
  - macOS: `~/Library/Application Support/FreeTree/session.json`
  - Linux: `~/.config/freetree/session.json`
  - Windows: `%APPDATA%\FreeTree\session.json`
- Global preferences, window geometry, panel splits, and recent repositories live in
  `preferences.json` in the same application-data directory.
- Both files are versioned JSON documents written atomically through the shared `JsonFileStore`.
  The previous `state.json` and Java `Preferences` values are migrated automatically on first run.
- Restore tabs without loading them; load only the active one. Missing paths become “unavailable”.
- Opening the same repository twice activates the existing tab.
- Inactive tabs keep their last snapshot. The memory trade-off and cap/LRU policy live in
  [13-performance.md](13-performance.md).
- `TabState.branchFilter` persists the applied filter per tab. Resolution results are transient.
- `RepoUiState` exposes `filterBusy`, `filterError`, and `filterVisibleRefs`; the graph spec and
  `labelsByCommit` are derived from the same visible set. Tags annotate only while a filter is
  active; HEAD keeps its history when `showHead` is on.

## Invariants (tests with fakes and virtual time)

- **T1** At most one session is `ACTIVE`.
- **T2** An `INACTIVE` session has no processes, watcher, live coroutines, or I/O; only `TabState`
  + one immutable snapshot.
- **T3** Results arriving after deactivation do not modify state.
- **T4** Tab switch never blocks the UI and shows the frozen snapshot in < 50 ms.
- **T5** Query, hidden refs, selection, and scroll state are preserved per tab.
- **T6** A write is not cancelled by a tab switch and its result is notified.
- **T7** Two tabs for the same repository are not allowed.
