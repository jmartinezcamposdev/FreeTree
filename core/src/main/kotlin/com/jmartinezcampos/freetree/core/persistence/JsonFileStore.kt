package com.jmartinezcampos.freetree.core.persistence

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Small typed JSON file store shared by session and preference persistence.
 *
 * Writes go through a temporary file in the same directory and are then moved over the target,
 * so a crash cannot leave a half-written JSON document behind.
 */
class JsonFileStore<T>(
    val file: Path,
    private val serializer: KSerializer<T>,
    private val json: Json = defaultJson(),
) {
    fun load(): T? {
        if (!Files.exists(file)) return null
        return json.decodeFromString(serializer, Files.readString(file, StandardCharsets.UTF_8))
    }

    fun save(value: T) {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".freetree-", ".json.tmp")
        try {
            Files.writeString(
                temporary,
                json.encodeToString(serializer, value),
                StandardCharsets.UTF_8,
            )
            moveOverTarget(temporary, file)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveOverTarget(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
