@file:OptIn(ExperimentalTestApi::class)

package com.jmartinezcampos.freetree.app.polish

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.core.session.Selection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance checks for the transient scrollbars attached to the commit changed-files list and to
 * the diff panel: the bars exist, appear while the content scrolls, and their thumbs drag content.
 */
class PolishDetailScrollbarUiTest {

    private val fileCount = 60
    private val diffLineCount = 200

    @Test
    fun commitChangedFilesScrollbarIsPresentAndDragsTheList() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val scenario = polishCommitScenario(fileCount = fileCount, diffLineCount = diffLineCount)
            try {
                setContent { App(scenario.fixture.manager) }
                awaitGraph(scenario.fixture)
                openCommit(scenario)

                val bar = onNodeWithTag(UiTestTags.COMMIT_FILES_SCROLLBAR)
                bar.assertExists()
                assertEquals(
                    GraphStyle.SCROLLBAR_GUTTER.value,
                    bar.getUnclippedBoundsInRoot().let { (it.right - it.left).value },
                    0.5f,
                    "changed-files scrollbar should own the gutter",
                )

                // Transient bars are hidden at rest and only appear while scrolling.
                val atRest = scrollbarPaint(UiTestTags.COMMIT_FILES_SCROLLBAR)
                assertFalse(atRest.visible, "the changed-files scrollbar should be hidden at rest")
                assertNull(atRest.thumb)

                wheelScroll(UiTestTags.changedFile("file-0.txt"), delta = 3f)
                waitForIdle()
                val afterWheel = scrollbarPaint(UiTestTags.COMMIT_FILES_SCROLLBAR)
                assertTrue(afterWheel.visible, "the changed-files scrollbar should fade in on scroll")
                val wheelThumb = assertNotNull(
                    afterWheel.thumb,
                    "the revealed changed-files scrollbar should paint a thumb",
                )

                // File positions inside the list are clipped once they leave the viewport, so use
                // the first fully visible file for the content check (scanned last, because node
                // queries advance test time and hide the transient bar again).
                dragPaintedThumb(UiTestTags.COMMIT_FILES_SCROLLBAR, travel = 0.35f)
                waitForIdle()

                val afterDrag = scrollbarPaint(UiTestTags.COMMIT_FILES_SCROLLBAR)
                assertTrue(afterDrag.visible, "the scrollbar should stay visible while dragging")
                val dragThumb = assertNotNull(afterDrag.thumb)
                assertTrue(
                    dragThumb.first > wheelThumb.first,
                    "dragging the changed-files thumb down should move it " +
                        "($wheelThumb -> $dragThumb)",
                )
                val viewportTop = bar.getUnclippedBoundsInRoot().top
                assertTrue(
                    firstChangedFile(viewportTop) > 0,
                    "dragging the changed-files scrollbar should advance past file-0.txt",
                )
                bar.assertExists()
            } finally {
                scenario.close()
            }
        }

    @Test
    fun diffScrollbarIsPresentAndDragsTheDiff() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val scenario = polishCommitScenario(fileCount = fileCount, diffLineCount = diffLineCount)
            try {
                setContent { App(scenario.fixture.manager) }
                awaitGraph(scenario.fixture)
                openCommit(scenario)
                openFirstDiff(scenario)

                val bar = onNodeWithTag(UiTestTags.DIFF_SCROLLBAR)
                bar.assertExists()
                onNodeWithTag(UiTestTags.DIFF_VIEW).assertExists()
                assertEquals(
                    GraphStyle.SCROLLBAR_GUTTER.value,
                    bar.getUnclippedBoundsInRoot().let { (it.right - it.left).value },
                    0.5f,
                    "diff scrollbar should own the gutter",
                )
                onNodeWithTag(UiTestTags.diffLine(1, 1)).assertExists()

                val atRest = scrollbarPaint(UiTestTags.DIFF_SCROLLBAR)
                assertFalse(atRest.visible, "the diff scrollbar should be hidden at rest")
                assertNull(atRest.thumb)

                wheelScroll(UiTestTags.DIFF_VIEW, delta = 3f)
                waitForIdle()
                val afterWheel = scrollbarPaint(UiTestTags.DIFF_SCROLLBAR)
                assertTrue(afterWheel.visible, "the diff scrollbar should fade in on scroll")
                val wheelThumb = assertNotNull(
                    afterWheel.thumb,
                    "the revealed diff scrollbar should paint a thumb",
                )

                dragPaintedThumb(UiTestTags.DIFF_SCROLLBAR, travel = 0.35f)
                waitForIdle()

                val afterDrag = scrollbarPaint(UiTestTags.DIFF_SCROLLBAR)
                assertTrue(afterDrag.visible, "the diff scrollbar should stay visible while dragging")
                val dragThumb = assertNotNull(afterDrag.thumb)
                assertTrue(
                    dragThumb.first > wheelThumb.first,
                    "dragging the diff thumb down should move it ($wheelThumb -> $dragThumb)",
                )

                // The diff content actually advanced: the first visible line is beyond line 1.
                val viewportTop = bar.getUnclippedBoundsInRoot().top
                assertTrue(
                    firstDiffLine(viewportTop) > 1,
                    "dragging the diff scrollbar should scroll past the first line",
                )
            } finally {
                scenario.close()
            }
        }

    private fun ComposeUiTest.openCommit(scenario: PolishCommitScenario) {
        onNodeWithTag(UiTestTags.commitRowForHash(scenario.tip.hash)).performClick()
        awaitSelection(scenario.fixture, Selection.Commit(scenario.tip.hash))
        awaitCommitDetail(scenario.fixture, scenario.tip.hash)
        awaitChangedFiles(scenario.fixture, fileCount)
        waitForIdle()
    }

    private fun ComposeUiTest.openFirstDiff(scenario: PolishCommitScenario) {
        onNodeWithTag(UiTestTags.changedFile(scenario.files.first().path)).performClick()
        waitUntil(timeoutMillis = 5_000) {
            scenario.fixture.manager.activeSession.value?.state?.value?.openDiff != null
        }
        waitForIdle()
    }

    /**
     * Lowest changed-file index that is fully below the list viewport's top edge.
     */
    private fun ComposeUiTest.firstChangedFile(viewportTop: Dp): Int =
        (0 until fileCount)
            .mapNotNull { index ->
                runCatching {
                    index to onNodeWithTag(UiTestTags.changedFile("file-$index.txt"))
                        .getUnclippedBoundsInRoot()
                        .top
                }.getOrNull()
            }
            .filter { (_, top) -> top.value.isFinite() && top >= viewportTop }
            .minByOrNull { it.first }
            ?.first
            ?: fileCount

    /**
     * First diff line far enough below the viewport top to avoid lazy prefetch rows whose semantic
     * bounds are clipped at the edge.
     */
    private fun ComposeUiTest.firstDiffLine(viewportTop: Dp): Int =
        (1..diffLineCount)
            .mapNotNull { number ->
                runCatching {
                    number to onNodeWithTag(UiTestTags.diffLine(number, number))
                        .getUnclippedBoundsInRoot()
                        .top
                }.getOrNull()
            }
            .filter { (_, top) ->
                top.value.isFinite() && top >= viewportTop + GraphStyle.DIFF_ROW_HEIGHT
            }
            .minByOrNull { it.first }
            ?.first
            ?: 0
}
