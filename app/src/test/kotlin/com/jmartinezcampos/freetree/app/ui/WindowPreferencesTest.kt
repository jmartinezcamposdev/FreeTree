package com.jmartinezcampos.freetree.app.ui

import java.util.UUID
import java.util.prefs.Preferences
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowPreferencesTest {

    private val display = ScreenArea(
        id = "Display 1",
        bounds = WindowBounds(0, 0, 1512, 982),
        workArea = WindowBounds(0, 38, 1441, 944),
    )

    @Test
    fun windowPreferencesRoundTrip() {
        val node = Preferences.userRoot().node("freetree-window-test-${UUID.randomUUID()}")
        try {
            val preferences = PreferencesWindowPreferences(node)
            assertNull(preferences.load())

            val saved = SavedWindowState(
                bounds = WindowBounds(120, 160, 900, 640),
                monitorId = "Display 1",
                monitorBounds = WindowBounds(0, 0, 1512, 982),
            )
            preferences.save(saved)

            assertEquals(saved, preferences.load())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun missingOrCorruptWindowPreferencesReturnNull() {
        val node = Preferences.userRoot().node("freetree-window-test-${UUID.randomUUID()}")
        try {
            val preferences = PreferencesWindowPreferences(node)
            node.put("windowX", "not-a-number")
            assertNull(preferences.load())

            node.put("windowX", "10")
            node.put("windowY", "20")
            node.put("windowWidth", "800")
            node.put("windowHeight", "600")
            node.put("windowMonitorId", "Display 1")
            node.put("windowMonitorX", "0")
            node.put("windowMonitorY", "0")
            node.put("windowMonitorWidth", "0")
            node.put("windowMonitorHeight", "982")
            assertNull(preferences.load())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun noSavedStateOpensCenteredAtDefaultSize() {
        val bounds = resolveWindowBounds(
            saved = null,
            screens = listOf(display),
            primaryId = display.id,
            defaultWidth = 1180,
            defaultHeight = 760,
            minWidth = 720,
            minHeight = 480,
        )

        assertEquals(WindowBounds(130, 130, 1180, 760), bounds)
    }

    @Test
    fun sameMonitorRestoresSavedBounds() {
        val saved = SavedWindowState(
            bounds = WindowBounds(140, 170, 900, 640),
            monitorId = display.id,
            monitorBounds = display.bounds,
        )

        val bounds = resolveWindowBounds(
            saved = saved,
            screens = listOf(display),
            primaryId = display.id,
            defaultWidth = 1180,
            defaultHeight = 760,
            minWidth = 720,
            minHeight = 480,
        )

        assertEquals(WindowBounds(140, 170, 900, 640), bounds)
    }

    @Test
    fun changedMonitorSizeResetsToDefaultSizeAndCenter() {
        val changedDisplay = ScreenArea(
            id = "Display 1",
            bounds = WindowBounds(0, 0, 1920, 1080),
            workArea = WindowBounds(0, 25, 1920, 1055),
        )
        val saved = SavedWindowState(
            bounds = WindowBounds(140, 170, 900, 640),
            monitorId = changedDisplay.id,
            monitorBounds = display.bounds,
        )

        val bounds = resolveWindowBounds(
            saved = saved,
            screens = listOf(changedDisplay),
            primaryId = changedDisplay.id,
            defaultWidth = 1180,
            defaultHeight = 760,
            minWidth = 720,
            minHeight = 480,
        )

        assertEquals(WindowBounds(370, 172, 1180, 760), bounds)
    }

    @Test
    fun disconnectedMonitorFallsBackToPrimary() {
        val secondary = ScreenArea(
            id = "Display 2",
            bounds = WindowBounds(1512, 0, 1920, 1080),
            workArea = WindowBounds(1512, 25, 1920, 1055),
        )
        val saved = SavedWindowState(
            bounds = WindowBounds(1700, 150, 900, 640),
            monitorId = secondary.id,
            monitorBounds = secondary.bounds,
        )

        val bounds = resolveWindowBounds(
            saved = saved,
            screens = listOf(display),
            primaryId = display.id,
            defaultWidth = 1180,
            defaultHeight = 760,
            minWidth = 720,
            minHeight = 480,
        )

        assertEquals(WindowBounds(130, 130, 1180, 760), bounds)
    }

    @Test
    fun offScreenSavedBoundsAreClampedInsideTheWorkArea() {
        val saved = SavedWindowState(
            bounds = WindowBounds(5000, 5000, 900, 640),
            monitorId = display.id,
            monitorBounds = display.bounds,
        )

        val bounds = resolveWindowBounds(
            saved = saved,
            screens = listOf(display),
            primaryId = display.id,
            defaultWidth = 1180,
            defaultHeight = 760,
            minWidth = 720,
            minHeight = 480,
        )

        assertEquals(WindowBounds(541, 342, 900, 640), bounds)
    }

    @Test
    fun oversizedSavedWindowIsClampedToTheWorkArea() {
        val saved = SavedWindowState(
            bounds = WindowBounds(-100, -100, 3000, 2000),
            monitorId = display.id,
            monitorBounds = display.bounds,
        )

        val bounds = resolveWindowBounds(
            saved = saved,
            screens = listOf(display),
            primaryId = display.id,
            defaultWidth = 1180,
            defaultHeight = 760,
            minWidth = 720,
            minHeight = 480,
        )

        assertEquals(WindowBounds(0, 38, 1441, 944), bounds)
    }

    @Test
    fun windowFollowsAMonitorThatWasRepositionedWithoutResizing() {
        val movedDisplay = display.copy(bounds = WindowBounds(1512, 0, 1512, 982))
        val saved = SavedWindowState(
            bounds = WindowBounds(140, 170, 900, 640),
            monitorId = display.id,
            monitorBounds = display.bounds,
        )

        val bounds = resolveWindowBounds(
            saved = saved,
            screens = listOf(movedDisplay.copy(workArea = WindowBounds(1512, 38, 1441, 944))),
            primaryId = movedDisplay.id,
            defaultWidth = 1180,
            defaultHeight = 760,
            minWidth = 720,
            minHeight = 480,
        )

        assertEquals(WindowBounds(1652, 170, 900, 640), bounds)
    }

    @Test
    fun capturesTheWindowGeometryAndItsMonitor() {
        val state = WindowState(
            position = WindowPosition(140.dp, 170.dp),
            size = DpSize(900.dp, 640.dp),
        )

        val saved = captureWindowState(
            windowState = state,
            desktop = DesktopScreens(screens = listOf(display), primaryId = display.id),
            monitorId = display.id,
        )

        assertEquals(
            SavedWindowState(
                bounds = WindowBounds(140, 170, 900, 640),
                monitorId = display.id,
                monitorBounds = display.bounds,
            ),
            saved,
        )
    }

    @Test
    fun doesNotCaptureAWindowWithoutSpecifiedGeometry() {
        val state = WindowState()

        assertNull(
            captureWindowState(
                windowState = state,
                desktop = DesktopScreens(screens = listOf(display), primaryId = display.id),
                monitorId = display.id,
            ),
        )
    }
}
