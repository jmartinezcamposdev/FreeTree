package com.jmartinezcampos.freetree.app.ui

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

private const val LEGACY_PREFERENCES_NODE = "com/jmartinezcampos/freetree"

private val LEGACY_KEYS = setOf(
    "themeMode",
    "historyViewMode",
    "historyShowDate",
    "graphPaneFraction",
    "commitFilesFraction",
    "changesFilesFraction",
    "recentRepositories",
    "windowX",
    "windowY",
    "windowWidth",
    "windowHeight",
    "windowMonitorId",
    "windowMonitorX",
    "windowMonitorY",
    "windowMonitorWidth",
    "windowMonitorHeight",
)

/**
 * Reads the pre-JSON preference keys once so an existing installation keeps its settings when
 * [AppSettingsController] starts using `preferences.json`.
 */
internal fun readLegacySettings(
    preferences: Preferences = Preferences.userRoot().node(LEGACY_PREFERENCES_NODE),
): AppSettings? {
    val keys = try {
        preferences.keys().toSet()
    } catch (e: BackingStoreException) {
        System.err.println("FreeTree: cannot read legacy preferences: ${e.message}")
        return null
    } catch (e: SecurityException) {
        System.err.println("FreeTree: cannot read legacy preferences: ${e.message}")
        return null
    }
    if (keys.none { it in LEGACY_KEYS }) return null

    return AppSettings(
        themeMode = ThemeMode.entries.firstOrNull { it.name == preferences.get("themeMode", null) }
            ?: ThemeMode.SYSTEM,
        historyViewMode = HistoryViewMode.entries.firstOrNull {
            it.name == preferences.get("historyViewMode", null)
        } ?: HistoryViewMode.COMPACT,
        showHistoryDate = when (preferences.get("historyShowDate", null)?.lowercase()) {
            "false" -> false
            else -> true
        },
        graphPaneFraction = float(preferences, "graphPaneFraction", GraphStyle.GRAPH_PANE_WEIGHT),
        commitFilesFraction = float(
            preferences,
            "commitFilesFraction",
            GraphStyle.COMMIT_FILES_FRACTION,
        ),
        changesFilesFraction = float(
            preferences,
            "changesFilesFraction",
            GraphStyle.CHANGES_FILES_FRACTION,
        ),
        recentRepositories = readLegacyRecentRepositories(preferences),
        window = readLegacyWindow(preferences),
    )
}

private fun float(preferences: Preferences, key: String, default: Float): Float =
    preferences.get(key, null)?.toFloatOrNull() ?: default

private fun int(preferences: Preferences, key: String): Int? =
    preferences.get(key, null)?.toIntOrNull()

private fun readLegacyWindow(preferences: Preferences): SavedWindowState? {
    val bounds = WindowBounds(
        x = int(preferences, "windowX") ?: return null,
        y = int(preferences, "windowY") ?: return null,
        width = int(preferences, "windowWidth") ?: return null,
        height = int(preferences, "windowHeight") ?: return null,
    )
    val monitorBounds = WindowBounds(
        x = int(preferences, "windowMonitorX") ?: return null,
        y = int(preferences, "windowMonitorY") ?: return null,
        width = int(preferences, "windowMonitorWidth") ?: return null,
        height = int(preferences, "windowMonitorHeight") ?: return null,
    )
    val monitorId = preferences.get("windowMonitorId", null) ?: return null
    if (bounds.width <= 0 || bounds.height <= 0) return null
    if (monitorBounds.width <= 0 || monitorBounds.height <= 0) return null
    return SavedWindowState(bounds = bounds, monitorId = monitorId, monitorBounds = monitorBounds)
}

private fun readLegacyRecentRepositories(preferences: Preferences): List<String> {
    val saved = preferences.get("recentRepositories", null) ?: return emptyList()
    return saved.split(',').mapNotNull { encoded ->
        if (encoded.isEmpty()) return@mapNotNull null
        try {
            String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            System.err.println("FreeTree: ignoring invalid recent repository entry: ${e.message}")
            null
        }
    }
}
