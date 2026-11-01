package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

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
    CompositionLocalProvider(
        LocalGraphColors provides colors,
        LocalContextMenuRepresentation provides FreeTreeContextMenuRepresentation,
    ) {
        MaterialTheme(
            colorScheme = GraphStyle.materialColors(colors, dark),
            typography = GraphStyle.typography,
            content = content,
        )
    }
}
