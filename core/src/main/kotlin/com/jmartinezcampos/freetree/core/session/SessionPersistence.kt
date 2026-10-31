package com.jmartinezcampos.freetree.core.session

import com.jmartinezcampos.freetree.core.persistence.AppDataPaths
import com.jmartinezcampos.freetree.core.persistence.JsonFileStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable

private const val CURRENT_SESSION_SCHEMA_VERSION = 1

@Serializable
data class PersistedSessionState(
    val schemaVersion: Int = CURRENT_SESSION_SCHEMA_VERSION,
    val tabs: List<TabState> = emptyList(),
    val activeId: String? = null,
)

interface SessionPersistence {
    fun load(): PersistedSessionState?
    fun save(state: PersistedSessionState)
}

class JsonSessionPersistence(
    val file: Path,
    private val legacyFile: Path? = null,
) : SessionPersistence {
    private val store = JsonFileStore(file, PersistedSessionState.serializer())

    override fun load(): PersistedSessionState? {
        val current = store.load()
        if (current != null) return current

        val legacy = legacyFile
            ?.takeIf { Files.exists(it) }
            ?.let { JsonFileStore(it, PersistedSessionState.serializer()).load() }
            ?: return null
        store.save(legacy)
        return legacy
    }

    override fun save(state: PersistedSessionState) {
        store.save(state)
    }
}

class InMemorySessionPersistence(initial: PersistedSessionState? = null) : SessionPersistence {
    @Volatile
    var saved: PersistedSessionState? = initial
        private set

    override fun load(): PersistedSessionState? = saved

    override fun save(state: PersistedSessionState) {
        saved = state
    }
}

fun defaultSessionStatePath(): Path = AppDataPaths.sessionFile()

fun defaultLegacySessionStatePath(): Path = AppDataPaths.legacySessionFile()
