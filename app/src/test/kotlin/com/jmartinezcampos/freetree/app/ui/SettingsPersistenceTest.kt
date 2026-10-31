package com.jmartinezcampos.freetree.app.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SettingsPersistenceTest {

    @Test
    fun jsonStoreRoundTripsTheCompleteSettingsDocument() {
        val directory = Files.createTempDirectory("freetree-settings")
        val file = directory.resolve("preferences.json")
        val store = JsonSettingsStore(file)
        val settings = AppSettings(
            themeMode = ThemeMode.DARK,
            historyViewMode = HistoryViewMode.COMFORTABLE,
            showHistoryDate = false,
            graphPaneFraction = 0.61f,
            commitFilesFraction = 0.42f,
            changesFilesFraction = 0.37f,
            recentRepositories = listOf("/one", "/two"),
            window = SavedWindowState(
                bounds = WindowBounds(10, 20, 900, 700),
                monitorId = "Display 1",
                monitorBounds = WindowBounds(0, 0, 1512, 982),
            ),
        )

        store.save(settings)

        val loaded = assertNotNull(store.load())
        assertEquals(settings, loaded)
        assertEquals(1, loaded.schemaVersion)
        directory.toFile().deleteRecursively()
    }

    @Test
    fun controllerUpdatesAreNormalizedAndPersisted() {
        val store = InMemorySettingsStore()
        val controller = AppSettingsController(store)

        controller.setThemeMode(ThemeMode.LIGHT)
        controller.setHistoryViewMode(HistoryViewMode.COMFORTABLE)
        controller.setShowHistoryDate(false)
        controller.setGraphPaneFraction(2f)
        controller.setCommitFilesFraction(-1f)
        controller.setChangesFilesFraction(Float.NaN)
        controller.setRecentRepositories(listOf("/one", "/one", "/two"))

        val state = controller.state.value
        assertEquals(ThemeMode.LIGHT, state.themeMode)
        assertEquals(HistoryViewMode.COMFORTABLE, state.historyViewMode)
        assertEquals(false, state.showHistoryDate)
        assertEquals(0.95f, state.graphPaneFraction)
        assertEquals(0.05f, state.commitFilesFraction)
        assertEquals(GraphStyle.CHANGES_FILES_FRACTION, state.changesFilesFraction)
        assertEquals(listOf("/one", "/two"), state.recentRepositories)
        assertEquals(state, store.saved)
    }

    @Test
    fun legacyJavaPreferencesAreMigratedIntoTheJsonStore() {
        val directory = Files.createTempDirectory("freetree-settings-migration")
        val file = directory.resolve("preferences.json")
        val node = Preferences.userRoot().node("freetree-migration-test-${UUID.randomUUID()}")
        try {
            node.put("themeMode", "DARK")
            node.put("historyViewMode", "COMFORTABLE")
            node.put("historyShowDate", "false")
            node.putFloat("graphPaneFraction", 0.4f)
            node.putFloat("commitFilesFraction", 0.3f)
            node.putFloat("changesFilesFraction", 0.2f)
            node.put("windowX", "100")
            node.put("windowY", "120")
            node.put("windowWidth", "800")
            node.put("windowHeight", "600")
            node.put("windowMonitorId", "Display 1")
            node.put("windowMonitorX", "0")
            node.put("windowMonitorY", "0")
            node.put("windowMonitorWidth", "1512")
            node.put("windowMonitorHeight", "982")
            val encodedPath = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("/repo/one".toByteArray(StandardCharsets.UTF_8))
            node.put("recentRepositories", encodedPath)

            val controller = AppSettingsController(
                store = JsonSettingsStore(file),
                legacyLoader = LegacySettingsLoader { readLegacySettings(node) },
            )

            val state = controller.state.value
            assertEquals(ThemeMode.DARK, state.themeMode)
            assertEquals(HistoryViewMode.COMFORTABLE, state.historyViewMode)
            assertEquals(false, state.showHistoryDate)
            assertEquals(0.4f, state.graphPaneFraction)
            assertEquals(0.3f, state.commitFilesFraction)
            assertEquals(0.2f, state.changesFilesFraction)
            assertEquals(listOf("/repo/one"), state.recentRepositories)
            assertEquals(
                SavedWindowState(
                    bounds = WindowBounds(100, 120, 800, 600),
                    monitorId = "Display 1",
                    monitorBounds = WindowBounds(0, 0, 1512, 982),
                ),
                state.window,
            )

            val migrated = assertNotNull(JsonSettingsStore(file).load())
            assertEquals(state, migrated)
        } finally {
            node.removeNode()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingLegacyPreferencesLeaveDefaultsInPlace() {
        val node = Preferences.userRoot().node("freetree-empty-test-${UUID.randomUUID()}")
        try {
            assertNull(readLegacySettings(node))
            val controller = AppSettingsController(
                store = InMemorySettingsStore(),
                legacyLoader = LegacySettingsLoader { readLegacySettings(node) },
            )
            assertEquals(AppSettings(), controller.state.value)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun unknownSettingsKeysAreIgnoredForForwardCompatibility() {
        val directory = Files.createTempDirectory("freetree-settings-forward")
        val file = directory.resolve("preferences.json")
        Files.writeString(
            file,
            """
            {
              "schemaVersion": 1,
              "themeMode": "DARK",
              "futureSetting": true
            }
            """.trimIndent(),
        )
        try {
            val loaded = assertNotNull(JsonSettingsStore(file).load())
            assertEquals(ThemeMode.DARK, loaded.themeMode)
            assertEquals(ThemeMode.SYSTEM, AppSettings().themeMode)
            assertEquals(HistoryViewMode.COMPACT, loaded.historyViewMode)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidSettingsFileFallsBackToDefaults() {
        val directory = Files.createTempDirectory("freetree-settings-corrupt")
        val file = directory.resolve("preferences.json")
        Files.writeString(file, "{not valid json")
        try {
            val controller = AppSettingsController(JsonSettingsStore(file))
            assertEquals(AppSettings(), controller.state.value)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
