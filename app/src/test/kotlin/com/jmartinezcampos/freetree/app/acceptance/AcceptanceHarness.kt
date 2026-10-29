package com.jmartinezcampos.freetree.app.acceptance

import com.jmartinezcampos.freetree.core.session.InMemorySessionPersistence
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.testing.FakeGitBackend
import com.jmartinezcampos.freetree.core.testing.FakeRepoScenarios
import com.jmartinezcampos.freetree.core.testing.FakeWatcher
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

internal class AcceptanceHarness(
    val manager: SessionManager,
    val backends: Map<Path, FakeGitBackend>,
    val watchers: Map<Path, FakeWatcher>,
    val persistence: InMemorySessionPersistence,
) : AutoCloseable {
    fun backend(path: Path): FakeGitBackend = backends.getValue(path.normalize())
    fun watcher(path: Path): FakeWatcher = watchers.getValue(path.normalize())

    override fun close() = manager.close()
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.harness(vararg paths: Path): AcceptanceHarness {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val normalized = paths.map { it.toAbsolutePath().normalize() }
    val backends = normalized.associateWith { FakeRepoScenarios.standard().backend(statefulWrites = true) }
    val watchers = normalized.associateWith { FakeWatcher() }.toMutableMap()
    val persistence = InMemorySessionPersistence()
    val manager = SessionManager(
        backendFactory = { path ->
            val key = path.toAbsolutePath().normalize()
            backends[key] ?: error("No fake backend registered for $path")
        },
        watcherFactory = { path ->
            val key = path.toAbsolutePath().normalize()
            watchers.getOrPut(key) { FakeWatcher() }
        },
        persistence = persistence,
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        readDispatcher = dispatcher,
        writeDispatcher = dispatcher,
        clockMillis = { testScheduler.currentTime },
        restore = false,
    )
    return AcceptanceHarness(manager, backends, watchers, persistence)
}
