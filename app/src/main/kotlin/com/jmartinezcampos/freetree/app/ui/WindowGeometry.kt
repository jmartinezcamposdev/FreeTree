package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Toolkit
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
internal data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal data class ScreenArea(
    val id: String,
    val bounds: WindowBounds,
    val workArea: WindowBounds,
)

internal data class DesktopScreens(
    val screens: List<ScreenArea>,
    val primaryId: String?,
)

@Serializable
internal data class SavedWindowState(
    val bounds: WindowBounds,
    val monitorId: String,
    val monitorBounds: WindowBounds,
)

internal fun readDesktopScreens(): DesktopScreens {
    return try {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val toolkit = Toolkit.getDefaultToolkit()
        DesktopScreens(
            screens = environment.screenDevices.map { device ->
                val configuration = device.defaultConfiguration
                val bounds = configuration.bounds
                val insets = toolkit.getScreenInsets(configuration)
                ScreenArea(
                    id = device.getIDstring(),
                    bounds = WindowBounds(bounds.x, bounds.y, bounds.width, bounds.height),
                    workArea = WindowBounds(
                        x = bounds.x + insets.left,
                        y = bounds.y + insets.top,
                        width = bounds.width - insets.left - insets.right,
                        height = bounds.height - insets.top - insets.bottom,
                    ),
                )
            },
            primaryId = environment.defaultScreenDevice.getIDstring(),
        )
    } catch (e: HeadlessException) {
        DesktopScreens(emptyList(), null)
    } catch (e: SecurityException) {
        System.err.println("FreeTree: cannot read the screen layout: ${e.message}")
        DesktopScreens(emptyList(), null)
    }
}

/**
 * Resolves the window bounds to use at startup.
 *
 * A saved position is only reused when its monitor still exists with the same size. The window is
 * then repositioned relative to that monitor, so moving a display in the OS settings does not move
 * the window to another display. If the monitor size changed, the window opens centered on that
 * monitor with the default size. Everything is clamped to the usable work area so the window can
 * never open outside the screen.
 */
internal fun resolveWindowBounds(
    saved: SavedWindowState?,
    screens: List<ScreenArea>,
    primaryId: String?,
    defaultWidth: Int,
    defaultHeight: Int,
    minWidth: Int,
    minHeight: Int,
): WindowBounds {
    val primary = screens.firstOrNull { it.id == primaryId } ?: screens.firstOrNull()
        ?: return WindowBounds(
            x = 0,
            y = 0,
            width = defaultWidth.coerceAtLeast(1),
            height = defaultHeight.coerceAtLeast(1),
        )

    if (saved == null) {
        return defaultBounds(primary, defaultWidth, defaultHeight, minWidth, minHeight)
    }

    val monitor = screens.firstOrNull { it.id == saved.monitorId }
        ?: return defaultBounds(primary, defaultWidth, defaultHeight, minWidth, minHeight)

    val sizeChanged = monitor.bounds.width != saved.monitorBounds.width ||
        monitor.bounds.height != saved.monitorBounds.height
    if (sizeChanged) {
        return defaultBounds(monitor, defaultWidth, defaultHeight, minWidth, minHeight)
    }

    val width = clampDimension(saved.bounds.width, minWidth, monitor.workArea.width)
    val height = clampDimension(saved.bounds.height, minHeight, monitor.workArea.height)
    val relativeX = saved.bounds.x - saved.monitorBounds.x
    val relativeY = saved.bounds.y - saved.monitorBounds.y
    val x = clampPosition(
        value = monitor.bounds.x + relativeX,
        start = monitor.workArea.x,
        extent = monitor.workArea.width,
        size = width,
    )
    val y = clampPosition(
        value = monitor.bounds.y + relativeY,
        start = monitor.workArea.y,
        extent = monitor.workArea.height,
        size = height,
    )
    return WindowBounds(x = x, y = y, width = width, height = height)
}

private fun defaultBounds(
    screen: ScreenArea,
    defaultWidth: Int,
    defaultHeight: Int,
    minWidth: Int,
    minHeight: Int,
): WindowBounds {
    val width = clampDimension(defaultWidth, minWidth, screen.workArea.width)
    val height = clampDimension(defaultHeight, minHeight, screen.workArea.height)
    return WindowBounds(
        x = screen.workArea.x + (screen.workArea.width - width) / 2,
        y = screen.workArea.y + (screen.workArea.height - height) / 2,
        width = width,
        height = height,
    )
}

private fun clampDimension(value: Int, minimum: Int, available: Int): Int {
    val max = available.coerceAtLeast(1)
    val min = minimum.coerceIn(1, max)
    return value.coerceIn(min, max)
}

private fun clampPosition(value: Int, start: Int, extent: Int, size: Int): Int {
    val max = start + (extent - size).coerceAtLeast(0)
    return value.coerceIn(start, max)
}

internal fun captureWindowState(
    windowState: WindowState,
    desktop: DesktopScreens,
    monitorId: String?,
): SavedWindowState? {
    val position = windowState.position
    val size = windowState.size
    if (!position.isSpecified || !size.isSpecified) return null

    val width = size.width.value.roundToInt()
    val height = size.height.value.roundToInt()
    if (width <= 0 || height <= 0) return null

    val bounds = WindowBounds(
        x = position.x.value.roundToInt(),
        y = position.y.value.roundToInt(),
        width = width,
        height = height,
    )
    val centerX = bounds.x + width / 2
    val centerY = bounds.y + height / 2
    val monitor = desktop.screens.firstOrNull { it.id == monitorId }
        ?: desktop.screens.firstOrNull { it.contains(centerX, centerY) }
        ?: desktop.screens.firstOrNull { it.id == desktop.primaryId }
        ?: return null

    return SavedWindowState(
        bounds = bounds,
        monitorId = monitor.id,
        monitorBounds = monitor.bounds,
    )
}

internal fun ScreenArea.contains(x: Int, y: Int): Boolean =
    x >= bounds.x &&
        x < bounds.x + bounds.width &&
        y >= bounds.y &&
        y < bounds.y + bounds.height
