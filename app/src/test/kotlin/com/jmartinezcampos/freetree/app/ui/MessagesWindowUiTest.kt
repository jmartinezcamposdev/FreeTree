package com.jmartinezcampos.freetree.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.core.session.MessageLog
import com.jmartinezcampos.freetree.core.session.UserMessage
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class MessagesWindowUiTest {

    @Test
    fun emptyLogShowsPlaceholder() =
        runDesktopComposeUiTest(width = 900, height = 500) {
            val log = MessageLog()
            setContent { AppTheme(ThemeMode.DARK) { MessagesLogContent(log) } }

            onNodeWithTag(UiTestTags.MESSAGES_WINDOW).assertExists()
            onNodeWithTag(UiTestTags.MESSAGES_EMPTY).assertExists()
            onNodeWithText("No messages yet.").assertExists()
        }

    @Test
    fun rowsAreCompactAndOnlyTheArrowExpandsThem() =
        runDesktopComposeUiTest(width = 900, height = 500) {
            val log = MessageLog()
            log.record(1_000L, Path.of("/repo/alpha"), UserMessage.Info("Index updated."))
            log.record(2_000L, Path.of("/repo/beta"), UserMessage.Error("Git command failed.", "fatal: boom"))
            setContent { AppTheme(ThemeMode.DARK) { MessagesLogContent(log) } }

            onNodeWithText("INFO").assertExists()
            onNodeWithText("ERROR").assertExists()
            onNodeWithText("alpha").assertExists()
            onNodeWithText("beta").assertExists()
            onNodeWithText("Index updated.").assertExists()
            onNodeWithTag(UiTestTags.messageLogRow(1)).assertHasNoClickAction()
            onNodeWithTag(UiTestTags.messageLogCopy(1)).assertExists()
            onAllNodesWithText("▲").assertCountEquals(2)

            val entries = log.entries.value
            assertEquals("Index updated.", messageCopyText(entries[0]))
            assertEquals("Git command failed.\nfatal: boom", messageCopyText(entries[1]))

            onNodeWithText("fatal: boom").assertDoesNotExist()
            onNodeWithText("/repo/beta").assertDoesNotExist()

            onNodeWithTag(UiTestTags.messageLogToggle(1)).performClick()
            waitForIdle()

            onNodeWithText("/repo/beta").assertExists()
            onNodeWithText("fatal: boom").assertExists()
            onAllNodesWithText("▲").assertCountEquals(1)
            onAllNodesWithText("▼").assertCountEquals(1)
            saveScreenshot("09-messages-log.png")

            onNodeWithTag(UiTestTags.messageLogToggle(1)).performClick()
            waitForIdle()

            onNodeWithText("fatal: boom").assertDoesNotExist()
            onAllNodesWithText("▲").assertCountEquals(2)
        }

    @Test
    fun themeOptionsInvokeTheSelectedMode() =
        runDesktopComposeUiTest(width = 500, height = 500) {
            var selected: ThemeMode? = null
            setContent {
                AppTheme(ThemeMode.SYSTEM) {
                    PreferencesContent(
                        themeMode = ThemeMode.SYSTEM,
                        onThemeModeChange = { selected = it },
                    )
                }
            }

            onNodeWithTag(UiTestTags.PREFERENCES_WINDOW).assertExists()
            onNodeWithTag(UiTestTags.THEME_SYSTEM).assertExists()
            onNodeWithTag(UiTestTags.THEME_LIGHT).assertExists()
            onNodeWithTag(UiTestTags.THEME_DARK).assertExists()

            onNodeWithTag(UiTestTags.THEME_DARK).performClick()
            waitForIdle()

            assertEquals(ThemeMode.DARK, selected)
            saveScreenshot("10-preferences.png")
        }

    @Test
    fun historyOptionsInvokeCallbacks() =
        runDesktopComposeUiTest(width = 500, height = 500) {
            var historyMode by mutableStateOf(HistoryViewMode.COMPACT)
            var showDate by mutableStateOf(true)
            setContent {
                AppTheme(ThemeMode.SYSTEM) {
                    PreferencesContent(
                        themeMode = ThemeMode.SYSTEM,
                        onThemeModeChange = {},
                        historyViewMode = historyMode,
                        onHistoryViewModeChange = { historyMode = it },
                        showHistoryDate = showDate,
                        onShowHistoryDateChange = { showDate = it },
                    )
                }
            }

            onNodeWithTag(UiTestTags.HISTORY_COMPACT).assertExists()
            onNodeWithTag(UiTestTags.HISTORY_SHOW_DATE).assertExists()

            onNodeWithTag(UiTestTags.HISTORY_COMFORTABLE).performClick()
            waitForIdle()
            assertEquals(HistoryViewMode.COMFORTABLE, historyMode)

            onNodeWithTag(UiTestTags.HISTORY_SHOW_DATE).performClick()
            waitForIdle()
            assertFalse(showDate)

            saveScreenshot("17-preferences-history.png")
        }
}
