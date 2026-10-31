package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState

@Composable
internal fun PreferencesWindow(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    Window(
        onCloseRequest = onDismiss,
        title = "Preferences",
        state = rememberWindowState(
            width = GraphStyle.PREFERENCES_WINDOW_WIDTH,
            height = GraphStyle.PREFERENCES_WINDOW_HEIGHT,
        ),
        resizable = false,
    ) {
        AppTheme(themeMode) {
            PreferencesContent(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
            )
        }
    }
}

@Composable
internal fun PreferencesContent(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val colors = LocalGraphColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(GraphStyle.SPACE_XL)
            .testTag(UiTestTags.PREFERENCES_WINDOW),
        verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
    ) {
        Text(
            text = "Appearance",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        Text(
            text = "Theme",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GraphStyle.SPACE_XS),
            horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeMode.entries.forEach { mode ->
                ThemeOption(
                    mode = mode,
                    selected = mode == themeMode,
                    onClick = { onThemeModeChange(mode) },
                )
            }
        }
        Text(
            text = "System follows the operating system appearance.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
            modifier = Modifier.padding(top = GraphStyle.SPACE_XS),
        )
    }
}

@Composable
private fun ThemeOption(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalGraphColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(GraphStyle.THEME_OPTION_CORNER_RADIUS))
            .background(if (selected) colors.primary else colors.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = GraphStyle.SPACE_XL, vertical = GraphStyle.SPACE_SM)
            .testTag(mode.testTag()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = mode.label(),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) colors.onPrimary else colors.onSurface,
        )
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun ThemeMode.testTag(): String = when (this) {
    ThemeMode.SYSTEM -> UiTestTags.THEME_SYSTEM
    ThemeMode.LIGHT -> UiTestTags.THEME_LIGHT
    ThemeMode.DARK -> UiTestTags.THEME_DARK
}
