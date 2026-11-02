# 12 — Filters

Parent: [SPEC.md](SPEC.md). Related: [04-graph.md](04-graph.md), [05-query.md](05-query.md),
[07-sessions.md](07-sessions.md), [08-ui.md](08-ui.md), [09-testing.md](09-testing.md).

## Purpose

Filters decide which **branches** are visible, using inclusion and exclusion rules. A rule matches
**commits** by author, message, or date; matching commits are attributed to their nearest branch
ahead in history. By default the result shows whole branches with all their work behind them, not
isolated commits.

Primary use case: show only the branches I am working on and hide branches owned by teammates that I
have not contributed to.

The UI reuses the branch-picker modal language so the feature feels native to FreeTree.

### Non-goals (baseline)

- No file/path rule field.
- No regex rules.
- No commit-body matching (subject only).
- No network/fetch changes.
- No replacement of hidden refs or the existing query internals; filters are an additional layer.

## Glossary

| Term | Meaning |
|---|---|
| Rule | `field + value` pair. Fields: Author, Message, Date. |
| Include group | Rules that decide which branches are shown. |
| Exclude group | Rules that decide which branches are hidden. |
| Candidate branch | A displayable branch eligible for rule mapping. Local and remote branches are individual refs; tags are not branches. |
| Nearest branch | For a commit, the branch tip with the shortest descendant path from that commit. |
| Branch mode | Default: rules select whole branches with full history. |
| Commit mode | Optional later phase: rules select individual commits in a flat list. |

## Rule model

```kotlin
@Serializable
data class FilterRule(
    val field: FilterField,
    val text: String = "",
    val after: String? = null,   // ISO yyyy-MM-dd, DATE only
    val before: String? = null,  // ISO yyyy-MM-dd, DATE only
)

enum class FilterField { AUTHOR, MESSAGE, DATE }

enum class RuleMode { ANY, ALL }

@Serializable
data class BranchFilter(
    val includes: List<FilterRule> = emptyList(),
    val excludes: List<FilterRule> = emptyList(),
    val includeMode: RuleMode = RuleMode.ANY,
    val excludeMode: RuleMode = RuleMode.ANY,
    val commitMode: Boolean = false, // phase 3
)
```

### Field semantics

| Field | Matches | Case | Notes |
|---|---|---|---|
| Author | commit author **name or email** | insensitive | substring, trimmed |
| Message | commit **subject** only | insensitive | substring, trimmed |
| Date | commit **author date** | — | day precision, local timezone |

- `RawCommit.authorEmail` is added and parsed from `%ae`.
- Empty text/date values are invalid and block Apply.
- Dates use `yyyy-MM-dd`. `after` and `before` are optional and may be used alone.
- **Strict boundaries:** `after 2024-01-01` means 2024-01-02 00:00 onward; `before 2024-01-01`
  means before 2024-01-01 00:00. If both are set, `after < before` is required.

### Group combination

- `ANY`: a commit matches if any rule in the group matches.
- `ALL`: a commit matches only if every rule in the group matches.
- Default for both groups: `ANY` (include/exclude pattern semantics).
- Examples:
  - Includes `[Author = me]`, `[Author = teammate]`, ANY → either author.
  - Includes `[Author = me]`, `[Message = WIP]`, ALL → my commits mentioning WIP.
  - Excludes `[Author = teammate]`, ANY → hide branches owned by that teammate.

## Branch-mode semantics

### Candidate branches

1. Start from refs of enabled kinds (locals always; remotes and tags follow existing toggles).
2. Remove `hiddenRefs`.
3. Keep local and remote branches as individual refs.
4. Tags are not candidates and never participate in nearest-branch mapping.

An empty filter has no effect.

### Nearest branch

For a commit `c`, consider every candidate branch whose tip is a descendant of `c`. The nearest
branch is the candidate tip with the fewest parent→child edges from `c`.

Deterministic tie-break:

1. current branch,
2. local branch before remote,
3. branch short-name lexicographic.

Tips have distance 0. Commits reachable only from tags or a detached HEAD have no branch mapping and
are ignored by branch-mode rules.

### Visibility

```text
includeBranches =
    if includes is empty -> candidate branches
    else -> nearest branches of commits matching the include group

excludeBranches = nearest branches of commits matching the exclude group

visibleBranches = includeBranches - excludeBranches
```

- Exclusions always win.
- Empty includes + non-empty excludes → all candidates except excluded.
- The default display is the full history of every visible branch (`git log <visible refs>`).
- Important branches (phase 2 fast scan) are always visible and unfiltered.

### Example

Rule `Message = "add discard all to unstaged changes section"` matches a commit nearest to
`polish/ui-issues`.

- Include → `polish/ui-issues` is visible with full history.
- Exclude → `polish/ui-issues` is hidden; `main` remains visible because it is not the nearest
  branch for that commit.
- If HEAD is on the hidden branch, its chip disappears and `showHeadHighlight` draws the `HEAD` chip.

## Nearest-branch algorithm

Commits stream in `git log --topo-order` (children before parents), allowing a single-pass
equivalent of a multi-source BFS from all branch tips:

1. Seed each candidate tip at distance 0.
2. For each commit, combine its own seeding with candidates propagated from its children
   (`child distance + 1`).
3. Keep only the minimum-distance candidate set.
4. Pick the winner with the tie-break.
5. Propagate the minimum-distance set to each parent at `distance + 1`.

This is `O(commits + edges)` and stores only the current frontier plus matched branch sets.

## Commit-mode semantics (phase 3)

Independent of branch mode:

- Rules apply directly to commits.
- Includes show only matching commits; excludes hide matching commits.
- No nearest-branch mapping.
- Flat rendering (one column), because intermediate commits may be removed.
- Branch chips appear only on visible commits; HEAD chip logic is unchanged.
- Important branches are not special in this mode.

## Architecture and scan modes

The engine must not know how commits are obtained. This is the extension point for performance work.

```kotlin
interface BranchScan {
    fun plan(request: BranchFilterRequest): ScanPlan
}

data class BranchFilterRequest(
    val filter: BranchFilter,
    val refs: List<GitRef>,
    val hiddenRefs: Set<String>,
    val showRemotes: Boolean,
    val importantBranches: Set<String> = emptySet(),
    val scanMode: ScanMode = ScanMode.FULL,
)

data class ScanPlan(
    val candidates: List<GitRef>,
    val seeds: List<GitRef>,
    val alwaysVisible: List<GitRef>,
    val scanRevs: List<String>,
    val excludeRevs: List<String>,
)

data class BranchFilterResult(
    val visibleRefs: List<GitRef>,
    val matchedCommits: Set<String>,
    val stats: FilterStats,
)

data class FilterStats(
    val scannedCommits: Int,
    val candidateBranches: Int,
    val includeBranches: Int,
    val excludeBranches: Int,
    val durationMillis: Long,
)
```

- `FullScan` (baseline): all candidate branches, all reachable commits.
- `UnmergedWorkScan` (phase 2, opt-in): see below.
- `FilterStats` exists from the baseline for diagnostics and tests.

### Full scan

- Candidates = all displayed branches.
- Stream all commits reachable from candidates.
- Nearest mapping within candidates.

### Unmerged-work scan (phase 2, opt-in)

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

Implementation notes for phase 2:

- Batch merged detection with `git for-each-ref --merged=<important>`.
- Use `LogSpec.excludeRevs` (written as `^ref` to `git log --stdin`).
- Detect the repository default branch from `refs/remotes/origin/HEAD` / `symbolic-ref`, cached per
  session.
- If no important branch exists, fall back to full scan.

### Future optimizations enabled by the API

- Cache `visibleRefs` by `(filter, refs digest, head, toggles, scanMode)`.
- Cache the nearest-branch index per refs snapshot (independent of rules).
- Push predicates to `git log --author/--grep/--since/--until` inside the scan range.
- Parallelize branch walks.
- Add a `RandomDag` benchmark (≈200k commits).

## Session and persistence

- `RepoUiState` adds `branchFilter`, `filterBusy`, `filterError`, and the resolved visible ref set
  used for labels and the toolbar badge.
- `TabState` adds `branchFilter: BranchFilter = BranchFilter()`; filters persist per tab across
  restarts. Old session files load because every new field has a default.
- `RepoSession.setBranchFilter(filter)` validates, updates state, and starts a cancellable
  resolution with a sequence id; stale results are discarded.
- No rules → filter inactive; skip resolution and keep current behavior.
- After resolution, the graph reloads with the visible refs.
- If resolution fails, keep the previous graph and surface the error; never lose the current view.
- Re-resolve when refs/head/toggles change while a filter is active.
- Selection is preserved by commit hash where possible; otherwise it falls back to the working tree.

### Graph spec and labels

- `LogSpec.revs` = visible branch full names (+ HEAD per `showHead`).
- `visibleLabels()` uses the same visible set; hidden branches must not leak chips.
- While a filter is active, tags annotate visible commits but do not add git revs. With no filter,
  current behavior remains.
- `showHead` keeps HEAD and its history even if its branch is filtered out. The HEAD chip appears
  because labels exclude hidden branches.
- The working tree row is always displayed.

## Filters dialog

### Entry point

- The toolbar **Filters** button opens the modal (replacing the info dialog).
- The active rule count is shown as a badge.

### Layout

Reuse the branch-picker modal shell: scrim, close button, Escape dismissal, adaptive large size
(≈80% width / 80% height, with a max width). Two clearly separated cards:

```text
┌ Filters ─────────────────────────────────────────────────── × ┐
│ Include branches                       Exclude branches       │
│ ┌────────────────────────────┐  ┌────────────────────────────┐ │
│ │ [Author ▾] [value…]     ×  │  │ [Message ▾] [value…]    ×  │ │
│ │ [Message ▾] [value…]    ×  │  │ [Author ▾] [value…]     ×  │ │
│ │ + Add include              │  │ + Add exclude              │ │
│ └────────────────────────────┘  └────────────────────────────┘ │
│ [ ] Filter commits instead of branches                         │
│                                Clear all   Cancel    Apply    │
└────────────────────────────────────────────────────────────────┘
```

- Include card has a subtle green accent; exclude card a subtle red accent.
- Each card scrolls independently with the existing scrollbar/gutter.
- Cards stack vertically on narrow windows.
- Empty states: “No includes — all branches are visible.” / “No excludes — nothing is hidden.”

### Rule rows

- Field selector: compact dropdown `Author ▾` / `Message ▾` / `Date ▾`.
- Author/Message: single text input with a field-specific placeholder.
- Date: `After [yyyy-MM-dd]  Before [yyyy-MM-dd]` (both optional; taller row).
- Remove `×` per row.
- `+ Add include` / `+ Add exclude` appends a rule and focuses it.

### Apply behavior

- The dialog owns a draft; **Apply** commits, **Cancel** discards, **Clear all** empties both groups.
- Reopening always shows the applied state.
- Inline validation: empty rule value, invalid date, `after >= before`; Apply disabled.
- After Apply, close the dialog and let the graph reload.
- Optional: show “N branches match” after resolution.

### Important branches setting (phase 2)

- New global Preferences setting: a list of important branch names, default `main`, `master`.
- Simple list editor with add/remove, validation, and duplicate handling.
- Consumed by the fast scan. Not shown in the baseline Filters dialog.

## Testing

### Core (pure)

- Author name/email matching, case-insensitivity, trimming.
- Message subject matching.
- Date strict boundaries, open-ended sides, invalid ranges.
- Any/All composition.
- Nearest-branch mapping: divergence, merges, shared ancestors, ties, tip at distance 0.
- Include/exclude precedence.
- Resolver with a synthetic `Flow<RawCommit>` and synthetic refs; no git required.

### Session

- Applying a filter yields the expected `LogSpec.revs`.
- `labelsByCommit` excludes hidden branches.
- HEAD chip appears when its branch is filtered out.
- Per-tab persistence and restore.
- Stale resolution cancellation.
- Selection fallback.

### Real git acceptance

- Repo with multiple branches, authors, dates, and merges.
- Include/exclude by author, message, date.
- Visible graph and chips match.
- HEAD chip case.
- Tags annotate-only while a filter is active.

### UI

- Open Filters from the toolbar; add/remove rules; field selector; date validation.
- Apply/Cancel/Clear; reopen shows the applied state.
- Any/All toggles.
- Per-tab state; toolbar badge.
- Screenshots: filters dialog, filtered history, HEAD chip, empty states.

`FakeGitBackend.logStream` ignores `LogSpec` filtering, so session/UI tests assert the requested
spec; semantic tests use the pure resolver or a real `GitTestRepo`.

### Performance (phase 2+)

- `RandomDag` benchmark for full and unmerged-work scans.
- Verify the unmerged scan does not walk important-branch history.

## Phases and steps

### Phase 0 — Plan

- [x] Add this document and the feature branch.

### Phase 1 — Baseline

**Core**

- [ ] Add `authorEmail` to `RawCommit`, `LOG_FORMAT`, parser, fake backend, scenarios, and tests.
- [ ] Add `BranchFilter`, `FilterRule`, field/mode enums, validation.
- [ ] Add the rule matcher (author name/email, subject, strict date range).
- [ ] Add `NearestBranchTracker` (single-pass BFS, tie-break).
- [ ] Add `BranchFilterEngine` + `FullScan` + `FilterStats`.
- [ ] Add pure tests for matcher, tracker, and engine.

**Session**

- [ ] Add filter state to `RepoUiState` and `TabState`; persist per tab.
- [ ] Resolve on apply, persistence restore, and refs/head/toggle changes.
- [ ] Use visible refs for `LogSpec` and `visibleLabels`.
- [ ] Tags annotate-only while filtering; HEAD behavior; selection fallback.
- [ ] Add session tests.

**UI**

- [ ] Replace the Filters info dialog with the two-pane modal.
- [ ] Rule rows with field selector, date inputs, Any/All, validation.
- [ ] Apply/Cancel/Clear, toolbar badge, per-tab state.
- [ ] Add UI tests and screenshots.

**Docs**

- [ ] Link this document from `SPEC.md` and the related docs.

### Phase 2 — Fast scan + important branches

- [ ] Preferences important-branches editor (defaults `main`, `master`).
- [ ] `UnmergedWorkScan`, `LogSpec.excludeRevs`, merged-branch detection.
- [ ] Advanced toggle in the Filters dialog.
- [ ] Benchmark and caching hooks.

### Phase 3 — Commit mode

- [ ] Matched-commit visibility, flat-mode reload, `--no-walk` backend support.
- [ ] Checkbox wiring and tests.

### Phase 4 — Performance polish

- [ ] Nearest-branch index cache.
- [ ] Git predicate pushdown.
- [ ] Parallel scans.

## Applied decisions

- Baseline is branch mode only; commit mode is phase 3.
- Filters persist per tab across restarts.
- Important-branches setting ships with phase 2.
- Important names match local and remote logical names.
- Author rules match name or email; message matches the subject; dates use author date, local
  timezone, strict before/after.
- Blank or invalid rules block Apply.
- Document path is `docs/12-filters.md`.
