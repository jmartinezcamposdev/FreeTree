package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
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
    historyViewMode: HistoryViewMode,
    onHistoryViewModeChange: (HistoryViewMode) -> Unit,
    showHistoryDate: Boolean,
    onShowHistoryDateChange: (Boolean) -> Unit,
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
                historyViewMode = historyViewMode,
                onHistoryViewModeChange = onHistoryViewModeChange,
                showHistoryDate = showHistoryDate,
                onShowHistoryDateChange = onShowHistoryDateChange,
            )
        }
    }
}

@Composable
internal fun PreferencesContent(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    historyViewMode: HistoryViewMode = HistoryViewMode.COMPACT,
    onHistoryViewModeChange: (HistoryViewMode) -> Unit = {},
    showHistoryDate: Boolean = true,
    onShowHistoryDateChange: (Boolean) -> Unit = {},
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
                OptionButton(
                    label = mode.label(),
                    selected = mode == themeMode,
                    testTag = mode.testTag(),
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

        Spacer(Modifier.height(GraphStyle.SPACE_SM))
        Box(
            Modifier
                .fillMaxWidth()
                .height(GraphStyle.DIVIDER_THICKNESS)
                .background(colors.divider),
        )
        Spacer(Modifier.height(GraphStyle.SPACE_SM))

        Text(
            text = "Git history",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        Text(
            text = "Commit rows",
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
            HistoryViewMode.entries.forEach { mode ->
                OptionButton(
                    label = mode.label(),
                    selected = mode == historyViewMode,
                    testTag = mode.testTag(),
                    onClick = { onHistoryViewModeChange(mode) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GraphStyle.SPACE_XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = showHistoryDate,
                onCheckedChange = onShowHistoryDateChange,
                modifier = Modifier.testTag(UiTestTags.HISTORY_SHOW_DATE),
            )
            Spacer(Modifier.width(GraphStyle.SPACE_SM))
            Text(
                text = "Show date and time",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
            )
        }
        Text(
            text = "Applies to both compact and comfortable rows.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun OptionButton(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = LocalGraphColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(GraphStyle.THEME_OPTION_CORNER_RADIUS))
            .background(if (selected) colors.primary else colors.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = GraphStyle.SPACE_XL, vertical = GraphStyle.SPACE_SM)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
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

private fun HistoryViewMode.label(): String = when (this) {
    HistoryViewMode.COMPACT -> "Compact"
    HistoryViewMode.COMFORTABLE -> "Comfortable"
}

private fun HistoryViewMode.testTag(): String = when (this) {
    HistoryViewMode.COMPACT -> UiTestTags.HISTORY_COMPACT
    HistoryViewMode.COMFORTABLE -> UiTestTags.HISTORY_COMFORTABLE
}
