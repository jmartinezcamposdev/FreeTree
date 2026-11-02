# FreeTree Correctness and Resilience Audit

**Original audit:** 2026-11-02, revision `bb8092f` (“feat(ui): add copy all to the diff context menu”).

**Revalidated:** 2026-11-02/03 at `d22626f` (code through `f035b19`, including branch filters).
The original document (`docs/audit/2026-11-02-flaw-audit.md`) has been superseded by this file.

**Method:** static review of `core`, `app`, tests, and normative docs; five independent read-only
investigations; safe Git experiments in temporary repositories. No source code was changed and no
Gradle build/test was run during revalidation. Claims that need runtime/benchmark proof are marked
**hypothesis**.

**Scope:** user-work loss, Git correctness, persistence/concurrency, performance cliffs, and
workflow dead ends. This is a backlog, not a specification. Line references are current at the
revalidation revision and will drift.

## 1. How to read this document

Severity describes realistic user damage:

| Level | Meaning |
|---|---|
| **P0** | Can destroy uncommitted work, orphan commits, or corrupt repository state. |
| **P1** | Can lose drafts/settings/session state, mislead about repository state, or block recovery. |
| **P2** | Correctness bug, serious performance cliff, or workflow dead end. |
| **P3** | Quality-of-life, accessibility, or maintainability issue. |

Status labels:

- **Confirmed** — code path is unambiguous or reproduced at the current revision.
- **Corrected** — the issue is real, but the original diagnosis or severity was partly wrong.
- **Hypothesis** — needs a runtime, process-level, platform, or benchmark test.
- **Non-goal** — intentional/documented behavior, not a defect.
- **Non-issue** — disproven; do not “fix” it.

The audit was written before the branch-filter work. That changed the scope of `E1` and the docs,
but none of the P0/P1 lifecycle, Git-write, persistence, or performance findings were fixed.

## 2. Executive summary

The audit is strong: the P0/P1 work-loss findings are mostly real, and the recommended direction is
sound. Its main weakness is that it treats every observation as an independent issue. Many share a
root cause, several are duplicates, and a few are overstated or outdated.

The backlog is best organized into workstreams by root cause. The main groups are:

| Workstream | Original IDs | Severity | Why it matters |
|---|---|---|---|
| **WL-1 Shutdown/write lifecycle** | A1, A13 | P0/P1 | Quit or timeout can force-kill Git mid-write. |
| **WL-2 Draft and shortcut safety** | A2, A6, E5, C10 | P0/P1 | Most frequent authored-content loss. |
| **WL-3 Recoverable discards** | A3, A8 | P0/P1 | Untracked files are deleted with no trash/undo; C8/C9 compound it (GH-5). |
| **WL-4 Result model, detached HEAD, warnings** | A4, A7, C1, F4, F5 | P0/P1 | Orphan commits; successful Git warnings/hook output are invisible. |
| **WL-5 Worktree identity and instance safety** | A5, A14, A15 | P1 | The same worktree can be opened/written twice; invalid tabs persist; no single instance. E11 overlaps recents. |
| **GH-1 Branch deletion safety** | A9 | P1 | Wrong reachability predicate; no force fallback in a common topology; no recovery. |
| **GH-2 Dirty checkout** | A10 | P1 | Uncommitted work silently moves to another branch. |
| **GH-3 Merge/rebase/conflict recovery** | A11, C14 | P1/P2 | No abort/continue; resolved conflicts cannot be staged; combined diffs are empty. |
| **GH-4 Push destination** | A12 | P1 | Bare push can push unintended branches or the wrong remote. |
| **PS-1 Corrupt persistence** | B3, F7 | P1 | Corrupt session/settings are overwritten; the only error flow is dead. |
| **PS-2 Persistence writer and scroll state** | B1, B2, B12, E6, F8 | P1/P2 | UI-thread write amplification, out-of-order saves, wrong tab anchors. |
| **PS-3 Startup restore** | B11 | P1 | Launching with a path silently replaces the saved session. |
| **PF-1 Graph hash index** | D2 | P1 | Repeated O(n) map builds during partial graph loads; worse than the audit stated. |
| **PF-2 Eager lists and uncapped status** | D1, C13, F9 | P1/P2 | Thousands of rows composed eagerly; output/derived work scales with them. |
| **UX-1 Legacy filters** | E1 | P1 for legacy sessions | Branch filters now work; old query/hidden-ref state is still trapped and unclearable. |
| **UX-2 Feedback and error surface** | E3, E4, F6 | P2 | Success is invisible; a large error bar can squeeze the app. |
| **UX-3 Tabs, tooltips, closing feedback** | E2, E12, E13, E22 | P2/P3 | Overflow hides tabs; small close target; no “closing after write” state. |
| **UX-4 Keyboard/accessibility** | E14, E15, E23, F12 | P3 (mostly) | Semantics/focus-trap gaps; audit overstates keyboard activation. |
| **UX-5 Preferences, recents, labels, version** | E9, E10, E11, E17–E21 | P3 | Usability and maintenance polish. |
| **AR Architecture/quality** | F1–F14 | Maintainability | God class, duplicated sources, no CI/lint, stale docs, dependency risk. |

**P0 shortlist:** WL-1 (quit/write kill), WL-2 (draft/shortcut), WL-3 (untracked discard), and WL-4
(detached/warnings). **P1 shortlist:** WL-5, GH-1 through GH-4, PS-1 through PS-3, PF-1, PF-2, and
UX-1 for restored legacy sessions.

Lower-priority bundles are detailed later: GH-5 (status/diff/loading), GH-6 (fetch/pull scope),
PS-4/5/6 (watcher, legacy, settings), PF-3/4 (lanes, retention/loads), UX-6 (message log), and
AR-1..4.

**Corrections to the original audit, in one line each:**

- `E1` is partly obsolete: branch filters are reachable; the old query/ref-hiding controls are not.
- `C1`’s unborn-repository claim is false; the detached-HEAD defect is real.
- `C8` is narrower: ordinary untracked directories are expanded by `-uall`; embedded repositories
  are the real directory case.
- `E14` is overstated: Compose `clickable` already provides focusability and Enter/Space; the real
  gaps are roles, focus trapping, and safe default focus (P3).
- `E23` is probably false: clicking a row should focus it; keep only as a regression test.
- `E7` is a documented MVP non-goal, not a defect.
- `B8` and the frequency claims in `B7`/`D7` are overstated: window saves are trailing-debounced.
- `E5`/`C10` duplicate `A2`; `C12` duplicates `C3`; `A8` is an umbrella over `A3`/`A9`.
- `B4` is latent (no schema v2 yet); `B9` is an unverified HiDPI hypothesis.
- `F13` is dependency risk, not a straightforward “pin stable” fix.
- Two real bugs were missed: `A9` can classify a branch “safe” and then `git branch -d` refuses; a
  resolved conflict cannot be staged in-app (`A11`).

## 3. Work loss and destructive operations

### WL-1 — Quit or timeout can force-kill an in-flight Git write

**Original:** A1, A13. **Status:** confirmed code path; exact on-disk damage is a hypothesis.
**Severity:** P0 for app quit/close, P1 for the 120 s timeout.

**What is wrong.** The window close handler and menu Exit call `exitApplication()` with no busy
check. `RepoSession.close()` cancels the write scope, which kills the child Git process with
`destroyForcibly()` (SIGKILL on POSIX). Closing a *tab* is graceful (`beginClose` waits for active
writes); the dangerous path is app quit. A separate watchdog force-kills local writes after 120 s,
which is common for pre-commit test suites, signing, or GPG/pinentry. The app reports nothing
because it is exiting or already timing out.

**Evidence.** `Main.kt:134-140`, `Main.kt:172`; `RepoSession.kt:488-500`; `GitRunner.kt:26-36`,
`:127-133`, `:286-296`. Tests currently pin both the 120 s default and force-kill behavior
(`GitRunnerTest`), so a graceful fix must update them.

**Impact.** A commit can exist but never be reported; checkout/pull can be half-applied; a stale
`index.lock` can block all later writes; the user must repair with the CLI.

**Fix direction.** Add a shutdown coordinator: persist first, detect `busy`/queued writes, show
“Finishing…” with a bounded wait, and never cancel a local write merely because the app is closing.
Escalate SIGTERM → grace → SIGKILL instead of force-killing immediately. Detect and explain
`index.lock` before writes, and record a “previous session exited during an operation” marker.
Make the local-write timeout generous/configurable and allow Cancel with cleanup.

**Validation.** Latched fake commit + window close (confirmation, no cancellation); process-level
kill during checkout/pull (lock/worktree state); hook sleeping beyond the timeout (progress, clean
cancel, no lock).

### WL-2 — Commit draft is destroyed; shortcuts ignore text focus

**Original:** A2, A6, E5, C10. **Status:** confirmed. **Severity:** P0/P1.

**What is wrong.** The commit message is local `remember(session?.id)` state in `ChangesPanel`
(`ChangesPanel.kt:63`). Clicking Commit calls `session.commit(message)` and immediately clears the
field (`:202-203`), before the job has validated or run. Selecting a commit swaps `ChangesPanel` for
`CommitPanel`, disposing the draft; switching tabs resets it; quitting loses it. The root
`onPreviewKeyEvent` handles `Cmd/Ctrl+W` unconditionally (`App.kt:253-267`), so it closes the tab
even while the message field is focused. The current keyboard test locks in the close behavior.

**Impact.** The most frequent authored-content loss: a rejected pre-commit hook, a quick history
inspection, a tab switch, or a muscle-memory `Cmd+W` discards the message. It can also cause a commit
to be abandoned.

**Fix direction.** Move the draft to `TabState` (or a per-session state flow) and clear it only after
a confirmed `BusyOp.COMMIT` success. Preserve it across selection/tab/restart; optionally debounce-
persist it. Make shortcuts focus-aware, confirm closing a tab with a draft, add “Reopen closed tab”,
and enlarge the close target.

**Validation.** Type → tab switch → return; type → select commit → return; failing commit; app
restart; `Cmd/Ctrl+W` while the message field is focused.

### WL-3 — Untracked discard is an unrecoverable delete; no undo

**Original:** A3, A8; C8/C9 compound it (see GH-5). **Status:** confirmed. **Severity:** P0/P1.

**What is wrong.** `discardUnstaged` restores tracked paths, then deletes untracked paths with
`Files.deleteIfExists` (`CliGitBackend.kt:198-226`). There is no trash, backup, or undo journal.
Per-file confirmation copy is now correct, but “Discard all” still shows only a count and a generic
warning. Nested/embedded repositories are the real untracked-directory case: they produce an I/O
error on open, `DirectoryNotEmptyException` on discard, and can fail after earlier paths were
already restored/deleted; the UI returns only one error.

**Impact.** Irrecoverable loss of user-authored notes/scripts/assets; a partial “Discard all” can
leave a mixed, unreported state. `A8` is the umbrella: branch deletes and discards also rely on CLI
recovery only.

**Fix direction.** Move untracked discards to app trash (or `Desktop.moveToTrash` with a fallback)
with a relative-path/timestamp manifest and Restore. Add per-path results/preflight for “Discard
all”, list untracked files explicitly, and keep a bounded operation journal with branch tips,
before/after hashes, and “Undo/Recreate”. Refuse directories with a clear message unless the user
chooses a very explicit recursive delete.

**Validation.** Untracked discard → trash + restore; mixed tracked/untracked discard; nested repo;
undeletable path → partial summary and accurate status.

### WL-4 — Detached HEAD is invisible and successful Git warnings are dropped

**Original:** A4, A7, C1, F4, F5. **Status:** confirmed; one subclaim corrected. **Severity:** P0/P1.

**What is wrong.** `# branch.head` is still discarded (`GitParsers.kt:111`); `WorkingStatus` has no
`branchName`/`detached`, and the toolbar shows an abbreviated hash in a detached checkout
(`Chrome.kt:236-238`). `runWrite` returns only stdout on exit 0 (`CliGitBackend.kt`), discarding
stderr. Git prints “leaving N commits behind” and pre-commit/post-checkout hook output on stderr
even on success. The checkout-commit context action has no confirmation, so a detached commit can
become unreachable except through reflog. `GitResult.Ok` structurally cannot carry warnings.

**Correction.** The audit’s claim that an **unborn** repository displays “detached HEAD” is false:
`head()` returns the branch name via `symbolic-ref`; the toolbar shows it. The real defect is
detached HEAD only. C1 alone is P2; it is P1 as the enabler of A4.

**Fix direction.** Model `BranchState(name, detached, unborn, upstream)`. Preserve stdout and stderr
in the command result, add warning-level messages, and surface successful stderr in the UI/log.
Show a persistent detached banner with “Create branch here”/“Checkout branch”, and pre-check
reachability before leaving a detached HEAD. Confirm checkout-commit and other one-click moves.

**Validation.** Detached/unborn parser fixtures; toolbar/banner UI; real-git “leaving commits”
warning with Cancel; successful pre-commit hook output visible; `/tmp` experiments already confirm
the Git behavior.

### WL-5 — Worktree identity, invalid tabs, and single-instance safety

**Original:** A5, A14, A15; E11 overlaps recents (UX-5). **Status:** confirmed by inspection; runtime scenarios need tests.
**Severity:** P1 (A5 is P1, not P0 in isolation).

**What is wrong.** Dedupe only compares `toAbsolutePath().normalize()` strings
(`SessionManager.kt:60-73`). A subdirectory, symlink, or case alias creates a second tab, while
`CliGitBackend` roots both at the real toplevel. Each `RepoSession` has its own write mutex, so two
sessions can run writes against one index; Git’s own lock prevents silent corruption, but the
sessions, watchers, and recents diverge. Invalid folders are persisted as tabs/recents before any
repository probe, and there is no process-level instance lock. `RecentRepositories` has no
remove/clear.

**Fix direction.** Canonicalize with `toRealPath()` and `git rev-parse --show-toplevel`; dedupe by
`(worktreeRoot, gitDir)`; keep linked worktrees distinct; add a per-worktree write lock. Probe
repositories before creating/persisting tabs and recents; add remove/clear for recents. Acquire an
exclusive app-data lock and focus/refuse a second instance.

**Validation.** Root + subdirectory + symlink + case variant → one tab; linked worktrees → two tabs;
blocked Stage serializes across sessions; invalid folder creates no tab/recent; second instance does
not write.

## 4. Git operation safety

### GH-1 — Branch deletion safety is wrong, and `-d` can dead-end

**Original:** A9. **Status:** confirmed; **new bug added**. **Severity:** P1.

**What is wrong.** `isBranchDeletionSafe` checks only `merge-base --is-ancestor hash HEAD` and local
branches containing the hash (`RepoSession.kt:436-441`); `localBranchesContaining` only queries
`refs/heads` (`CliGitBackend.kt:320-332`). Tags, remote-tracking refs, stash, and reflog are
ignored, so a branch already pushed/tagged gets a false “may lose commits” warning. Worse, a branch
merged only into another local branch (e.g. `topic` into `release`, while on `main`) is classified
safe, but `git branch -d` refuses; the app has no `-D` fallback, so deletion is a dead end. Force
delete records no tip, so there is no recreation path. Remote delete uses the same local-only
predicate, making its warning text conceptually wrong.

**Fix direction.** Use one reachability query across `HEAD`, other local branches, remote-tracking
refs, tags, and (optionally) stash/reflog. Show *where* the tip is reachable. Try `-d`; on failure,
offer an explicit force-delete confirmation that records the tip and offers “Recreate branch at
SHA”. For remote deletes, show the exact recovery refspec.

**Validation.** Branch reachable only from a tag/remote; branch merged into another local branch
(must not dead-end); force delete → recreate; remote delete warning accuracy.

### GH-2 — Checkout carries dirty changes silently

**Original:** A10. **Status:** confirmed (Git experiment + code). **Severity:** P1.

**What is wrong.** Every checkout/create-branch path is a bare `git checkout` with no dirty-tree
check (`CliGitBackend.kt:281-294`; `App.kt` branch actions). Git exits 0 and carries modified files;
the carried-file output is on stdout and only reaches the hidden message log. The user can commit
onto the new branch by mistake.

**Fix direction.** Inspect `status.entries` before checkout. If dirty, show “N changes will move to
`<branch>`” with Cancel/Continue (and later Stash). Block checkout during merge/rebase/cherry-pick/
revert until GH-3’s actions exist. Show the post-checkout status.

**Validation.** Dirty tree + branch picker: confirmation lists carried files and Cancel performs no
checkout; real-git cross-branch commit prevention.

### GH-3 — No abort/continue; resolved conflicts cannot be staged; combined diffs are empty

**Original:** A11, C14. **Status:** confirmed; **new bug added**. **Severity:** P1/P2.

**What is wrong.** Operation detection exists (`RepoSession`), but `GitBackend` has no
`merge/rebase/cherry-pick/revert --abort/--continue/--skip`. The UI only shows red operation text and
a read-only conflict list. After the user resolves a conflict on disk, Git still reports `UU`; the
app excludes `UNMERGED` paths from Stage (`withoutConflicts`) and renders conflict rows read-only, so
the resolution cannot be staged in-app. Combined `@@@` diffs do not match the hunk parser and are
filtered as file headers, so the conflict diff says “No textual changes”.

**Fix direction.** Add backend/UI actions for abort/continue/skip (merge, rebase, cherry-pick,
revert), an explicit “Stage resolved file” path, and combined-diff parsing/rendering. Disable
checkout/pull/push while an operation is active except where Git explicitly allows it.

**Validation.** Real merge: Abort restores pre-merge, Continue completes; rebase skip/abort/continue;
resolved conflict can be staged; combined fixture renders markers; pull/rebase stopped state is
actionable.

### GH-4 — Push can target the wrong remote or multiple branches

**Original:** A12. **Status:** confirmed (code + Git experiment). **Severity:** P1.

**What is wrong.** No upstream → hardcoded `git push --set-upstream origin <branch>`; otherwise a
bare `git push` (`CliGitBackend.kt:244-253`). Remotes are not enumerated, and `upstream` is unused in
the UI. With `push.default=matching`, one click pushes every matching branch; with a non-origin-only
remote it fails; with a `fork` upstream it silently goes there; detached HEAD falls through to bare
push.

**Fix direction.** Read remotes/upstream and show “Push `main` → `origin/main`”. Always use an
explicit refspec (`HEAD:refs/heads/<branch>`), optionally `--force-with-lease` only after explicit
confirmation. When no upstream, ask which remote/branch; prompt before creating a new remote branch.

**Validation.** Only-`upstream` remote; `push.default=matching` with two branches; fork upstream;
detached HEAD; diverged branch shows full stderr.

### GH-5 — Status, diff, and loading correctness bundle

These are real but mostly small; group them into one “correctness fixes” batch rather than separate
projects.

| Original | What is wrong | Evidence | Fix / test |
|---|---|---|---|
| **C2** | Both untracked `?` and unmerged `U` render as “U”. | `UiFormat.kt:26-36`; `ChangesPanel.kt` passes `'?'`/`entry.index`. | Use `?`/`??` for untracked, `U`/`UU` for unmerged, with a tooltip; table test. |
| **C3 / C12** | `↑0 ↓0` is shown with no upstream; `WorkingStatus.upstream` is parsed but unused. | `Chrome.kt:270-278`; `GitParsers.kt:112-121`. | Hide arrows or say “No upstream”; show upstream name; test both states. |
| **C4** | Failed commit-detail load leaves an infinite spinner. | `CommitPanel.kt:68-73`; `RepoSession` reports only via `message`; no `commitDetailError`. | Add detail error + Retry; fake backend failure test. |
| **C5** | Failed status load leaves “Loading working tree…” forever. | `ChangesPanel.kt:247-253`; no `statusError`. | Add status error + Retry; test. |
| **C6** | Re-selecting the same commit clears the open diff and reloads detail. | `RepoSession.kt:288-310` always cancels/clears. | Early-return when selection is unchanged; assert diff remains and no extra calls. |
| **C7** | CRLF diff lines retain `\r`; `addedTextDiff` strips it, so paths disagree. | `GitParsers.kt:240-288`; `addedTextDiff` at `:299`. | Strip a trailing `\r` for display/copy; CRLF fixture. |
| **C8** | Embedded repositories (only) are reported as directories, then treated as files. Ordinary dirs are expanded by `-uall`. | `GitParsers.kt` keeps `dir/`; `readUntracked`/`discardUnstaged` fail on directories. | Detect directory/embedded repo; show a directory row; explicit confirmation; refuse recursive discard; surface embedded-repo warning. |
| **C9** | `Discard all` can partially succeed and returns one error; no rollback/summary. | `CliGitBackend.kt:211-226`. | Per-path results or preflight; “2 restored, 1 deleted, 1 failed” summary; test mixed nested repo. |
| **C11** | Query `since:`/`until:` are not validated; Git fails late. Legacy persisted queries can still trigger this. | `QueryParser.kt`; query UI unreachable, but restore applies it. | Validate dates or map the graph error to `queryError`; test. |

`C13` (uncapped status/refs/detail/changed-files output) is owned by **PF-2**; it is a correctness
guard and a performance threshold, not a second fix.

### GH-6 — Fetch and pull policy are scope decisions, not defects

**Original:** E7, E8. **Status:** non-goal/intentional. **Severity:** P3/opinion.

`GitBackend` has only push/pull; `SPEC.md` explicitly lists independent fetch as an MVP non-goal.
Pull is a bare `git pull --no-edit` and honors `pull.rebase`/`pull.ff` invisibly; that is documented
behavior. The real risk is interaction with GH-3: a config-driven rebase can stop with no in-app
recovery. If scope stays, at least display the effective pull policy and link a stopped rebase to
GH-3 actions; add fetch only if the roadmap changes.

## 5. Persistence, sessions, and concurrency

### PS-1 — Corrupt persistence is silently overwritten; the error flow is dead

**Original:** B3, F7. **Status:** confirmed. **Severity:** P1.

**What is wrong.** `JsonFileStore.load` throws on malformed JSON; `SessionManager.restoreTabs` sets
`persistenceError`, but no app code collects it. `AppSettingsController` catches to stderr and then
the next update overwrites the corrupt file; if legacy Java Preferences exist, it can overwrite at
startup. There is no `.bak`, quarantine rename, or visible message. A later successful save clears a
prior session error.

**Fix direction.** Preserve before overwrite: rename unreadable files to `.corrupt-<timestamp>`, keep
a last-known-good `.bak`, surface the error in the message bar/log, and never overwrite unreadable
state without a copy. Add fsync/temp cleanup if crash durability matters.

**Validation.** Invalid JSON → defaults + visible error + preserved corrupt file; save again → backup
still present; read-only app-data directory → usable app with error.

### PS-2 — UI-thread write amplification, ordering, closing, and scroll anchors

**Original:** B1, B2, B12, E6, F8. **Status:** confirmed with corrections. **Severity:** P1/P2.

**What is wrong.** Each distinct scroll row and selection calls `updateTab`; `SessionManager.persist`
writes the whole `session.json` synchronously on the UI thread. Identical `TabState`s are deduped, but
distinct anchors are not debounced. Save ordering has no revision/single writer, so an older snapshot
can land after a newer one. `closeTab` does not persist the close request until `finalizeClose`, so a
quit while a long write drains can resurrect the tab. `GraphList` uses an unkeyed
`rememberLazyListState`; switching sessions re-emits the old visible index and writes it into the new
tab. `TabState.scrollAnchorHash` is never read by the UI, so T5 is false in the UI.

**Fix direction.** Keep view state in memory; debounce durable writes (~500 ms) and persist on close;
use one background conflated writer with a revision/last-writer-wins rule. Persist a `closing` marker
immediately. Key list states by session/target and restore from a persisted index+hash; do not write
an anchor until the user actually scrolls after a switch.

**Validation.** Counting persistence: 100 row changes → ≤2 writes; latch test: old save cannot
overwrite newer; blocked write + close + quit → tab not restored; two tabs → independent anchors;
close after write resumes correctly.

### PS-3 — Launching with a repository argument replaces the saved session

**Original:** B11. **Status:** confirmed. **Severity:** P1.

**What is wrong.** `Main` constructs `SessionManager(restore = startupRepository == null)`; opening
the argument persists a one-tab session, overwriting the restored tab set. IDE/Gradle launches are a
normal workflow, so this silently loses tabs/queries/selection.

**Fix direction.** Always restore, then merge the startup path as an additional tab using the
canonical dedupe from WL-5. Do not persist until the merge completes; surface restore failures.

**Validation.** Seed three tabs, launch with a path, assert four tabs and the original three persist
after restart.

### PS-4 — Watcher runtime failure and partial watch are invisible

**Original:** B10. **Status:** confirmed; audit evidence corrected. **Severity:** P2.

**What is wrong.** `JdkRepoWatcher` defaults both `onFailure` and `onWarning` to no-ops, and
production uses the default factory. Startup failure is handled with polling, but a **runtime**
`IOException`/`RuntimeException` calls the no-op and continues; only `ClosedWatchServiceException`
ends the loop. `partialWatch`/the directory cap never reaches `RepoSession`, so there is no polling
or warning. The UI can stay stale indefinitely.

**Fix direction.** Wire callbacks to report an error and start polling; surface a partial-watch
warning. Add `watcherHealthy`/`partialWatch` to state and the message surface.

**Validation.** Fake watcher fails after start → polling + error; low directory cap → warning +
fallback.

### PS-5 — Legacy, schema, and geometry edges

**Original:** B4, B5, B6, B9. **Status:** mixed. **Severity:** P2/P3/hypothesis.

| Original | What is wrong | Correction / action |
|---|---|---|
| **B4** | `schemaVersion` is written but never read/compared. | Latent, not active corruption; add a future-version refusal/migration before the next schema. |
| **B5** | A failed legacy migration write discards readable legacy tabs. | Session-only (settings migration survives); return legacy state even if `save` throws; retry. |
| **B6** | Legacy files/preferences are never retired, so deleting the new file re-imports old data. | Add a migration marker/rename; test deleting the new file. |
| **B9** | Window geometry mixes AWT screen units and Compose `Dp`; HiDPI risk. | Unverified hypothesis; add density 1.0/1.25/1.5/2.0 round-trip tests; verify Windows/Linux. |

### PS-6 — Settings/window performance claims are mostly overstated

**Original:** B7, B8, D7. **Status:** corrected/downgraded. **Severity:** P3.

Window geometry is saved by a **trailing debounce** (one save after motion settles), not every 350
ms. The settings payload is tiny; splitter/close writes are on the UI thread but cheap. Collective
`AppSettings` state can still invalidate the root scope, but `App` receives stable
theme/layout/date params and should skip. Keep a cheap validation: a slow `SettingsStore` must not
block a frame; a recomposition counter around `App` should not increment on `setWindow`; cache desktop
screen enumeration or refresh on display changes. Do not treat this as a priority.

## 6. Performance and scalability

### PF-1 — `GraphSnapshot.indexOfHash` rebuilds a full map per partial snapshot

**Original:** D2. **Status:** confirmed; **worse than the audit stated**. **Severity:** P1.

**What is wrong.** `rowByHash` is a lazy full `HashMap` over every chunk (`Graph.kt:184-193`).
Every partial snapshot is a new `GraphSnapshot` with a fresh lazy map, published every 2,000 rows or
100 ms during a CLEAR load. `GraphList` calls `indexOfHash` for the selected commit **and for the
working-tree row even with no selection** (`GraphList.kt:82-83`, `:229-231`). A 200k-row load can
therefore trigger ~100 full map builds on the UI thread (estimated ~10M transient entries, roughly
50× one final build), and `finishGraphLoad` builds the final map while holding the session lock.

**Fix direction.** Maintain the hash→row index incrementally in the layout engine, or build it once
per snapshot off the UI thread; avoid rebuilding for the working-tree lookup; move the final lookup
outside the lock. Benchmark a partial-load replay (per-snapshot build time, total entries, heap
delta) before/after.

**Validation.** Extend `bench` with 200k partial snapshots and `indexOfHash(head)` per snapshot;
assert cumulative work is bounded to ~one build; add a UI-side count of published graph instances.

### PF-2 — Eager changed-file lists and uncapped status output

**Original:** D1, C13, F9. **Status:** confirmed; practical cliff is composition. **Severity:** P1/P2.

**What is wrong.** `ChangesPanel` and `CommitPanel` use `Column + verticalScroll + forEach` for
conflicts/staged/unstaged/changed files, composing every row on every refresh. Status uses
`--untracked-files=all` with no output cap; refs/detail/changed-files are also uncapped. The dominant
cost is not parsing but composing thousands of rows/semantics nodes; `filterCounts` and status
filtering are recomputed per composition. Two path lists and the menu model may also rebuild on
every partial graph/status emission.

**Fix direction.** Measure first with a 5k-entry fake status and a commit detail with 5k files.
Then use `LazyColumn` with stable keys and section headers (or a paged/capped “first N” list);
consider `--untracked-files=normal` fallback and a clear “too many untracked files” warning. Memoize
derived counts/paths/menu data; do not rebuild the native menu on every partial graph publish.

**Validation.** UI perf test (not `check`) with 5k entries and a refresh; bench a 20k-untracked
working tree; assert bounded first-frame/time/bytes and a graceful cap.

### PF-3 — Lane engine complexity is unmeasured

**Original:** D4. **Status:** confirmed code; impact unquantified. **Severity:** P2.

`LaneLayoutEngine` scans live lanes multiple times per commit and uses a linear `firstFreeSlot`;
lanes are unbounded and only clipped by the UI. Normal repos are fine; hundreds of simultaneous
unmerged branches/pathological DAGs could make layout quadratic-ish. The existing bench does not
exercise wide lanes: the synthetic DAG has ~30 refs/merges every 10, the real bench logs HEAD only,
and the 300-parent test is one row. Add a wide-lane scenario and print `maxLanes` before considering
a free-slot stack or compaction.

### PF-4 — Snapshot retention, opt-in loads, and status bursts

**Original:** D3, D5, D6. **Status:** mostly deliberate or low. **Severity:** P2/P3.

- **D3** inactive tabs retain their snapshot (~80 MB/200k, plus any lazy hash map/diff/status).
  Accepted for MVP; measure 10 retained snapshots and activation cost before adding LRU/cap.
- **D5** the default diff path is capped at 2 MB; only explicit “Load full” is unbounded. Add a
  graceful cap/streaming for that opt-in path; lower priority.
- **D6** watcher status runs once per debounced burst (300 ms quiet / 2 s max), not per event;
  measure a large dirty tree before changing it.
- **D7** screen enumeration is per window save, but saves are trailing-debounced; it belongs with
  PS-6 and is low priority.

## 7. UX, workflow, and maintainability

### UX-1 — Legacy query/ref filters are trapped; branch filters now work

**Original:** E1 (corrected). **Status:** partially outdated. **Severity:** P2; P1 only for restored
legacy sessions.

The old “Filters will be supported in a future version” info dialog is gone; the toolbar now opens a
real branch-filter dialog. However, the original `FilterBar` (query field, per-ref checkboxes,
Remotes/Tags/HEAD toggles) still has no call site. `TabState` still persists `query`/`hiddenRefs` and
`RepoSession` applies them on restore, so a legacy session can open a filtered graph with no visible
control to clear it; toolbar badges still derive from unreachable query/hidden state. Docs/README
still claim interactive ref hiding.

**Fix direction.** Either restore the bar or add a “Clear legacy filters” action (and update docs).
Do not leave persisted filters with no escape hatch. Test a seeded legacy `TabState` and clearing it.

### UX-2 — Success feedback and error bar

**Original:** E3, E4, F6. **Status:** confirmed. **Severity:** P2.

`publishMessage` stores only `UserMessage.Error` in `state.message`; successful `Info` text goes only
to the hidden message log. Users cannot tell whether Stage/Commit/Discard succeeded. The error bar
renders full stderr in an uncapped `Column`, so a large hook failure can squeeze the app and push
Dismiss off-screen.

**Fix direction.** Add `Info`/`Warning`/`Error` with transient success toasts, keep errors
persistent, cap the bar (e.g. 25% height), make stderr scrollable, add Copy/Open log, and surface
`persistenceError`/watcher warnings. Test a 500-line error and a successful stage.

### UX-3 — Tabs, tooltips, and closing feedback

**Original:** E2, E12, E13, E22. **Status:** confirmed. **Severity:** P2/P3.

TabBar is a plain Row with no horizontal scroll/overflow; the close target is 16 dp; same-named
repositories are indistinguishable; long branch names are ellipsized with no tooltip; a tab draining
a write shows no “closing” state and repeat clicks appear to do nothing. Add horizontal scroll/overflow,
a larger hover target, full-path/full-branch tooltips, and a closing indicator. Test 15 tabs at
720 dp and a blocked write + close.

### UX-4 — Keyboard/accessibility corrections

**Original:** E14, E15, E23, F12. **Status:** mostly corrected. **Severity:** P3.

Compose 1.12.2 `clickable` already adds focusability and Enter/Space handling, so “keyboard-only
users cannot confirm” is too strong. Real gaps: custom buttons lack `Role.Button`, there is no focus trap,
initial focus is the dialog container, destructive dialogs do not default to Cancel, and there is no
shortcut reference. `E23` (click-then-arrow) is probably already fixed because `clickable`
requests focus; keep a cheap regression test. Add semantics/roles, focus containment, safe default focus, and Help →
Keyboard Shortcuts. Run a keyboard-only dialog test.

### UX-5 — Preferences, recents, labels, version, file actions

**Original:** E9, E10, E11, E17, E18, E19, E20, E21. **Status:** mostly confirmed; **P3**.
**Severity:** P3.

| Original | What is wrong | Fix / validation |
|---|---|---|
| **E9** | Preferences has only appearance/history; no reset/safety/startup options; fixed size. | Add reset + safety/startup sections; round-trip/reset test. |
| **E10** | Last folder-picker directory is in-memory only. | Persist in settings; restart test. |
| **E11** | Recents have no remove/clear and record invalid paths; overlaps WL-5. | Add remove/clear/prune; test bad path. |
| **E17** | “Discard staged” is really “Unstage”. | Rename; copy review. |
| **E18** | Changed-files count uses entries while staged+unstaged can exceed it. | Clarify wording; tooltip; test one file in both. |
| **E19** | No copy-path/open-file-manager/open-editor actions. | Add context actions; platform fallback test. |
| **E20** | Version hardcoded `1.0.0` while alpha. | Derive from Gradle; show commit for dev builds. |
| **E21** | Menu Exit does not explicitly save geometry. | Low value; `onDispose` likely covers it; add an explicit quit path only if reproduced. |

### UX-6 — Message log

**Original:** E16. **Status:** confirmed. **Severity:** P3.

The message log is in-memory, capacity 1000, starts at the oldest, and has no clear/filter/follow
tail or file persistence. Add Clear, level/repo filter, follow-tail, and optional rotated file
logging for writes/errors. Test 100 messages + restart.

## 8. Architecture and quality

### AR-1 — State ownership, god class, duplicated sources

**Original:** F1, F2, F3, F9. **Status:** confirmed; `RepoSession` is now ~1,430 lines and also owns
branch-filter resolution.

Define three state domains: per-tab repository view state (query, hidden refs, selection, scroll,
draft), global preferences (theme, layout, window, recents), and ephemeral UI state. Move the draft
there (WL-2). Extract `GraphLoader`, `StatusPoller`, `WriteQueue`, `DiffLoader`, and `TabStateStore`
from `RepoSession`; keep it as a coordinator. Remove duplicated sources: appearance parameters vs
settings controller, recent limit, `remoteBranchName()`, status filters. Memoize/derive expensive
values (PF-1/PF-2) instead of recomputing in composition. This is important after safety work, not
before it.

### AR-2 — Result and notification models

**Original:** F5, F6. **Status:** confirmed; root of WL-4/UX-2.

`GitResult.Ok(stdout)` cannot carry stderr/warnings; `UserMessage` has only Info/Error and
`RepoUiState.message` is last-error-wins. Introduce a command result with stdout/stderr/exit/
timedOut/truncated, and a notification model with Info/Warning/Error plus actions and persistent
history. Fold this into WL-4/UX-2 rather than treating as separate work.

### AR-3 — Persistence service

**Original:** F7, F8. **Status:** confirmed; root of PS-1/PS-2.

`JsonFileStore` is a good atomic write primitive but not a service. Add a single background writer
with a conflated channel and revisions, corrupt-file quarantine/backups, and an inter-process lock
(WL-5). Surface persistence errors. This is the shared fix for B1/B2/B3/B12 and part of A15.

### AR-4 — CI, lint, docs, accessibility, dependencies

**Original:** F10, F11, F12, F13, F14. **Status:** mixed.

- **F10 docs drift** is narrower than the audit: `docs/12`/`13` now document filters/performance, but
  `README`, `SPEC` R5, and the `docs/08` toolbar mock still claim unreachable ref hiding/query UI.
  `README` also promises author/message/date/path filtering, says branch/tag create/delete do not
  exist, and points at `docs/09` for performance targets now consolidated in `docs/13`.
- **F11 CI/lint** is real: no `.github`, no ktlint/detekt/spotless, duplicate imports, UI tests not
  run headlessly, daemon toolchain 21 vs build toolchain 25. The bench separation is intentional per
  `docs/13`, not a flaw.
- **F12 accessibility/localization** is broad pre-1.0 work: hardcoded English, small text, custom
  clickable rows without roles, 16 dp targets, no high-contrast/color-blind review, missing focus
  order. Fold into UX-4 and a pre-1.0 checklist.
- **F13 Material3 alpha** is a real dependency risk, but “pin a stable version” is not
  straightforward on the Compose 1.12 line (1.10–1.12 are alpha/beta; stable 1.9 exists). Treat as
  upgrade testing, not a one-line pin.
- **F14 version/packaging** overlaps E20 and is mostly an ops task (version derivation, notarization,
  entitlements); it is not a code defect in this audit.

## 9. Corrections to the original audit

| Original claim | Correction | Consequence |
|---|---|---|
| E1 Filters unreachable | Branch filters are now a real dialog; legacy query/ref hiding remains unreachable. | Split into “branch filters work” and “legacy filters trapped” (UX-1). |
| C1 unborn repo shows detached HEAD | False; `head()` returns the branch name for unborn repos. | The real defect is detached only; C1 standalone P2. |
| C8 untracked directories treated as files | Ordinary dirs are expanded by `-uall`; embedded repositories remain `dir/`. | Fix the embedded-repo case; don’t add a generic directory special case blindly. |
| E14 dialogs not keyboard-accessible | Compose `clickable` provides focus/Enter/Space; real gaps are roles/focus trap/safe default. | Downgrade to P3; test instead of rewriting all dialogs. |
| E23 click-then-arrow broken | Likely false; `clickable` requests focus. | Keep a regression test; no fix unless it fails. |
| E5, C10 separate issues | Duplicates of A2 (draft). | One fix, one test set. |
| C12 separate issue | Duplicate of C3 (upstream). | One fix. |
| A8 separate issue | Umbrella over A3/A9 recovery. | Implement trash/journal, not a separate feature. |
| B8 whole-app recomposition | Save is trailing-debounced; `App` receives stable params and should skip. | P3/validate, not a priority. |
| B7/D7 saves every 350 ms | Window saves are trailing-debounced; splitter/close writes remain but are small. | Frequency overstated. |
| E7 no fetch | Documented MVP non-goal in SPEC. | Scope decision, not a bug. |
| E21 menu Exit loses geometry | `onDispose` likely saves; unproven. | Low value; reproduce first. |
| B4 schema corruption | `schemaVersion` is unchecked, but only v1 exists. | Latent forward-compatibility debt. |
| B9 HiDPI | Unverified; units are self-consistent on macOS. | Hypothesis; needs platform matrix. |
| F13 pin stable Material3 | The Compose 1.12 line is alpha/beta-only. | Dependency risk, not a simple pin. |
| D5 unlimited diffs | Default path is 2 MB capped; only explicit Load full is unbounded. | Lower priority; cap the opt-in path. |
| D6 status per watcher event | Debounced 300 ms/2 s; not per file. | Measure before changing. |
| A5 P0 | Per-session writes can race, but Git’s lock prevents silent corruption; duplicate sessions/watchers are the real harm. | P1, still high value. |
| E6 P1 | No data loss; it corrupts anchors/QoL and violates T5. | P2. |
| Audit severity matrix | E5/E6/E14 listed P1 but sections say P2. | Use section-level severity above. |

## 10. Verified non-issues (do not “fix”)

1. **Unstaged rename discard** is correct: Git reports a deletion + untracked file; restore/delete
   works. (Verified in a temp repo.)
2. **Staged rename discard** correctly expands `origPath`; acceptance coverage exists.
3. **`--git-common-dir` from a subdirectory** resolves correctly.
4. **Untracked symlinks are not followed**; `readUntracked` checks symlink first.
5. **Discard confirmations exist** for unstaged actions; the problem is irreversible deletion, not
   the absence of a dialog.
6. **Window geometry restore** is well tested for missing/changed/oversized monitors; HiDPI remains
   separate (B9).
7. **Git environment hardening** is real: no inherited `GIT_DIR`/`GIT_WORK_TREE`, no shell,
   `GIT_TERMINAL_PROMPT=0`, literal pathspecs, `--no-pager`.
8. **Session/preferences split** is a real improvement; `AppSettingsController` is a coherent API.
9. **Parser fixtures** cover Unicode/newline paths, renames, binary, detached, annotated tags,
   symrefs, roots, octopus merges. Caveat: the detached fixture asserts only the hash, not a
   `detached` flag.
10. **One pre-commit kill experiment did not leave `index.lock`.** The lock window is narrow; it
    does not clear A1/A13.

Additional corrections: **unborn label is not wrong** (C1), and **ordinary untracked directories are
not treated as files** (C8). Do not implement fixes based on those two subclaims.

## 11. Recommended fix order

The audit’s P0-first instinct is right, but group by root cause so one design fixes several IDs.

### Phase A — Stop work loss (P0/P1)

1. **WL-2 Draft ownership + shortcut guard** (A2/A6/E5/C10). Cheapest high-frequency win.
2. **WL-4 Result model + detached HEAD + warnings** (A4/A7/C1/F4/F5). One model change unlocks checkout,
   push, hook, and conflict warnings.
3. **WL-1 Shutdown coordinator + timeout escalation** (A1/A13). Requires a process-level reproduction
   before claiming on-disk damage.
4. **WL-3 Trash/undo + per-path discard** (A3/A8; C8/C9 in GH-5). Supplies the recovery layer for A9.
5. **WL-5 Canonical worktree + instance lock + invalid-path probe** (A5/A14/A15; E11 in UX-5).

### Phase B — Prevent wrong-branch/orphan operations

6. **GH-1 Branch deletion reachability + `-d` fallback/recreate** (A9).
7. **GH-3 Abort/continue + resolved-conflict staging + combined diffs** (A11/C14).
8. **GH-2 Dirty-checkout confirmation** (A10).
9. **GH-4 Push remote/refspec/destination** (A12).

### Phase C — Persistence reliability

10. **PS-1 Corrupt persistence preservation + error UI** (B3/F7).
11. **PS-2 Single writer/debounce/revisions + tab-scoped scroll + closing marker** (B1/B2/B12/E6/F8).
12. **PS-3 Restore + merge startup argument** (B11); **PS-4 watcher callbacks** (B10).
13. **PS-5 legacy/schema/geometry** (B4/B5/B6/B9) after the above.

### Phase D — Measure, then optimize

14. **PF-1 partial-snapshot benchmark and hash index** (D2). Do not change code before the
    partial-load replay exists.
15. **PF-2 large-status/list benchmark, then lazy lists/caps** (D1/C13/F9).
16. **PF-3 wide-lane benchmark** (D4); **PF-4 measure snapshots/opt-in loads/status bursts**
    (D3/D5/D6).

### Phase E — Workflow and quick correctness

17. **GH-5 correctness bundle** (C2–C9, C11; C13 is in PF-2, E17/E18 in UX-5).
18. **UX-1 legacy filters escape hatch**; **UX-2 feedback/error bar**; **UX-3 tabs/tooltips/closing**.
19. **UX-4 accessibility**; **UX-5 preferences/recents/labels/version**; **UX-6 message log**.

### Phase F — Architecture and CI

20. **AR-1 state domains/RepoSession split/duplicate sources**; **AR-2 result/notification models**
    (with WL-4/UX-2); **AR-3 persistence service** (with PS-1/PS-2); **AR-4 CI/lint/docs/deps**.

## 12. Validation matrix

| Workstream | Minimum evidence before closing |
|---|---|
| WL-1 | Latched write + close confirmation; process kill during checkout/pull; timeout progress/cancel. |
| WL-2 | Draft survives tab/selection/failure/restart; `Cmd/Ctrl+W` focus guard + confirmation. |
| WL-3 | Trash/restore; mixed tracked/untracked; nested repo; partial discard summary. |
| WL-4 | Detached/unborn fixtures; detached banner; real “leaving commits” warning; hook stderr visible. |
| WL-5 | Root/subdir/symlink dedupe; worktree write lock; invalid path; two instances. |
| GH-1 | Remote/tag/other-local reachability; `-d` failure fallback; recreate. |
| GH-2 | Dirty checkout confirmation; cross-branch commit prevention. |
| GH-3 | Merge abort/continue; rebase/CP/revert actions; stage resolved conflict; combined diff. |
| GH-4 | Non-origin remote; `push.default=matching`; explicit refspec; destination UI. |
| PS-1 | Corrupt JSON preserved + visible error; backup; read-only dir. |
| PS-2 | 100 rows → ≤2 saves; latch ordering; close/quit; two-tab anchors. |
| PS-3 | Three tabs + startup path → four tabs persist. |
| PS-4 | Runtime watcher failure → polling/warning; cap warning. |
| PF-1 | Partial-snapshot bench; cumulative map work bounded; no UI-thread stall. |
| PF-2 | 5k-row UI test; 20k-untracked status bench; cap/lazy behavior. |
| UX-1 | Legacy `query`/`hiddenRefs` visible/clearable. |
| UX-2 | Success toast; 500-line error bar capped. |
| GH-5 | Table tests for labels/upstream/CRLF/reselect/spinners; embedded repo; partial discard. |
| UX-3/4/5/6 | Tab overflow; focus trap/roles; prefs reset; log clear/filter. |

## 13. Appendix — revalidation experiments and limits

Safe experiments in temporary repositories confirmed:

- Detached checkout prints “leaving N commits behind” on **stderr with exit 0**; the app drops it.
- `git checkout other` with ` M f.txt` exits 0, prints `M\tf.txt` on stdout, and carries the change.
- `git push.default=matching` with two branches makes bare `git push` push both.
- A combined conflict diff uses `@@@ ... @@@`; the parser leaves it as file headers, so the view
  shows “No textual changes”.
- `git status --porcelain=v2 -z --untracked-files=all` reports a nested repository as `? embedded/`;
  ordinary untracked directories are expanded into files.
- A branch merged only into another local branch is classified safe by the app, but `git branch -d`
  refuses (“not fully merged”).
- Killing `git commit` during a short pre-commit hook did not leave `index.lock` in one run; this
  does not clear the shutdown/timeout risk, which needs checkout/pull and process-level tests.
- Unborn status is `# branch.oid (initial)` / `# branch.head main`; `head()` returns `main`, so the
  audit’s “unborn shows detached HEAD” claim is false.

### ID map (original → workstream)

| Original | Workstream |
|---|---|
| A1, A13 | WL-1 |
| A2, A6, E5, C10 | WL-2 |
| A3, A8 | WL-3 |
| A4, A7, C1, F4, F5 | WL-4 |
| A5, A14, A15 | WL-5 |
| A9 | GH-1 |
| A10 | GH-2 |
| A11, C14 | GH-3 |
| A12 | GH-4 |
| C2–C9, C11 | GH-5 |
| E7, E8 | GH-6 |
| B3, F7 | PS-1 |
| B1, B2, B12, E6, F8 | PS-2 |
| B11 | PS-3 |
| B10 | PS-4 |
| B4, B5, B6, B9 | PS-5 |
| B7, B8, D7 | PS-6 |
| D2 | PF-1 |
| D1, C13, F9 | PF-2 |
| D4 | PF-3 |
| D3, D5, D6 | PF-4 |
| E1 | UX-1 |
| E3, E4, F6 | UX-2 |
| E2, E12, E13, E22 | UX-3 |
| E14, E15, E23, F12 | UX-4 |
| E9, E10, E11, E17–E21 | UX-5 |
| E16 | UX-6 |
| F1, F2, F3, F9 | AR-1 |
| F5, F6 | AR-2 (with WL-4/UX-2) |
| F7, F8 | AR-3 (with PS-1/PS-2) |
| F10–F14 | AR-4 |

**Limits.** This revalidation was static plus safe Git experiments. It did not run Gradle tests or
benchmarks, did not reproduce on-disk damage for A1/A13, did not reproduce the B2 race, and did not
test HiDPI or click-then-arrow on a real window. Treat hypotheses accordingly. The document records
observations at `d22626f`; re-check line references before fixing.

**Use this document as the backlog.** Do not implement fixes from the original audit’s false/outdated
subclaims (unborn label, ordinary untracked directories, E14 as a keyboard blocker, E23, B8/D7
frequency, E7 as a bug).
