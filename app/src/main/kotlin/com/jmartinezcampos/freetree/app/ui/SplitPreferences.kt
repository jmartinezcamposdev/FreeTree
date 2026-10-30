package com.jmartinezcampos.freetree.app.ui

import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

internal interface SplitPreferences {
    fun loadGraphFraction(): Float
    fun saveGraphFraction(value: Float)
    fun loadCommitFilesFraction(): Float
    fun saveCommitFilesFraction(value: Float)
    fun loadChangesFilesFraction(): Float
    fun saveChangesFilesFraction(value: Float)
}

internal class PreferencesSplitPreferences(
    private val preferences: Preferences = Preferences.userRoot()
        .node("com/jmartinezcampos/freetree"),
) : SplitPreferences {
    override fun loadGraphFraction(): Float =
        load(GRAPH_FRACTION_KEY, GraphStyle.GRAPH_PANE_WEIGHT)

    override fun saveGraphFraction(value: Float) = save(GRAPH_FRACTION_KEY, value)

    override fun loadCommitFilesFraction(): Float =
        load(COMMIT_FILES_FRACTION_KEY, GraphStyle.COMMIT_FILES_FRACTION)

    override fun saveCommitFilesFraction(value: Float) = save(COMMIT_FILES_FRACTION_KEY, value)

    override fun loadChangesFilesFraction(): Float =
        load(CHANGES_FILES_FRACTION_KEY, GraphStyle.CHANGES_FILES_FRACTION)

    override fun saveChangesFilesFraction(value: Float) = save(CHANGES_FILES_FRACTION_KEY, value)

    private fun load(key: String, default: Float): Float {
        val value = try {
            preferences.getFloat(key, default)
        } catch (e: SecurityException) {
            System.err.println("FreeTree: cannot read the split position: ${e.message}")
            default
        }
        return if (value.isFinite()) value.coerceIn(0.05f, 0.95f) else default
    }

    private fun save(key: String, value: Float) {
        if (!value.isFinite()) return
        try {
            preferences.putFloat(key, value.coerceIn(0.05f, 0.95f))
            preferences.flush()
        } catch (e: BackingStoreException) {
            System.err.println("FreeTree: cannot save the split position: ${e.message}")
        } catch (e: SecurityException) {
            System.err.println("FreeTree: cannot save the split position: ${e.message}")
        }
    }

    private companion object {
        const val GRAPH_FRACTION_KEY = "graphPaneFraction"
        const val COMMIT_FILES_FRACTION_KEY = "commitFilesFraction"
        const val CHANGES_FILES_FRACTION_KEY = "changesFilesFraction"
    }
}
