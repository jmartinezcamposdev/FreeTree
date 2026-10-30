# 01 — Bootstrap

Parent: [SPEC.md](SPEC.md). Read this before touching code.

## Current state

The repository is the Compose Multiplatform template:

```
settings.gradle.kts       includes :shared and :desktopApp
shared/                   KMP module with template UI (App, Greeting, Platform)
desktopApp/               JVM Compose Desktop entry point
gradle/libs.versions.toml Kotlin 2.4.21, Compose 1.12.2, coroutines 1.11.0
```

There is no feature code yet. Do not extend the template; migrate it.

## Target layout

```
FreeTree/
├─ settings.gradle.kts              include(":core", ":app")
├─ gradle/libs.versions.toml
├─ docs/                            this documentation
├─ temp/                            local-only; never tracked (PROGRESS.md, DECISIONS.md, legacy specs)
├─ core/                            kotlin("jvm") + java-test-fixtures; NO Compose
│  ├─ src/main/kotlin/com/jmartinezcampos/freetree/core/
│  │  ├─ model/                     Models.kt
│  │  ├─ git/                       GitRunner, CliGitBackend, parsers
│  │  ├─ graph/                     LaneLayoutEngine, GraphSnapshot, chunks
│  │  ├─ query/                     QueryParser, QueryResolver
│  │  ├─ watch/                     RepoWatcher, EventClassifier, Debouncer
│  │  └─ session/                   RepoSession, SessionManager, TabState, persistence
│  ├─ src/testFixtures/kotlin/       fakes, GitTestRepo, RandomDag
│  └─ src/test/kotlin/
└─ app/                             kotlin("jvm") + Compose Desktop
   ├─ src/main/kotlin/com/jmartinezcampos/freetree/app/
   │  ├─ Main.kt, App.kt
   │  ├─ theme/GraphStyle.kt
   │  ├─ tabs/, toolbar/, filter/, graph/, detail/
   └─ src/test/kotlin/
```

## Phase 0 steps

1. Rename `shared/` → `core/` and `desktopApp/` → `app/`; update `settings.gradle.kts`.
2. Remove the unused Kotlin Multiplatform plugin alias from the root build; add the Kotlin
   serialization plugin alias for `core`.
3. Convert `core` to `kotlin("jvm")` + `java-test-fixtures` + serialization. Remove all Compose
   dependencies from `core`.
4. Configure `jvmToolchain(25)` for `core` and `app`.
5. Keep `app` as `kotlin("jvm")` + Compose. It depends on `project(":core")` and declares
   `compose.desktop.currentOs`, `compose.material3`, `compose.foundation`, `compose.ui`, and
   `kotlinx-coroutines-swing`.
6. Replace the template UI with a minimal `Main.kt`/`App.kt` in
   `com.jmartinezcampos.freetree.app`. Delete `Greeting.kt`, `Platform.kt`, and the template
   drawable. Do not move the old resource-based `App.kt` as-is.
7. Set `mainClass = "com.jmartinezcampos.freetree.app.MainKt"` and
   `nativeDistributions.packageName = "FreeTree"`.
8. Create the contracts from [02-contracts.md](02-contracts.md).
9. Create `temp/PROGRESS.md` and `temp/DECISIONS.md` using the templates in
   [10-workflow.md](10-workflow.md). They are local-only working docs under `temp/` and must never
   be tracked.
10. Update `README.md` with Java/Git requirements, `./gradlew :app:run`, `./gradlew :core:test`,
    `./gradlew check`, and packaging notes.
11. **Gate:** `./gradlew build`, `./gradlew :core:test`, and an empty `./gradlew :app:run` window
    all work.

## Dependencies

Keep the current baseline: Kotlin 2.4.21, Compose 1.12.2, Material 3 1.12.0-alpha03, coroutines
1.11.0, JUnit 4.13.2, `kotlin-test` / `kotlin-test-junit`.

Add only:

- `kotlinx-coroutines-core`
- `kotlinx-coroutines-test`
- `kotlinx-serialization-json` + Kotlin serialization plugin
- `io.methvin:directory-watcher` (verify the current API/version)
- Compose UI test support for `app` (verify the accessor/artifact for Compose 1.12.2)

Do not add Kotest, a DI framework, an MVI library, a database, or `materialIconsExtended` unless a
specific need is approved. Remove unused lifecycle dependencies.

## Environment

- Java 25. Configure it in the IDE or shell; do not commit local JDK paths. Verify Compose
  Multiplatform 1.12.2 and Gradle compatibility early; if a conflict is proven, record the fallback
  JDK in `temp/DECISIONS.md` (local-only; never tracked).
- Git 2.30 or newer.
- macOS is the development platform; Linux is the next target, then Windows.
