package com.jmartinezcampos.freetree.core.persistence

import java.nio.file.Path

/**
 * Single source of truth for where FreeTree keeps its own state files.
 */
object AppDataPaths {
    fun directory(): Path {
        val home = Path.of(System.getProperty("user.home"))
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> home.resolve("Library/Application Support/FreeTree")
            os.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(Path::of)
                    ?: home.resolve("AppData/Roaming")
                appData.resolve("FreeTree")
            }
            else -> {
                val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                    ?: home.resolve(".config")
                config.resolve("freetree")
            }
        }
    }

    fun sessionFile(): Path = directory().resolve("session.json")

    fun legacySessionFile(): Path = directory().resolve("state.json")

    fun preferencesFile(): Path = directory().resolve("preferences.json")
}
