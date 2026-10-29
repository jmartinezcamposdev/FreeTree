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
- Phase 1 was not started.

## Phase 1 — Core

Not started.

## Phase 2 — Session/UI

Not started.

## Phase 3 — Integration

Not started.

## Phase 4 — Hardening

Not started.
