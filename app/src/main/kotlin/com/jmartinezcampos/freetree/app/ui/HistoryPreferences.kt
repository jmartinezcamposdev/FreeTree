package com.jmartinezcampos.freetree.app.ui

import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

enum class HistoryViewMode { COMPACT, COMFORTABLE }

internal interface HistoryPreferences {
    fun loadViewMode(): HistoryViewMode
    fun saveViewMode(mode: HistoryViewMode)
    fun loadShowDate(): Boolean
    fun saveShowDate(show: Boolean)
}

internal class PreferencesHistoryPreferences(
    private val preferences: Preferences = Preferences.userRoot()
        .node("com/jmartinezcampos/freetree"),
) : HistoryPreferences {
    override fun loadViewMode(): HistoryViewMode {
        val stored = try {
            preferences.get(HISTORY_VIEW_KEY, HistoryViewMode.COMPACT.name)
        } catch (e: SecurityException) {
            System.err.println("FreeTree: cannot read the history view preference: ${e.message}")
            return HistoryViewMode.COMPACT
        }
        return HistoryViewMode.entries.firstOrNull { it.name == stored } ?: HistoryViewMode.COMPACT
    }

    override fun saveViewMode(mode: HistoryViewMode) {
        save(HISTORY_VIEW_KEY, mode.name)
    }

    override fun loadShowDate(): Boolean {
        val stored = try {
            preferences.get(SHOW_DATE_KEY, "true")
        } catch (e: SecurityException) {
            System.err.println("FreeTree: cannot read the history date preference: ${e.message}")
            return true
        }
        return when (stored.lowercase()) {
            "true" -> true
            "false" -> false
            else -> true
        }
    }

    override fun saveShowDate(show: Boolean) {
        save(SHOW_DATE_KEY, show)
    }

    private fun save(key: String, value: String) {
        try {
            preferences.put(key, value)
            preferences.flush()
        } catch (e: BackingStoreException) {
            System.err.println("FreeTree: cannot save the history preference: ${e.message}")
        } catch (e: SecurityException) {
            System.err.println("FreeTree: cannot save the history preference: ${e.message}")
        }
    }

    private fun save(key: String, value: Boolean) {
        save(key, value.toString())
    }

    private companion object {
        const val HISTORY_VIEW_KEY = "historyViewMode"
        const val SHOW_DATE_KEY = "historyShowDate"
    }
}
