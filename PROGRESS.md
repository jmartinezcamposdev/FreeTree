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
| Integrated test report | 28 suites, **220 tests, 0 failures, 0 errors** |
| `grep` quality scans | No `TODO(`/`FIXME`/`NotImplementedError`; no Compose/AWT/Swing imports in `core`; no empty `catch` blocks |

### Phase 1 final verification

- `./gradlew clean build :core:test --rerun-tasks --no-build-cache` → `BUILD SUCCESSFUL`; all 15
  tasks executed; 220 tests, 0 failures, 0 errors.
- `./gradlew :core:test` → `BUILD SUCCESSFUL`.
- `./gradlew build` → `BUILD SUCCESSFUL`.
- `T1`–`T7` covered by SESSION tests with fakes and virtual time.
- No new dependencies; no shell/JGit/libgit2; `core` remains free of Compose/AWT/Swing.
- `temp/` was not modified. No Phase 2/UI work was started.

## Phase 2 — Session/UI

Not started.

## Phase 3 — Integration

Not started.

## Phase 4 — Hardening

Not started.
