package com.jmartinezcampos.freetree.app.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class GraphColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val primary: Color,
    val onPrimary: Color,
    val divider: Color,
    val selectedRow: Color,
    val hoverRow: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val lanePalette: List<Color>,
    val nodeBorder: Color,
    val headHalo: Color,
    val arrow: Color,
    val chipLocalBackground: Color,
    val chipLocalForeground: Color,
    val chipRemoteBackground: Color,
    val chipRemoteForeground: Color,
    val chipTagBackground: Color,
    val chipTagForeground: Color,
    val chipHeadBackground: Color,
    val chipHeadForeground: Color,
    val diffAddBackground: Color,
    val diffDelBackground: Color,
    val diffHunkBackground: Color,
    val diffFileHeaderBackground: Color,
    val diffNoteBackground: Color,
    val diffLineNumber: Color,
    val statusAdded: Color,
    val statusModified: Color,
    val statusDeleted: Color,
    val statusRenamed: Color,
    val scrollbarTrack: Color,
    val scrollbarThumb: Color,
    val scrollbarThumbHover: Color,
)

object GraphStyle {
    val ROW_HEIGHT: Dp = 28.dp
    val LANE_WIDTH: Dp = 16.dp
    val NODE_RADIUS: Dp = 4.dp
    val STROKE_WIDTH: Dp = 1.5.dp
    const val MAX_VISIBLE_LANES: Int = 16
    val ARROW_SIZE: Dp = 6.dp
    const val ARROW_TOWARD_PARENT: Boolean = true
    const val CENTER_FACTOR: Float = 2f
    const val CURVE_CONTROL_FRACTION: Float = 0.5f
    const val HEAD_HALO_RADIUS_FACTOR: Float = 2f
    const val MERGE_RING_RADIUS_FACTOR: Float = 1.45f

    val DIFF_MONO_SIZE = 12.sp
    val DIFF_LINE_HEIGHT = 16.sp
    val DIFF_ROW_HEIGHT: Dp = 16.dp
    val DIFF_LINE_NUMBER_WIDTH: Dp = 44.dp
    val BODY_SIZE = 12.sp
    val BODY_LINE_HEIGHT = 17.sp
    val SMALL_SIZE = 11.sp
    val SMALL_LINE_HEIGHT = 15.sp
    val TITLE_SIZE = 13.sp
    val TITLE_LINE_HEIGHT = 18.sp

    val WINDOW_WIDTH: Dp = 1180.dp
    val WINDOW_HEIGHT: Dp = 760.dp
    val ABOUT_DIALOG_WIDTH: Dp = 420.dp
    val ABOUT_DIALOG_HEIGHT: Dp = 320.dp
    val ABOUT_ICON_SIZE: Dp = 96.dp
    val ABOUT_CONTENT_MARGIN: Dp = 16.dp
    val ABOUT_TITLE_SIZE = 20.sp
    val ABOUT_TITLE_LINE_HEIGHT = 24.sp
    val CONFIRM_DIALOG_WIDTH: Dp = 460.dp
    val CONFIRM_DIALOG_HEIGHT: Dp = 240.dp

    val SPACE_XXS: Dp = 2.dp
    val SPACE_XS: Dp = 4.dp
    val SPACE_SM: Dp = 6.dp
    val SPACE_MD: Dp = 8.dp
    val SPACE_LG: Dp = 12.dp
    val SPACE_XL: Dp = 16.dp

    val TAB_BAR_HEIGHT: Dp = 34.dp
    val TAB_HEIGHT: Dp = 26.dp
    val TAB_CLOSE_SIZE: Dp = 16.dp
    val TAB_CLOSE_ICON_SIZE: Dp = 8.dp
    val TAB_CLOSE_ICON_STROKE: Dp = 1.3.dp
    val TAB_CORNER_RADIUS: Dp = 6.dp
    val TAB_BAR_START_PADDING: Dp = 4.dp
    val TAB_HORIZONTAL_PADDING: Dp = 10.dp
    val TAB_TITLE_WIDTH: Dp = 112.dp
    val TAB_TEXT_SIZE = 12.sp
    val TAB_TEXT_LINE_HEIGHT = 16.sp
    val NEW_TAB_SIZE: Dp = 22.dp
    val NEW_TAB_CORNER_RADIUS: Dp = 6.dp
    val NEW_TAB_ICON_SIZE: Dp = 10.dp
    val NEW_TAB_ICON_STROKE: Dp = 1.5.dp

    val SCROLLBAR_TRACK_WIDTH: Dp = 10.dp
    val SCROLLBAR_THUMB_WIDTH: Dp = 6.dp
    val SCROLLBAR_TRACK_RADIUS: Dp = 5.dp
    val SCROLLBAR_THUMB_RADIUS: Dp = 3.dp
    val SCROLLBAR_MIN_THUMB_HEIGHT: Dp = 24.dp
    val SCROLLBAR_TRACK_MARGIN: Dp = 4.dp
    val SCROLLBAR_GUTTER: Dp = 12.dp
    const val SCROLLBAR_HIDE_DELAY_MILLIS: Long = 900L
    const val SCROLLBAR_FADE_MILLIS: Int = 160
    val TOOLBAR_HEIGHT: Dp = 36.dp
    val DIVIDER_THICKNESS: Dp = 1.dp
    val CONTROL_HEIGHT: Dp = 28.dp
    val BUSY_INDICATOR_SIZE: Dp = 16.dp
    val PROGRESS_HEIGHT: Dp = 3.dp
    val REFS_PANEL_MAX_HEIGHT: Dp = 220.dp
    val GRAPH_ROW_PADDING: Dp = 8.dp
    val LANE_AREA_MIN_WIDTH: Dp = LANE_WIDTH

    val AUTHOR_COLUMN_WIDTH: Dp = 100.dp
    val DATE_COLUMN_WIDTH: Dp = 118.dp
    val CHIP_CORNER_RADIUS: Dp = 4.dp
    val CHIP_PADDING_H: Dp = 4.dp
    val CHIP_PADDING_V: Dp = 1.dp
    const val CHIP_MAX: Int = 3

    val GRAPH_PANE_WEIGHT: Float = 0.62f
    const val FILL_WEIGHT: Float = 1f

    val SPLITTER_HIT_WIDTH: Dp = 10.dp
    val SPLITTER_HIT_HEIGHT: Dp = 10.dp
    val MIN_GRAPH_PANE_WIDTH: Dp = 320.dp
    val MIN_DETAIL_PANE_WIDTH: Dp = 320.dp
    val MIN_FILES_PANE_HEIGHT: Dp = 100.dp
    val MIN_DIFF_PANE_HEIGHT: Dp = 120.dp
    const val COMMIT_FILES_FRACTION: Float = 0.5f
    const val CHANGES_FILES_FRACTION: Float = 0.5f

    const val PAGE_ROWS: Int = 10
    const val QUERY_DEBOUNCE_MILLIS: Long = 300L
    const val HASH_ABBREVIATION: Int = 8
    const val MESSAGE_MIN_LINES: Int = 3
    const val MESSAGE_MAX_LINES: Int = 6
    val COMMIT_PLACEHOLDER_OFFSET_X: Dp = 0.dp
    val COMMIT_PLACEHOLDER_OFFSET_Y: Dp = 0.dp
    const val TOOLTIP_DELAY_MILLIS: Int = 300
    const val MAX_TEXT_LINES: Int = 1
    const val MAX_DIFF_LINE_CHARS: Int = 2_000

    val DATE_PATTERN: String = "yyyy-MM-dd HH:mm"

    val typography: Typography = Typography(
        bodyMedium = TextStyle(fontSize = BODY_SIZE, lineHeight = BODY_LINE_HEIGHT),
        bodySmall = TextStyle(fontSize = SMALL_SIZE, lineHeight = SMALL_LINE_HEIGHT),
        titleSmall = TextStyle(
            fontSize = TITLE_SIZE,
            lineHeight = TITLE_LINE_HEIGHT,
            fontWeight = FontWeight.SemiBold,
        ),
        labelMedium = TextStyle(fontSize = SMALL_SIZE, lineHeight = SMALL_LINE_HEIGHT),
        labelSmall = TextStyle(fontSize = SMALL_SIZE, lineHeight = SMALL_LINE_HEIGHT),
    )

    val monoTextStyle: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = DIFF_MONO_SIZE,
        lineHeight = DIFF_LINE_HEIGHT,
    )

    val dark: GraphColors = GraphColors(
        background = Color(0xFF171A1F),
        surface = Color(0xFF1E2228),
        surfaceRaised = Color(0xFF272C34),
        onSurface = Color(0xFFE4E7EB),
        onSurfaceMuted = Color(0xFF9AA3AE),
        primary = Color(0xFF6FA8FF),
        onPrimary = Color(0xFF0B1220),
        divider = Color(0xFF333A44),
        selectedRow = Color(0xFF2D3E55),
        hoverRow = Color(0xFF252B34),
        error = Color(0xFFFF8B84),
        errorContainer = Color(0xFF4A2222),
        onErrorContainer = Color(0xFFFFD5D1),
        lanePalette = listOf(
            Color(0xFF64B5F6),
            Color(0xFF81C784),
            Color(0xFFFFB74D),
            Color(0xFFBA68C8),
            Color(0xFF4DD0E1),
            Color(0xFFF06292),
            Color(0xFFDCE775),
            Color(0xFFA1887F),
        ),
        nodeBorder = Color(0xFF11151A),
        headHalo = Color(0xFFFFD54F),
        arrow = Color(0xFFB0BEC5),
        chipLocalBackground = Color(0xFF244A36),
        chipLocalForeground = Color(0xFFB9F6CA),
        chipRemoteBackground = Color(0xFF243A55),
        chipRemoteForeground = Color(0xFFBBDEFB),
        chipTagBackground = Color(0xFF4A3A1C),
        chipTagForeground = Color(0xFFFFE0A3),
        chipHeadBackground = Color(0xFF5A4A12),
        chipHeadForeground = Color(0xFFFFF3C4),
        diffAddBackground = Color(0xFF1E3A24),
        diffDelBackground = Color(0xFF422326),
        diffHunkBackground = Color(0xFF233247),
        diffFileHeaderBackground = Color(0xFF2A3039),
        diffNoteBackground = Color(0xFF35301F),
        diffLineNumber = Color(0xFF7E8794),
        statusAdded = Color(0xFF81C784),
        statusModified = Color(0xFFFFB74D),
        statusDeleted = Color(0xFFEF9A9A),
        statusRenamed = Color(0xFF90CAF9),
        scrollbarTrack = Color(0x1FFFFFFF),
        scrollbarThumb = Color(0x66FFFFFF),
        scrollbarThumbHover = Color(0x99FFFFFF),
    )

    val light: GraphColors = GraphColors(
        background = Color(0xFFF7F8FA),
        surface = Color(0xFFFFFFFF),
        surfaceRaised = Color(0xFFEDEFF3),
        onSurface = Color(0xFF1C2128),
        onSurfaceMuted = Color(0xFF5E6875),
        primary = Color(0xFF1F5FBF),
        onPrimary = Color(0xFFFFFFFF),
        divider = Color(0xFFD5DAE1),
        selectedRow = Color(0xFFDCE8FA),
        hoverRow = Color(0xFFEEF1F5),
        error = Color(0xFFB3261E),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        lanePalette = listOf(
            Color(0xFF1565C0),
            Color(0xFF2E7D32),
            Color(0xFFEF6C00),
            Color(0xFF7B1FA2),
            Color(0xFF00838F),
            Color(0xFFC2185B),
            Color(0xFF827717),
            Color(0xFF5D4037),
        ),
        nodeBorder = Color(0xFFFFFFFF),
        headHalo = Color(0xFFB8860B),
        arrow = Color(0xFF546E7A),
        chipLocalBackground = Color(0xFFD9F2DF),
        chipLocalForeground = Color(0xFF1B5E20),
        chipRemoteBackground = Color(0xFFDCEBFA),
        chipRemoteForeground = Color(0xFF0D47A1),
        chipTagBackground = Color(0xFFFFF0C9),
        chipTagForeground = Color(0xFF6D4C00),
        chipHeadBackground = Color(0xFFFFE8A3),
        chipHeadForeground = Color(0xFF4A3A00),
        diffAddBackground = Color(0xFFE3F6E5),
        diffDelBackground = Color(0xFFFBE4E4),
        diffHunkBackground = Color(0xFFE3ECF8),
        diffFileHeaderBackground = Color(0xFFEFF1F4),
        diffNoteBackground = Color(0xFFFFF5D6),
        diffLineNumber = Color(0xFF7A838E),
        statusAdded = Color(0xFF2E7D32),
        statusModified = Color(0xFFB26A00),
        statusDeleted = Color(0xFFB3261E),
        statusRenamed = Color(0xFF0D47A1),
        scrollbarTrack = Color(0x14000000),
        scrollbarThumb = Color(0x4D000000),
        scrollbarThumbHover = Color(0x80000000),
    )

    fun colors(darkTheme: Boolean): GraphColors = if (darkTheme) dark else light

    fun materialColors(colors: GraphColors, darkTheme: Boolean) = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.background,
            onBackground = colors.onSurface,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.onSurfaceMuted,
            error = colors.error,
            errorContainer = colors.errorContainer,
            onErrorContainer = colors.onErrorContainer,
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.background,
            onBackground = colors.onSurface,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.onSurfaceMuted,
            error = colors.error,
            errorContainer = colors.errorContainer,
            onErrorContainer = colors.onErrorContainer,
        )
    }
}

val LocalGraphColors = staticCompositionLocalOf { GraphStyle.dark }
