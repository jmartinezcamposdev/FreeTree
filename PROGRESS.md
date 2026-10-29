# Progress

## Phase 0 — Bootstrap

- [x] Migrate to core + app
- [x] Contracts
- [x] Empty window
- [x] Java 25 toolchain
- [x] `PROGRESS.md`, `DECISIONS.md`, and `README.md`

### Command log

All Gradle commands below were run with `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-zulu` and that
JDK's `bin` directory first on `PATH`.

| Command | Result |
|---|---|
| `java -version` (system shell) | No system Java runtime found. |
| `java -version` with `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-zulu` | `openjdk version "25.0.1"` (Azul Zulu 25.30+17-CA). |
| `./gradlew build` (first attempt) | **FAILED**: Kotlin required explicit return types for `TODO()` expression bodies in `Graph.kt` and `SessionManager.kt`. Fixed by declaring `Unit` return types. |
| `./gradlew build` | **BUILD SUCCESSFUL** in 3s; 11 actionable tasks. |
| `./gradlew :core:test --rerun-tasks` | **BUILD SUCCESSFUL** in 2s; 5 actionable tasks. Test XML: `tests="2" skipped="0" failures="0" errors="0"`. |
| `./gradlew :app:run` (first attempt) | **FAILED**: `UnsupportedClassVersionError` — app classes were version 69 (Java 25), but Compose launched the Gradle daemon's Java 21 runtime. Fixed by setting `application.javaHome` from the Java 25 toolchain. No JDK downgrade. |
| `./gradlew build` (after run fix) | **BUILD SUCCESSFUL** in 1s; all tasks up to date. |
| `./gradlew :app:run` | App process ran for more than 8s under `~/.sdkman/candidates/java/25.0.1-zulu/bin/java`; only Skiko native-access warnings. Stopped cleanly with `APP_PROCESS_STOPPED`. |
| `./gradlew clean build :core:test` (final gate) | **BUILD SUCCESSFUL** in 1s; 13 actionable tasks (4 from cache). |
| `./gradlew :core:test --rerun-tasks` (final gate) | **BUILD SUCCESSFUL** in 4s; 5 actionable tasks executed. Test XML: `tests="2" skipped="0" failures="0" errors="0"`. |
| `./gradlew :app:run` (final gate) | `APP_RUNNING_AFTER_8S`; launched with Java 25; `> Task :app:run`; only Skiko native-access warnings. Terminated with `APP_PROCESS_STOPPED`. |
| `./gradlew clean build :core:test --rerun-tasks --no-build-cache` (final uncached gate) | **BUILD SUCCESSFUL** in 2s; 13 actionable tasks executed. Test XML: `tests="2" skipped="0" failures="0" errors="0"`. |
| `./gradlew :app:run` (after uncached gate) | `APP_RUNNING_AFTER_8S`; `> Task :app:run`; terminated with `APP_PROCESS_STOPPED`. |
| `./gradlew :core:dependencies --configuration compileClasspath` | Classpath contains Kotlin stdlib, coroutines, and serialization only; no Compose artifacts. |

### Final verification

- `./gradlew clean build :core:test --rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL in 2s`;
  13 of 13 tasks executed.
- `./gradlew :core:test` → `ContractsTest`: 2 tests, 0 failures, 0 errors.
- `./gradlew :app:run` → app stayed alive for more than 8 seconds, then was stopped; no startup
  error. The visual window check remains manual by design.
- Java 25 and Compose Multiplatform 1.12.2 are compatible; no fallback JDK was used.
- `temp/` was not modified; it still contains only the two original specification files.
- Phase 1 began only after this Phase 0 gate was green.

## Phase 1 — Core

### Workstreams and ownership

Four subagents were used, one per workstream, which was optimal because each owns disjoint paths and
can be verified and merged independently. QA was run first to unblock SESSION; QA was reused for
the independent acceptance layer.

| Workstream | Subagent | Owned paths | Result |
|---|---|---|---|
| QA | 1 | `core/src/testFixtures/**`, `core/src/test/**/qa/**`, `core/src/test/**/acceptance/**` | Fixtures first (29 tests), then acceptance and generators (30 tests) |
| GIT | 2 | `core/git/**`, `core/watch/**`, their tests and goldens | 93 workstream tests green |
| GRAPH | 3 | `core/graph/**`, `core/query/**`, their tests | 42 workstream tests green |
| SESSION | 4 | `core/session/**`, its tests | 26 workstream tests green |

### GIT

- [x] `GitRunner`, Git discovery/version check, timeouts, cancellation, descendant cleanup
- [x] `CliGitBackend` for refs, HEAD, streaming log, details, files, all diff targets, status
- [x] Stage, unstage, discard staged/unstaged, commit, push, pull, Git dirs
- [x] Parsers for refs, log, porcelain v2, name-status, commit detail, diff, empty tree
- [x] Watcher paths, `EventClassifier`, `Debouncer`, JDK `WatchService` watcher
- Evidence: `./gradlew :core:test --rerun-tasks` → `BUILD SUCCESSFUL`; 95 tests total on the branch,
  93 GIT/watch tests plus the 2 existing contract tests, 0 failures/errors. `:core:build` green.

### GRAPH

- [x] Incremental `LaneLayoutEngine` and flat mode
- [x] Chunked immutable `GraphSnapshot`, `RowChunk`, `SegmentCodec`, `SegmentsView`
- [x] I1–I7, merges, octopus, missing parents, many lanes, one-shot vs batch
- [x] `parseQuery`, `resolve`, glob matching, ref/HEAD toggles, invalid-query handling
- Evidence: `./gradlew :core:test --rerun-tasks` → `BUILD SUCCESSFUL`; 164 tests total on the branch,
  42 graph/query tests, 0 failures/errors. `:core:build` green.

### QA

- [x] `FakeGitBackend`, `FakeWatcher`, `RandomDag`, `GitTestRepo`
- [x] Fixture tests: 27 tests, plus 2 contracts = 29 on the first QA branch
- [x] Independent acceptance tests against real Git: log/refs, files/status, writes, discard matrix,
  push/pull, query round-trips (30 new tests)
- [x] `SyntheticDagGenerator` (50k) and `PerfRepoBuilder` (200k via `git fast-import`, cached)
- Evidence: `./gradlew :core:testFixturesClasses :core:test --rerun-tasks` → `BUILD SUCCESSFUL`,
  29 tests; `./gradlew :core:test --rerun-tasks` after acceptance → `BUILD SUCCESSFUL`, 194 tests.

### SESSION

- [x] `RepoSession` ACTIVE/INACTIVE/DRAINING lifecycle, read/write scopes, `Mutex` writes
- [x] Graph loading, partial publishing, atomic refresh, query application, selection/diff loading
- [x] `SessionManager` tabs, single active session, duplicate detection, close/drain behavior
- [x] Watcher reaction, focused polling fallback, late-result rejection
- [x] JSON persistence with atomic replace, platform path, restore active tab only
- [x] T1–T7 tests with fakes and virtual time
- Evidence: `./gradlew :core:test --rerun-tasks` → `BUILD SUCCESSFUL`; 190 tests total on the branch,
  26 session tests, 0 failures/errors. `:core:build` green.

### Integration and final gate

- [x] Merged `phase1/qa` and `phase1/git`
- [x] Merged `phase1/graph`
- [x] Merged `phase1/session` and the QA acceptance branch
- [x] `./gradlew clean build :core:test --rerun-tasks --no-build-cache`
- [x] `./gradlew :core:test` and `./gradlew build` run by the main agent after integration

### Phase 1 command log

All Gradle commands were run with `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-zulu` and that JDK's
`bin` directory first on `PATH`.

| Command / action | Result |
|---|---|
| Worktree setup: `phase1-qa`, `phase1-git`, then `phase1-graph`, `phase1-session` from integrated `master` | Four isolated worktrees; branches created |
| `phase1-qa`: `./gradlew :core:testFixturesClasses :core:test --rerun-tasks` | **BUILD SUCCESSFUL**; 29 tests, 0 failures/errors |
| `phase1-git`: `./gradlew :core:test --rerun-tasks` | **BUILD SUCCESSFUL**; 95 tests, 93 GIT/watch, 0 failures/errors |
| `phase1-git`: `./gradlew :core:build --rerun-tasks` | **BUILD SUCCESSFUL** |
| Main: `git merge --no-ff phase1/qa` | Merged, no conflicts |
| Main: `git merge --no-ff phase1/git` | Merged, no conflicts |
| Main: `./gradlew :core:test --rerun-tasks` after QA + GIT integration | **BUILD SUCCESSFUL** in 37s |
| `phase1-graph`: `./gradlew :core:test --rerun-tasks` | **BUILD SUCCESSFUL**; 164 tests, 42 graph/query, 0 failures/errors |
| `phase1-graph`: `./gradlew :core:build --rerun-tasks` | **BUILD SUCCESSFUL** |
| Main: `git merge --no-ff phase1/graph` | Merged, no conflicts |
| `phase1-session`: `./gradlew :core:test --rerun-tasks` | **BUILD SUCCESSFUL**; 190 tests, 26 session, 0 failures/errors |
| `phase1-session`: `./gradlew :core:build --rerun-tasks` | **BUILD SUCCESSFUL** |
| `phase1-qa` acceptance: `./gradlew :core:test --rerun-tasks` | **BUILD SUCCESSFUL**; 194 tests, 30 new acceptance/generator tests |
| Main: `git merge --no-ff phase1/session` | Merged, no conflicts |
| Main: `git merge --no-ff phase1/qa` (acceptance) | Merged, no conflicts |
| Main final gate: `./gradlew clean build :core:test --rerun-tasks --no-build-cache` | **BUILD SUCCESSFUL in 48s**; 15 actionable tasks executed |
| Main exact gate: `./gradlew :core:test` | **BUILD SUCCESSFUL in 472ms**; 7 tasks up-to-date; exit 0 |
| Main exact gate: `./gradlew build` | **BUILD SUCCESSFUL in 743ms**; 13 tasks up-to-date; exit 0 |
| Main quality gate: `./gradlew check` | **BUILD SUCCESSFUL in 643ms**; 13 tasks up-to-date |
| Integrated test report | 28 suites, **220 tests, 0 failures, 0 errors** |
| `grep` quality scans | No `TODO(`/`FIXME`/`NotImplementedError`; no Compose/AWT/Swing imports in `core`; no empty `catch` blocks |
| Worktree cleanup | Four merged worktrees removed; `phase1/*` branches retained |

### Phase 1 final verification

- `./gradlew clean build :core:test --rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL`; all 15
  tasks executed; 220 tests, 0 failures, 0 errors.
- `./gradlew :core:test` → `BUILD SUCCESSFUL`.
- `./gradlew build` → `BUILD SUCCESSFUL`.
- `./gradlew check` → `BUILD SUCCESSFUL`.
- `T1`–`T7` covered by SESSION tests with fakes and virtual time.
- No new dependencies were added in Phase 1; no shell/JGit/libgit2; `core` remains free of Compose/AWT/Swing.
- `temp/` was not modified. Phase 2 began only after this Phase 1 gate was green.

## Phase 2 — Session/UI

### Workstreams and ownership

Three subagents were used, one per Phase 2 workstream. That was optimal because SESSION and UI have
disjoint code paths and could run in parallel, while QA depended on the merged UI and therefore ran
last against the integrated app.

| Workstream | Subagent | Owned paths | Result |
|---|---|---|---|
| SESSION | 1 | `core/session/**`, its tests | 44 session tests green; `openDiff(..., unlimited)` added |
| UI | 2 | `app/src/main/**`, `app/src/test/**/ui/**` | Full fake-backed app shell, 17 UI tests, screenshots-ready test tags |
| QA | 3 | `core/src/testFixtures/**`, `app/src/test/**/acceptance/**`, `app/src/test/**/smoke/**` | 7 independent T1–T7 checks, 9 smoke tests, 10 screenshots |

### SESSION

- [x] Active session/tab coherence during open, activate, close, drain, restore
- [x] Selection/detail/changed-file loading with debounce, cancellation, late-result rejection
- [x] `openDiff(target, unlimited = false)` for the UI “load full” path
- [x] Busy/write pipeline, conflict exclusion, status/refs refresh after writes
- [x] Persistence, duplicate/draining path handling, neighbor activation
- Evidence: `./gradlew :core:test --rerun-tasks` → **BUILD SUCCESSFUL**; 238 core tests,
  44 SESSION tests (`RepoSessionTest` 23, `SessionManagerTest` 21), 0 failures/errors.

### UI

- [x] `App(sessionManager, onOpenRepository, loadFullDiff)` shell, TabBar, Toolbar, FilterBar/RefsPanel
- [x] GraphList/GraphCell/RefChips: fixed-height rows, item 0 Changes, per-row Canvas, merge/HEAD/refs
- [x] CommitPanel, ChangesPanel, DiffView, discard confirmation, commit enablement, keyboard navigation
- [x] `GraphStyle.kt` contains all visual constants; `UiTestTags` exposes stable smoke-test selectors
- [x] `Main.kt` uses an app-local deterministic `DemoGitBackend`/`DemoWatcher`; no CliGitBackend,
  JdkRepoWatcher, ProcessBuilder, or real Git in the app
- Evidence: `./gradlew :app:test --rerun-tasks` → **BUILD SUCCESSFUL**; 17 UI tests, 0 failures.
  `./gradlew :app:build` green. Manual `:app:run` stayed alive without startup error.

### QA

- [x] Opt-in stateful `FakeGitBackend` writes and deterministic `FakeRepoScenarios`
- [x] Independent T1–T7 acceptance checks in `app/src/test/**/acceptance/**`
- [x] Nine end-to-end smoke tests covering R1–R5/R7/R9 paths
- [x] Ten PNG screenshots generated by the smoke tests in `app/build/screenshots/`
- Evidence: `./gradlew :app:test --rerun-tasks` → **BUILD SUCCESSFUL**; 33 app tests
  (17 UI + 7 acceptance + 9 smoke), 0 failures. `:core:test` 238 tests green; `check`/`build` green.

### Integration and final gate

- [x] Merged `phase2/session`
- [x] Merged `phase2/ui`
- [x] Wired the UI default “load full” action to `RepoSession.openDiff(target, unlimited = true)`
- [x] Merged `phase2/qa`
- [x] `./gradlew clean :core:test check build --rerun-tasks --no-build-cache`
- [x] Exact `./gradlew :core:test`, `./gradlew check`, and `./gradlew build` run by the main agent

### Phase 2 command log

All Gradle commands were run with `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-zulu` and that JDK's
`bin` directory first on `PATH`.

| Command / action | Result |
|---|---|
| Worktrees `phase2-session`, `phase2-ui` from `master`; `phase2-qa` after UI/SESSION integration | Three isolated branches |
| `phase2-session`: `./gradlew :core:test --rerun-tasks` | **BUILD SUCCESSFUL** in 51s; 238 tests, 44 SESSION, 0 failures |
| `phase2-session`: `./gradlew :core:build --rerun-tasks` | **BUILD SUCCESSFUL** |
| `phase2-ui`: `./gradlew :app:test --rerun-tasks` | **BUILD SUCCESSFUL**; 17 UI tests, 0 failures |
| `phase2-ui`: `./gradlew :app:build --rerun-tasks` | **BUILD SUCCESSFUL** |
| Main: `git merge --no-ff phase2/session` | Merged, no conflicts |
| Main: `git merge --no-ff phase2/ui` | Merged, no conflicts |
| Main: `./gradlew :core:test :app:test --rerun-tasks` after SESSION + UI | **BUILD SUCCESSFUL**; 238 core + 17 app tests |
| Main: load-full integration fix | `App` default now calls `openDiff(target, unlimited = true)` |
| `phase2-qa`: `./gradlew :app:test :core:test --rerun-tasks` | **BUILD SUCCESSFUL** in 53s; 33 app + 238 core tests |
| Main: `git merge --no-ff phase2/qa` | Merged, no conflicts |
| Main final gate: `./gradlew clean :core:test check build --rerun-tasks --no-build-cache` | **BUILD SUCCESSFUL in 53s**; 17 actionable tasks executed |
| Main exact gate: `./gradlew :core:test` | **BUILD SUCCESSFUL**; exit 0 |
| Main exact gate: `./gradlew check` | **BUILD SUCCESSFUL**; exit 0 |
| Main exact gate: `./gradlew build` | **BUILD SUCCESSFUL**; exit 0 |
| Final test report | 238 core tests + 33 app tests, **0 failures, 0 errors** |
| Screenshot check | 10 PNGs at 1500×950; inspected graph and commit/diff screenshots |
| Quality scans | No real Git in `app/src/main`; no dp/sp outside `GraphStyle.kt`; no TODO/FIXME; no Compose/AWT/Swing in `core`; no empty catches |
| Worktree cleanup | Three merged Phase 2 worktrees removed; `phase2/*` branches retained |

### Phase 2 final verification

- `./gradlew clean :core:test check build --rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL`;
  17/17 tasks executed.
- `./gradlew :core:test` → `BUILD SUCCESSFUL`; 238 tests, 0 failures.
- `./gradlew check` → `BUILD SUCCESSFUL`; includes 33 app tests.
- `./gradlew build` → `BUILD SUCCESSFUL`.
- T1–T7 independently verified by QA and fully covered by SESSION tests.
- UI smoke tests cover R1–R5/R7/R9 paths; no real Git is used.
- Screenshots generated by the smoke suite:
  `01-main-graph.png`, `02-changes-staged-unstaged.png`, `02b-changes-after-stage.png`,
  `02c-discard-staged-index-only.png`, `03-commit-panel-diff.png`, `04-refs-query.png`,
  `04b-query-error.png`, `05-tab-switch.png`, `06-push-pull-stderr.png`, `07-commit-success.png`.
- `temp/` was not modified. Phase 3 began only after this Phase 2 gate was green.

## Phase 3 — Integration

### Preflight

- [x] `./gradlew :core:test` → **BUILD SUCCESSFUL** (238 tests at the time)
- [x] `./gradlew check` → **BUILD SUCCESSFUL**
- [x] `./gradlew build` → **BUILD SUCCESSFUL**
- [x] 10 screenshots present in `app/build/screenshots/`
- [x] App was still fake-backed (`DemoGitBackend`/`DemoWatcher` in `Main.kt`)

### Workstreams and ownership

Three subagents were optimal: GIT/WATCH and UI are disjoint and ran in parallel, while QA required
the merged real wiring for end-to-end acceptance. SESSION was not needed because no contract change
was required.

| Workstream | Subagent | Owned paths | Result |
|---|---|---|---|
| GIT/WATCH | 1 | `core/git/**`, `core/watch/**`, their tests/resources | Real backend/watcher fixes, 121 GIT/WATCH tests |
| UI | 2 | `app/src/main/**`, `app/src/test/**/ui/**` | Real `SessionManager` wiring, 38 UI tests, real-repo UI flow |
| QA | 3 | `core/src/test/**/acceptance/**`, `app/src/test/**/acceptance/**` | Real oracles, watcher, watcher-disabled, push/pull, end-to-end app acceptance |

### GIT/WATCH

- [x] Concurrent stdin/stdout/stderr handling; no large-log deadlock
- [x] Bounded process shutdown, descendant kill, stream closure; no hung processes
- [x] Cleared inherited repository-selection environment variables
- [x] Index-only removal uses `git rm --cached -f` for staged additions (worktree untouched)
- [x] Porcelain-v2 `.` normalized to `' '` in `StatusEntry`
- [x] Watcher `OVERFLOW` emits `REFS_CHANGED`; failed starts and stops clean up threads/services
- [x] Real watcher integration: refs, checkout, packed-refs after `git gc`, index/status,
  operation/rebase files, worktree exclusions, no `objects/`, debounce, cap, stop cleanup
- Evidence: `./gradlew :core:test --rerun-tasks` → **BUILD SUCCESSFUL**, 265 tests on the workstream
  branch; GIT/WATCH suites 121 tests (GIT 88, WATCH 33), 0 failures.

### UI

- [x] Production `Main.kt` uses real `SessionManager` defaults: `CliGitBackend`, `JdkRepoWatcher`,
  `JsonSessionPersistence`; `DemoBackend.kt` removed
- [x] Optional startup repository path; directory chooser opens a real session
- [x] Empty/loading/unavailable/error states preserved; real Git stderr shown
- [x] Real-repository UI test: graph, commit detail/files/diff, stage, discard with confirmation,
  commit, and manual refresh without watcher events
- [x] UI tests still inject fakes; fakes remain test-only
- Evidence: `./gradlew :app:test --rerun-tasks` → **BUILD SUCCESSFUL**; 38 app tests, 0 failures.
  `:app:build` green. Real app launched against a temporary repository:
  `APP_RUNNING_WITH_REAL_REPO pid=50955`, then `APP_STOPPED`.

### QA

- [x] Fixed stale `'.'` acceptance assertions to the normalized `' '` convention
- [x] Real Git oracles extended for statuses, writes, refs, commit detail, rename, push/pull errors
- [x] Real `JdkRepoWatcher` acceptance: branches, checkout, `git gc`, operation files, exclusions,
  ignored `objects/`, debounce, stop cleanup
- [x] Watcher-disabled session acceptance: silent watcher and failed-start watcher; explicit
  refresh/after-write refresh, stage/unstage/commit, checkout, 3 s polling fallback
- [x] Local bare-repo push/pull acceptance: missing upstream, push, fast-forward, divergence/rejection
- [x] End-to-end app acceptance with real `CliGitBackend` and real `JdkRepoWatcher`:
  graph → select → diff → stage/discard → commit → toolbar push → fast-forward pull
- Evidence: `./gradlew :core:test :app:test --rerun-tasks` → **BUILD SUCCESSFUL**;
  279 core tests + 40 app tests, 0 failures/errors. `check` and `build` green.

### Integration and final gate

- [x] Merged `phase3/gitwatch`
- [x] Merged `phase3/ui`
- [x] Merged `phase3/qa`
- [x] `./gradlew clean :core:test check build --rerun-tasks --no-build-cache`
- [x] Exact `./gradlew :core:test`, `./gradlew check`, and `./gradlew build` run by the main agent
- [x] Direct real-repository launch verified by the main agent

### Phase 3 command log

All Gradle commands were run with `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-zulu` and that JDK's
`bin` directory first on `PATH`.

| Command / action | Result |
|---|---|
| Preflight `./gradlew :core:test` / `check` / `build` | All **BUILD SUCCESSFUL**; 238 core tests, 10 screenshots; app still fake-backed |
| Worktrees `phase3-gitwatch`, `phase3-ui`; `phase3-qa` after integration | Three isolated branches |
| `phase3-gitwatch`: `./gradlew :core:test --rerun-tasks` | **BUILD SUCCESSFUL** in 1m44s; 265 tests, GIT/WATCH 121, 0 failures |
| `phase3-gitwatch`: `./gradlew :core:build --rerun-tasks` | **BUILD SUCCESSFUL** |
| GIT follow-up: normalize porcelain-v2 `.` | Parser + goldens updated; GIT/WATCH 121 tests green |
| `phase3-ui`: `./gradlew :app:test --rerun-tasks` | **BUILD SUCCESSFUL**; 38 app tests, 0 failures |
| `phase3-ui`: `./gradlew :app:build --rerun-tasks` | **BUILD SUCCESSFUL** |
| Main: `git merge --no-ff phase3/gitwatch` | Merged, no conflicts |
| Main: `git merge --no-ff phase3/ui` | Merged, no conflicts |
| `phase3-qa`: `./gradlew :core:test :app:test --rerun-tasks` | **BUILD SUCCESSFUL** in 2m34s; 279 core + 40 app tests |
| Main: `git merge --no-ff phase3/qa` | Merged, no conflicts |
| Main final gate: `./gradlew clean :core:test check build --rerun-tasks --no-build-cache` | **BUILD SUCCESSFUL in 2m30s**; 17/17 tasks executed |
| Main exact gate: `./gradlew :core:test` | **BUILD SUCCESSFUL**; exit 0 |
| Main exact gate: `./gradlew check` | **BUILD SUCCESSFUL**; exit 0 |
| Main exact gate: `./gradlew build` | **BUILD SUCCESSFUL**; exit 0 |
| Real app launch against temporary repo | `APP_RUNNING_WITH_REAL_REPO pid=50955`; stopped cleanly (`APP_STOPPED`) |
| Final test report | 279 core + 40 app tests, **0 failures, 0 errors** |
| Quality scans | No fakes in production; real `SessionManager` defaults confirmed; no dp/sp outside `GraphStyle.kt`; no TODO/FIXME; no empty catches |
| Worktree cleanup | Three merged Phase 3 worktrees removed; `phase3/*` branches retained |

### Phase 3 final verification

- `./gradlew clean :core:test check build --rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL`;
  17/17 tasks executed.
- `./gradlew :core:test` → `BUILD SUCCESSFUL`; 279 tests, 0 failures.
- `./gradlew check` → `BUILD SUCCESSFUL`; includes 40 app tests.
- `./gradlew build` → `BUILD SUCCESSFUL`.
- Oracle, watcher, push/pull, watcher-disabled, and real-app acceptance suites all green.
- Production `Main.kt` opens a real repository with real `CliGitBackend`/`JdkRepoWatcher`; fakes are
  test-only.
- Phase 2 screenshots are regenerated by the smoke suite (10 PNGs in `app/build/screenshots/`).
- `temp/` was not modified. Phase 4 began only after this Phase 3 gate was green.

## Phase 4 — Hardening, Performance, Packaging

### Preflight

- [x] Real `CliGitBackend` and `JdkRepoWatcher` are the production defaults.
- [x] `./gradlew :core:test` → **BUILD SUCCESSFUL** (279 tests at the time)
- [x] `./gradlew check` → **BUILD SUCCESSFUL**
- [x] `./gradlew build` → **BUILD SUCCESSFUL**
- [x] Full real-repository flow previously verified manually and through UI tests.

### Workstreams and ownership

Four subagents were optimal: HARDENING, PERFORMANCE, and PACKAGING are disjoint and ran in parallel;
QA ran last against the integrated MVP for independent R1–R9 and installer evidence.

| Workstream | Subagent | Owned paths | Result |
|---|---|---|---|
| HARDENING | 1 | `core/git/**`, `core/watch/**`, `core/session/**`, `app/src/main/**`, hardening tests | 5 findings fixed, 22 core + 2 app hardening tests |
| PERFORMANCE | 2 | `core/graph/**`, `core/query/**` if needed, `core/build.gradle.kts`, bench sources | Separate `bench` task, 50k/200k measurements |
| PACKAGING | 3 | `app/build.gradle.kts`, `README.md`, packaging resources | macOS Dmg built, launched, documented |
| QA | 4 | core/app acceptance tests | Independent R1–R9 audit, installer smoke, 8 new tests |

### HARDENING

- [x] Newline in work-tree path: `git rev-parse` is now invoked per value, not parsed line-by-line
- [x] File removed between status and diff: clear error + status refresh
- [x] Diff failure refreshes status and reports stderr
- [x] Discard confirmation scoped to the active tab/session
- [x] Diff lines capped at `GraphStyle.MAX_DIFF_LINE_CHARS` for display only
- [x] Covered weird paths, empty/unborn/detached, tags/symrefs, 1,000 branches, SHA-256,
  100-parent octopus, worktrees/submodules, Unicode/CRLF messages, hooks, Git mid-command failures,
  moved/deleted repos, permission errors, 50 MB diffs, watcher bursts, tab close during load, and
  the full discard matrix
- Evidence: `./gradlew :core:test :app:test --rerun-tasks` → **BUILD SUCCESSFUL**;
  301 core + 42 app tests on the branch, 0 failures. `check`/`build` green.

### PERFORMANCE

`./gradlew bench` is a separate task, never part of `check` or `build`. It uses the 50k
`SyntheticDagGenerator` and the cached 200k `PerfRepoBuilder` repository.

| Metric | Target | 50k synthetic | 200k real repo |
|---|---:|---:|---:|
| First visible rows | < 400 ms | 0.30 ms layout | **713.3 ms cold** (606 ms warm) |
| Full graph | < 3 s | 18.8 ms layout | **817.3 ms** |
| Overhead over raw `git log` | ≤ 30% | — | **−0.2%** |
| Layout throughput | ≥ 300k rows/s | **2.66 M rows/s** | **4.96 M rows/s** |
| Graph memory | ≤ 400 B/commit | **206.2 B** | **260.0 B** (329.1 B with lazy index) |
| Fast scroll (40 rows/frame) | no frame > 16 ms | max 0.03 ms | max 0.23 ms |
| Tab switch to frozen snapshot | < 50 ms | — | p99 0.02 ms, max 0.03 ms |
| Status backend overhead | ≤ ~50 ms | — | **1.67 ms** |

- First-visible deviation is Git-bound: raw `git log --topo-order` takes **594 ms** for the first
  2,000 rows. With a commit-graph, cold first rows drop to **142 ms**; FreeTree recommends it but
  never writes it automatically.
- All other budgets pass. No graph/query optimization was required; measured graph overhead is
  within noise of raw Git.
- Memory and scroll numbers are core-side approximations; Compose text layout/drawing is excluded.
- Evidence: `./gradlew bench` → **BUILD SUCCESSFUL in 43s** on final `master`.

### PACKAGING

- [x] `./gradlew :app:packageDmg` → **BUILD SUCCESSFUL**
- [x] Dmg: `app/build/compose/binaries/main/dmg/FreeTree-1.0.0.dmg`
- [x] Mounted, launched FreeTree, confirmed a running window, quit, detached; no leftovers
- [x] `:app:packageDeb` and `:app:packageMsi` are host-skipped on macOS, as expected
- [x] The provided `temp/FreeTreeIcon.icns` was copied to `app/icons/FreeTree.icns` and configured as
  the macOS bundle icon; the built app contains the same SHA-256:
  `9f550a9c…42dc2b`
- [x] README updated with Java 25, Git ≥ 2.30, run/test/check/bench/package commands, shortcuts,
  large-repository/commit-graph advice, and known limits
- Dmg is unsigned/unnotarized; Linux/Windows installers remain host-specific and untested here.

### QA — R1–R9 acceptance

| Req | Evidence | Result |
|---|---|---|
| R1 | `GraphLayoutAcceptanceTest`, real-UI merge/ref/HEAD test, I1–I7 suites | PASS |
| R2 | `DiffSafetyAcceptanceTest` (binary, >2 MB, unlimited), commit selection UI tests | PASS |
| R3 | `GitWritesAcceptanceTest`, `GitFilesAndStatusAcceptanceTest`, real-UI multiline/rename/delete/commit | PASS |
| R4 | `PushPullAcceptanceTest`, real-UI push/pull error surface, local bare origin | PASS |
| R5 | `QueryAcceptanceTest`, real-UI ref hiding/query/flat/invalid query | PASS |
| R6 | `./gradlew bench` | PASS with first-visible measured deviation |
| R7 | `SessionInvariantsAcceptanceTest` (T1–T7) | PASS |
| R8 | `:app:packageDmg` + mount/launch/quit/detach smoke | PASS (macOS) |
| R9 | `DiscardAcceptanceTest` (9 tests) + real-UI staged/unstaged/untracked flow | PASS |

- Evidence: `./gradlew :core:test --rerun-tasks` → **BUILD SUCCESSFUL**; 305 tests.
  `./gradlew :app:test --rerun-tasks` → **BUILD SUCCESSFUL**; 46 tests. `check`/`build` green.
- Installer smoke independently launched the Dmg (PID 4189) and left no process/mount.

### Integration and final gate

- [x] Merged `phase4/hardening`
- [x] Merged `phase4/performance`
- [x] Merged `phase4/packaging`
- [x] Merged `phase4/qa`
- [x] Added and verified the provided macOS app icon
- [x] Final `./gradlew clean build :core:test --rerun-tasks --no-build-cache`
- [x] Final `./gradlew check`
- [x] Final `./gradlew bench`
- [x] Final `./gradlew :app:packageDmg` + Dmg launch smoke

### Phase 4 command log

All Gradle commands were run with `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-zulu` and that JDK's
`bin` directory first on `PATH`.

| Command / action | Result |
|---|---|
| Preflight `:core:test` / `check` / `build` | All **BUILD SUCCESSFUL**; 279 core tests, real wiring confirmed |
| Worktrees `phase4-hardening`, `phase4-performance`, `phase4-packaging`; QA after integration | Four isolated branches |
| HARDENING: `./gradlew :core:test :app:test --rerun-tasks` | **BUILD SUCCESSFUL** in 2m48s; 301 core + 42 app tests |
| PERFORMANCE: `./gradlew bench` | **BUILD SUCCESSFUL** in 38s; metrics above |
| PERFORMANCE: `:core:test`, `check`, `build` | **BUILD SUCCESSFUL** |
| PACKAGING: `./gradlew :app:packageDmg` | **BUILD SUCCESSFUL** in 31s; Dmg 72 MB |
| PACKAGING: Dmg mount/launch/quit/detach | App pid 56043; window `FreeTree`; clean shutdown |
| PACKAGING: `:app:packageDeb`, `:app:packageMsi` | Host-skipped on macOS (expected) |
| Main: merge HARDENING, PERFORMANCE, PACKAGING | All merged, no conflicts |
| Main: README bench wording fix | Present-tense `./gradlew bench` instructions |
| QA: `:core:test --rerun-tasks` | **BUILD SUCCESSFUL**; 305 tests, 37 suites |
| QA: `:app:test --rerun-tasks` | **BUILD SUCCESSFUL**; 46 tests, 13 suites |
| QA: `check` / `build` / `bench` | All **BUILD SUCCESSFUL** |
| QA: Dmg mount/launch/quit/detach | App pid 4189; clean shutdown |
| Main final: `./gradlew clean build :core:test --rerun-tasks --no-build-cache` | **BUILD SUCCESSFUL in 2m55s**; 17/17 tasks |
| Main final: `./gradlew check` | **BUILD SUCCESSFUL**; exit 0 |
| Main final: `./gradlew bench` | **BUILD SUCCESSFUL in 43s**; final metrics above |
| Main final: `./gradlew :app:packageDmg` | **BUILD SUCCESSFUL in 24s** |
| Icon copy | `temp/FreeTreeIcon.icns` → `app/icons/FreeTree.icns`; SHA-256 matches |
| Icon embedding | `FreeTree.app/Contents/Resources/FreeTree.icns` SHA-256 matches source |
| Final Dmg launch | Mounted, app pid 19925, quit, detached, no leftovers |
| Final test report | 305 core + 46 app = **351 tests, 0 failures, 0 errors** |
| Quality scans | No fakes in production, no dp/sp outside `GraphStyle.kt`, no TODO/FIXME, no empty catches |
| Worktree cleanup | Four merged Phase 4 worktrees removed; `phase4/*` branches retained |

### Phase 4 final verification

- `./gradlew clean build :core:test --rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL`;
  17/17 tasks executed.
- `./gradlew check` → `BUILD SUCCESSFUL`.
- `./gradlew bench` → `BUILD SUCCESSFUL`; all budgets pass except first visible rows
  (713 ms vs 400 ms), measured as Git topo-order latency and logged in `DECISIONS.md`.
- Dmg built at `app/build/compose/binaries/main/dmg/FreeTree-1.0.0.dmg`, with the provided icon
  embedded, and launched/quit/detached successfully.
- R1–R9 reviewed with test evidence; no functional MVP blocker remains.
- `temp/` was not modified.

### Deferred visual polish (not MVP blockers)

- Canvas curve/arrow aesthetics and exact merge-arrow styling.
- Chip tooltip presentation and pixel-level chip truncation.
- Minor window/pane spacing polish beyond the documented `GraphStyle` values.
