package com.jmartinezcampos.freetree.app.ui

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePreferencesTest {

    @Test
    fun themeModeRoundTripsAndDefaultsToSystem() {
        val node = Preferences.userRoot().node("freetree-theme-test-${UUID.randomUUID()}")
        try {
            val preferences = PreferencesThemePreferences(node)
            assertEquals(ThemeMode.SYSTEM, preferences.load())

            preferences.save(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, preferences.load())

            preferences.save(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, preferences.load())

            node.put("themeMode", "UNKNOWN")
            assertEquals(ThemeMode.SYSTEM, preferences.load())
        } finally {
            node.removeNode()
        }
    }
}
