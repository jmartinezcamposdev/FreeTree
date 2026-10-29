# Decisions

| Date | ID | Decision | Reason | Alternatives |
|---|---|---|---|---|
| 2026-10-29 | D2 | Split the project into `core` (pure Kotlin/JVM) and `app` (Compose Desktop). | Enforces the no-Compose boundary and enables test fixtures. | Single module. |
| 2026-10-29 | D12 | Use Java 25 with `jvmToolchain(25)` for `core` and `app`; point the Compose application's `javaHome` at the same toolchain. | The spec requires Java 25. Compose `:app:run` otherwise uses the Gradle daemon JVM (Java 21) and fails with `UnsupportedClassVersionError`. No fallback JDK was needed. | Downgrade the JDK or leave the run task on the daemon JVM. |
| 2026-10-29 | D13 | Phase 0 contracts are compiling declarations. `SessionManager` constructor dependencies, `RepoSession`, `LaneLayoutEngine`, `GraphSnapshot` members, and the query functions are `TODO` stubs. | Phase 0 only defines the API surface; implementations belong to Phases 1–2. | Implement graph/query/session logic early (out of scope). |
| 2026-10-29 | D14 | Pin `kotlinx-serialization-json` to `1.12.0`. | Current stable release compatible with Kotlin 2.4.21 and required by `TabState`. | `1.11.0` or defer serialization. |
| 2026-10-29 | D15 | Use the catalog's `compose-material3` (`1.12.0-alpha03`) instead of the Compose plugin's deprecated `compose.material3` accessor. | Keeps the documented Material 3 baseline; the plugin accessor resolves 1.9.0. | `compose.material3` from the plugin. |
