package com.jmartinezcampos.freetree.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HistoryViewUiTest {
    private val root = RawCommit(
        hash = "a".repeat(40),
        parents = emptyList(),
        authorName = "Ada Lovelace",
        authorTimeSec = 1_700_000_000L,
        subject = "root commit",
    )
    private val tip = RawCommit(
        hash = "b".repeat(40),
        parents = listOf(root.hash),
        authorName = "Grace Hopper",
        authorTimeSec = 1_700_000_060L,
        subject = "Polish history rows",
    )

    private fun fixture(): UiFixture = uiFixture(
        commits = listOf(tip, root),
        refs = listOf(
            GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, tip.hash, true, null),
            GitRef("refs/tags/v1.0", "v1.0", RefKind.TAG, tip.hash, false, null),
        ),
        head = HeadInfo(tip.hash, "main"),
    )

    @Test
    fun compactModeCanHideTheDateColumnAndExpandTheRowContent() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture()
            var showDate by mutableStateOf(true)
            try {
                setContent { App(fixture.manager, showHistoryDate = showDate) }
                waitForGraph(fixture)

                val dateTag = UiTestTags.historyDate(tip.hash)
                onNodeWithTag(dateTag, useUnmergedTree = true).assertExists()
                val subjectRightWithDate = rightOf(UiTestTags.historySubject(tip.hash))
                saveScreenshot("14-history-compact.png")

                showDate = false
                waitForIdle()

                onNodeWithTag(dateTag, useUnmergedTree = true).assertDoesNotExist()
                val subjectRightWithoutDate = rightOf(UiTestTags.historySubject(tip.hash))
                assertTrue(
                    subjectRightWithoutDate > subjectRightWithDate,
                    "hiding the date should let the row content use the freed space",
                )
                saveScreenshot("15-history-compact-no-date.png")
            } finally {
                fixture.close()
            }
        }

    @Test
    fun comfortableModeUsesTwoRowsWithAuthorAndRefs() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture()
            var viewMode by mutableStateOf(HistoryViewMode.COMPACT)
            var showDate by mutableStateOf(true)
            try {
                setContent {
                    App(
                        fixture.manager,
                        historyViewMode = viewMode,
                        showHistoryDate = showDate,
                    )
                }
                waitForGraph(fixture)

                val rowTag = UiTestTags.commitRowForHash(tip.hash)
                val compactHeight = heightOf(rowTag)
                val compactSubjectTop = topOf(UiTestTags.historySubject(tip.hash))
                val compactAuthorTop = topOf(UiTestTags.historyAuthor(tip.hash))
                assertTrue(
                    kotlin.math.abs(compactAuthorTop - compactSubjectTop) < 2f,
                    "compact mode should keep author and subject on the same line",
                )

                viewMode = HistoryViewMode.COMFORTABLE
                waitForIdle()

                val comfortableHeight = heightOf(rowTag)
                val comfortableSubjectTop = topOf(UiTestTags.historySubject(tip.hash))
                val comfortableAuthorTop = topOf(UiTestTags.historyAuthor(tip.hash))
                assertTrue(comfortableHeight > compactHeight)
                assertTrue(
                    comfortableAuthorTop > comfortableSubjectTop,
                    "comfortable mode should place the author on a second row",
                )
                onNodeWithTag(UiTestTags.historyAuthor(tip.hash), useUnmergedTree = true)
                    .assertTextContains("Grace Hopper")
                onNodeWithTag(UiTestTags.historyDate(tip.hash), useUnmergedTree = true).assertExists()
                onNodeWithTag(rowTag)
                    .assertTextContains("main", substring = true)
                    .assertTextContains("v1.0", substring = true)
                saveScreenshot("16-history-comfortable.png")

                showDate = false
                waitForIdle()
                onNodeWithTag(UiTestTags.historyDate(tip.hash), useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag(UiTestTags.historyAuthor(tip.hash), useUnmergedTree = true).assertExists()
            } finally {
                fixture.close()
            }
        }

    private fun ComposeUiTest.waitForGraph(fixture: UiFixture) {
        waitUntil(timeoutMillis = 5_000) {
            fixture.manager.activeSession.value?.state?.value?.graph != null
        }
    }

    private fun ComposeUiTest.heightOf(tag: String): Float =
        onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height

    private fun ComposeUiTest.topOf(tag: String): Float =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun ComposeUiTest.rightOf(tag: String): Float =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.right
}
