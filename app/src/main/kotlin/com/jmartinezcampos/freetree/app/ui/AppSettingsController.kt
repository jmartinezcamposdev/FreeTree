package com.jmartinezcampos.freetree.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun interface LegacySettingsLoader {
    fun load(): AppSettings?
}

/**
 * Single entry point for global FreeTree preferences.
 *
 * Every mutation updates the observable state immediately and persists the complete settings
 * document through [SettingsStore]. No UI code needs to know about JSON or the settings file.
 */
internal class AppSettingsController(
    private val store: SettingsStore,
    private val legacyLoader: LegacySettingsLoader? = null,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setHistoryViewMode(mode: HistoryViewMode) = update { it.copy(historyViewMode = mode) }

    fun setShowHistoryDate(show: Boolean) = update { it.copy(showHistoryDate = show) }

    fun setGraphPaneFraction(value: Float) = update { it.copy(graphPaneFraction = value) }

    fun setCommitFilesFraction(value: Float) = update { it.copy(commitFilesFraction = value) }

    fun setChangesFilesFraction(value: Float) = update { it.copy(changesFilesFraction = value) }

    fun setRecentRepositories(paths: List<String>) = update { it.copy(recentRepositories = paths) }

    fun setWindow(window: SavedWindowState?) = update { it.copy(window = window) }

    private fun loadInitial(): AppSettings {
        val loaded = try {
            store.load()
        } catch (e: Exception) {
            System.err.println("FreeTree: cannot read preferences: ${e.message}")
            null
        }
        if (loaded != null) return loaded.normalized()

        val legacy = try {
            legacyLoader?.load()
        } catch (e: Exception) {
            System.err.println("FreeTree: cannot migrate legacy preferences: ${e.message}")
            null
        }
        if (legacy != null) {
            val normalized = legacy.normalized()
            persist(normalized)
            return normalized
        }
        return AppSettings()
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        synchronized(lock) {
            val previous = _state.value
            val next = transform(previous).normalized()
            if (next == previous) return
            _state.value = next
            persist(next)
        }
    }

    private fun persist(settings: AppSettings) {
        try {
            store.save(settings)
        } catch (e: Exception) {
            System.err.println("FreeTree: cannot save preferences: ${e.message}")
        }
    }
}
