# FreeTree

FreeTree is a desktop Git graph client built with Kotlin and Compose Multiplatform Desktop.

## Requirements

- **JDK 25** for compilation and running the app. The build uses `jvmToolchain(25)`; Gradle can
  provision a matching JDK through the foojay resolver when one is not installed locally.
- **Git 2.30 or newer** on `PATH`.
- macOS is the primary development platform; Linux and Windows follow.

## Modules

- `core/` — pure Kotlin/JVM. Models, Git backend contracts, graph, query, watcher, and session
  contracts. No Compose.
- `app/` — Compose Multiplatform Desktop application.

## Build and test

```bash
./gradlew build
./gradlew :core:test
./gradlew check
```

## Run

```bash
./gradlew :app:run
```

## Packaging

The application is named `FreeTree` and uses the version in `app/build.gradle.kts`.

```bash
./gradlew :app:packageDmg   # macOS
./gradlew :app:packageDeb   # Linux
./gradlew :app:packageMsi   # Windows
```

Packaging requires the target operating system, a JDK 25 with `jpackage`, and the platform
packaging tools (for example, `dpkg` on Debian-based Linux).

## Documentation

- [docs/SPEC.md](docs/SPEC.md) — specification index and decisions
- [docs/01-bootstrap.md](docs/01-bootstrap.md) — layout and Phase 0
- [PROGRESS.md](PROGRESS.md) — phase status and command evidence
- [DECISIONS.md](DECISIONS.md) — decision log
