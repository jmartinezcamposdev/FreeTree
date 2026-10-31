package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

enum class ThemeMode { SYSTEM, LIGHT, DARK }

internal interface ThemePreferences {
    fun load(): ThemeMode
    fun save(mode: ThemeMode)
}

internal class PreferencesThemePreferences(
    private val preferences: Preferences = Preferences.userRoot()
        .node("com/jmartinezcampos/freetree"),
) : ThemePreferences {
    override fun load(): ThemeMode {
        val stored = try {
            preferences.get(THEME_KEY, ThemeMode.SYSTEM.name)
        } catch (e: SecurityException) {
            System.err.println("FreeTree: cannot read the theme preference: ${e.message}")
            return ThemeMode.SYSTEM
        }
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    override fun save(mode: ThemeMode) {
        try {
            preferences.put(THEME_KEY, mode.name)
            preferences.flush()
        } catch (e: BackingStoreException) {
            System.err.println("FreeTree: cannot save the theme preference: ${e.message}")
        } catch (e: SecurityException) {
            System.err.println("FreeTree: cannot save the theme preference: ${e.message}")
        }
    }

    private companion object {
        const val THEME_KEY = "themeMode"
    }
}

@Composable
internal fun AppTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = GraphStyle.colors(dark)
    CompositionLocalProvider(LocalGraphColors provides colors) {
        MaterialTheme(
            colorScheme = GraphStyle.materialColors(colors, dark),
            typography = GraphStyle.typography,
            content = content,
        )
    }
}
