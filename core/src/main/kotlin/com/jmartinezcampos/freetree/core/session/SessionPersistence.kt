package com.jmartinezcampos.freetree.core.session

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersistedSessionState(
    val tabs: List<TabState> = emptyList(),
    val activeId: String? = null,
)

interface SessionPersistence {
    fun load(): PersistedSessionState?
    fun save(state: PersistedSessionState)
}

class JsonSessionPersistence(val file: Path) : SessionPersistence {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    override fun load(): PersistedSessionState? {
        if (!Files.exists(file)) return null
        val text = Files.readString(file, StandardCharsets.UTF_8)
        return json.decodeFromString(PersistedSessionState.serializer(), text)
    }

    override fun save(state: PersistedSessionState) {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "state", ".json.tmp")
        try {
            Files.writeString(temporary, json.encodeToString(PersistedSessionState.serializer(), state), StandardCharsets.UTF_8)
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
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

fun defaultSessionStatePath(): Path {
    val home = Path.of(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") -> home.resolve("Library/Application Support/FreeTree/state.json")
        os.contains("win") -> {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: home.resolve("AppData/Roaming")
            appData.resolve("FreeTree/state.json")
        }
        else -> {
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: home.resolve(".config")
            config.resolve("freetree/state.json")
        }
    }
}
