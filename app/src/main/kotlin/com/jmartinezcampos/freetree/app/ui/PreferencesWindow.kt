package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState

private enum class PreferencesSection {
    APPEARANCE,
    HISTORY,
}

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
        resizable = true,
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
    var section by remember { mutableStateOf(PreferencesSection.APPEARANCE) }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag(UiTestTags.PREFERENCES_WINDOW),
    ) {
        Column(
            modifier = Modifier
                .width(GraphStyle.PREFERENCES_NAV_WIDTH)
                .fillMaxHeight()
                .background(colors.surface)
                .padding(horizontal = GraphStyle.SPACE_LG, vertical = GraphStyle.SPACE_XL),
            verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_XS),
        ) {
            Text(
                text = "Preferences",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = GraphStyle.SPACE_SM),
            )
            Spacer(Modifier.height(GraphStyle.SPACE_MD))
            PreferencesNavItem(
                label = "Appearance",
                selected = section == PreferencesSection.APPEARANCE,
                tag = UiTestTags.PREFERENCES_APPEARANCE,
                onClick = { section = PreferencesSection.APPEARANCE },
            )
            PreferencesNavItem(
                label = "Git history",
                selected = section == PreferencesSection.HISTORY,
                tag = UiTestTags.PREFERENCES_HISTORY,
                onClick = { section = PreferencesSection.HISTORY },
            )
        }
        Box(
            Modifier
                .fillMaxHeight()
                .width(GraphStyle.DIVIDER_THICKNESS)
                .background(colors.divider),
        )
        Column(
            modifier = Modifier
                .weight(GraphStyle.FILL_WEIGHT)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = GraphStyle.PREFERENCES_CONTENT_PADDING,
                    vertical = GraphStyle.SPACE_XL,
                ),
            verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_MD),
        ) {
            when (section) {
                PreferencesSection.APPEARANCE -> AppearanceSection(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                )
                PreferencesSection.HISTORY -> HistorySection(
                    historyViewMode = historyViewMode,
                    onHistoryViewModeChange = onHistoryViewModeChange,
                    showHistoryDate = showHistoryDate,
                    onShowHistoryDateChange = onShowHistoryDateChange,
                )
            }
        }
    }
}

@Composable
private fun AppearanceSection(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val colors = LocalGraphColors.current
    SettingsHeader(
        title = "Appearance",
        description = "Choose how FreeTree looks. System follows the operating system appearance.",
    )
    SettingsLabel("Theme")
    Row(
        modifier = Modifier.fillMaxWidth(),
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
    SettingsHint("Changes apply immediately across all windows.")
}

@Composable
private fun HistorySection(
    historyViewMode: HistoryViewMode,
    onHistoryViewModeChange: (HistoryViewMode) -> Unit,
    showHistoryDate: Boolean,
    onShowHistoryDateChange: (Boolean) -> Unit,
) {
    SettingsHeader(
        title = "Git history",
        description = "Control how commit rows are laid out in the history graph.",
    )
    SettingsLabel("Commit row layout")
    Row(
        modifier = Modifier.fillMaxWidth(),
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
    SettingsHint("Comfortable rows show the commit subject above the author and refs.")
    SettingsDivider()
    ToggleRow(
        label = "Show date and time",
        hint = "Applies to both compact and comfortable rows.",
        checked = showHistoryDate,
        tag = UiTestTags.HISTORY_SHOW_DATE,
        onCheckedChange = onShowHistoryDateChange,
    )
}

@Composable
private fun SettingsHeader(title: String, description: String) {
    val colors = LocalGraphColors.current
    Column(verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_XS)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalGraphColors.current.onSurface,
        modifier = Modifier.padding(top = GraphStyle.SPACE_SM),
    )
}

@Composable
private fun SettingsHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalGraphColors.current.onSurfaceMuted,
    )
}

@Composable
private fun SettingsDivider() {
    val colors = LocalGraphColors.current
    Spacer(Modifier.height(GraphStyle.SPACE_XS))
    Box(
        Modifier
            .fillMaxWidth()
            .height(GraphStyle.DIVIDER_THICKNESS)
            .background(colors.divider),
    )
    Spacer(Modifier.height(GraphStyle.SPACE_XS))
}

@Composable
private fun PreferencesNavItem(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = LocalGraphColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GraphStyle.PREFERENCES_NAV_ITEM_HEIGHT)
            .clip(RoundedCornerShape(GraphStyle.PREFERENCES_NAV_CORNER_RADIUS))
            .background(if (selected) colors.selectedRow else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = GraphStyle.SPACE_SM)
            .testTag(tag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onSurface else colors.onSurfaceMuted,
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
            .height(GraphStyle.PREFERENCES_OPTION_HEIGHT)
            .clip(RoundedCornerShape(GraphStyle.PREFERENCES_NAV_CORNER_RADIUS))
            .background(if (selected) colors.primary else colors.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = GraphStyle.SPACE_XL)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onPrimary else colors.onSurface,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    hint: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalGraphColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GraphStyle.PREFERENCES_NAV_CORNER_RADIUS))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = GraphStyle.SPACE_MD, vertical = GraphStyle.SPACE_MD)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_XS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(GraphStyle.PREFERENCES_TOGGLE_SIZE)
                    .clip(RoundedCornerShape(GraphStyle.PREFERENCES_TOGGLE_CORNER_RADIUS))
                    .background(if (checked) colors.primary else colors.surfaceRaised)
                    .border(
                        GraphStyle.DIVIDER_THICKNESS,
                        if (checked) colors.primary else colors.divider,
                        RoundedCornerShape(GraphStyle.PREFERENCES_TOGGLE_CORNER_RADIUS),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onPrimary,
                    )
                }
            }
            Spacer(Modifier.width(GraphStyle.SPACE_SM))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
            modifier = Modifier.padding(start = GraphStyle.PREFERENCES_TOGGLE_SIZE + GraphStyle.SPACE_SM),
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
