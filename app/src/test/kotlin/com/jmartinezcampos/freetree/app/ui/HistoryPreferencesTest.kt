package com.jmartinezcampos.freetree.app.ui

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryPreferencesTest {

    @Test
    fun historyPreferencesRoundTripWithDefaults() {
        val node = Preferences.userRoot().node("freetree-history-test-${UUID.randomUUID()}")
        try {
            val preferences = PreferencesHistoryPreferences(node)
            assertEquals(HistoryViewMode.COMPACT, preferences.loadViewMode())
            assertTrue(preferences.loadShowDate())

            preferences.saveViewMode(HistoryViewMode.COMFORTABLE)
            preferences.saveShowDate(false)
            assertEquals(HistoryViewMode.COMFORTABLE, preferences.loadViewMode())
            assertFalse(preferences.loadShowDate())

            node.put("historyViewMode", "UNKNOWN")
            node.put("historyShowDate", "not-a-boolean")
            assertEquals(HistoryViewMode.COMPACT, preferences.loadViewMode())
            assertTrue(preferences.loadShowDate())
        } finally {
            node.removeNode()
        }
    }
}
