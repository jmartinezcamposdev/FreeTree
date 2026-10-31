package com.jmartinezcampos.freetree.app.ui

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class HistoryViewMode { COMPACT, COMFORTABLE }

private const val CURRENT_SETTINGS_SCHEMA_VERSION = 1

@Serializable
internal data class AppSettings(
    val schemaVersion: Int = CURRENT_SETTINGS_SCHEMA_VERSION,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val historyViewMode: HistoryViewMode = HistoryViewMode.COMPACT,
    val showHistoryDate: Boolean = true,
    val graphPaneFraction: Float = GraphStyle.GRAPH_PANE_WEIGHT,
    val commitFilesFraction: Float = GraphStyle.COMMIT_FILES_FRACTION,
    val changesFilesFraction: Float = GraphStyle.CHANGES_FILES_FRACTION,
    val recentRepositories: List<String> = emptyList(),
    val window: SavedWindowState? = null,
)

internal fun AppSettings.normalized(): AppSettings = copy(
    graphPaneFraction = normalizeFraction(graphPaneFraction, GraphStyle.GRAPH_PANE_WEIGHT),
    commitFilesFraction = normalizeFraction(commitFilesFraction, GraphStyle.COMMIT_FILES_FRACTION),
    changesFilesFraction = normalizeFraction(changesFilesFraction, GraphStyle.CHANGES_FILES_FRACTION),
    recentRepositories = recentRepositories.distinct().take(MAX_RECENT_REPOSITORIES),
    window = window?.takeIf { it.hasValidBounds() },
)

private fun normalizeFraction(value: Float, default: Float): Float =
    if (value.isFinite()) value.coerceIn(0.05f, 0.95f) else default

private fun SavedWindowState.hasValidBounds(): Boolean =
    bounds.width > 0 &&
        bounds.height > 0 &&
        monitorBounds.width > 0 &&
        monitorBounds.height > 0

internal const val MAX_RECENT_REPOSITORIES = 10
