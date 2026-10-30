@file:OptIn(ExperimentalTestApi::class)

package com.jmartinezcampos.freetree.app.polish

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.ui.GraphStyle
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.core.session.Selection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent acceptance checks for the history graph scrollbar and for click/keyboard scrolling
 * behavior. These deliberately drive the real `App` shell over the fake backend.
 */
class PolishGraphScrollbarUiTest {

    private val commitCount = 80

    @Test
    fun graphScrollbarIsVisibleBeforeAnyScrolling() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = polishGraphFixture(commitCount = commitCount, seed = 101)
            try {
                setContent { App(fixture.manager) }
                awaitGraph(fixture)

                // No scrolling has happened yet: the first commit row is on screen and the
                // scrollbar must already be there with a non-empty thumb.
                onNodeWithTag(UiTestTags.commitRow(0)).assertExists()
                val bar = onNodeWithTag(UiTestTags.GRAPH_SCROLLBAR)
                bar.assertExists()

                val bounds = bar.getUnclippedBoundsInRoot()
                assertEquals(
                    GraphStyle.SCROLLBAR_GUTTER.value,
                    (bounds.right - bounds.left).value,
                    0.5f,
                    "graph scrollbar should own the reserved gutter width",
                )
                assertTrue(
                    (bounds.bottom - bounds.top).value > 200f,
                    "graph scrollbar should span the graph pane",
                )

                val barImage = bar.captureToImage()
                assertTrue(barImage.width > 0 && barImage.height > 0)
                assertTrue(
                    barImage.distinctColorCount() >= 2,
                    "an always-visible scrollbar should render a track and a thumb before scrolling",
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun draggingGraphScrollbarChangesTheVisibleGraphPosition() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = polishGraphFixture(commitCount = commitCount, seed = 103)
            try {
                setContent { App(fixture.manager) }
                awaitGraph(fixture)

                val firstBefore = visibleCommitRows(commitCount).first()
                assertEquals(0, firstBefore, "graph should start at the newest commit")
                val barBefore = onNodeWithTag(UiTestTags.GRAPH_SCROLLBAR).captureToImage()

                dragScrollbar(UiTestTags.GRAPH_SCROLLBAR, toFraction = 0.85f)
                waitForIdle()

                val visibleAfter = visibleCommitRows(commitCount)
                assertTrue(visibleAfter.isNotEmpty(), "rows should still be rendered after dragging")
                assertTrue(
                    visibleAfter.first() > firstBefore,
                    "dragging the scrollbar down should move the graph window (was ${visibleAfter.first()})",
                )
                assertTrue(
                    onAllNodesWithTag(UiTestTags.commitRow(0)).fetchSemanticsNodes().isEmpty(),
                    "the first graph row should have left the viewport after dragging down",
                )

                val barAfter = onNodeWithTag(UiTestTags.GRAPH_SCROLLBAR).captureToImage()
                assertFalse(
                    barBefore.samePixelsAs(barAfter),
                    "the thumb should visibly move after dragging the scrollbar",
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun clickingCommitAfterScrollbarScrollKeepsFirstVisibleRowInPlace() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = polishGraphFixture(commitCount = commitCount, seed = 107)
            try {
                setContent { App(fixture.manager) }
                awaitGraph(fixture)
                val graphHashes = fixture.manager.activeSession.value!!
                    .state.value.graph!!
                    .let { graph -> (0 until commitCount).map { graph.hash(it) } }

                // Move the graph to an arbitrary later position with the scrollbar.
                dragScrollbar(UiTestTags.GRAPH_SCROLLBAR, toFraction = 0.55f)
                waitForIdle()

                val viewport = onNodeWithTag(UiTestTags.GRAPH_LIST).getUnclippedBoundsInRoot()
                val topsBefore = commitRowTops(commitCount)
                // Prefetched rows report Dp.Unspecified (NaN) tops; their floats sort above
                // everything, so filter them out explicitly.
                val placedTops = topsBefore.filterValues { it.value.isFinite() }
                // Ignore rows clipped at the very top edge: lazy prefetch can report stale bounds
                // there. One row below the edge is a stable anchor for the visible window.
                val anchorRow = placedTops.entries
                    .filter { it.value >= viewport.top + GraphStyle.ROW_HEIGHT }
                    .minBy { it.key }
                    .key
                val anchorTag = UiTestTags.commitRow(anchorRow)
                val anchorTopBefore = rowTop(anchorTag)
                val visibleBefore = placedTops.keys.filter { it >= anchorRow }
                assertTrue(anchorRow > 0, "expected the graph to be scrolled away from the top")
                assertTrue(visibleBefore.size >= 3, "expected several rows after scrolling, got $visibleBefore")

                val targetRow = anchorRow + visibleBefore.size / 2
                val targetHash = graphHashes[targetRow]
                val thumbBefore = scrollbarPaint(UiTestTags.GRAPH_SCROLLBAR).thumb
                val targetTopBefore = rowTop(UiTestTags.commitRow(targetRow))

                onNodeWithTag(UiTestTags.commitRowForHash(targetHash)).performClick()
                awaitSelection(fixture, Selection.Commit(targetHash))
                awaitCommitDetail(fixture, targetHash)
                waitForIdle()

                // The first row of the visible window (ignoring the clipped edge) must not move.
                val anchorAfter = commitRowTops(commitCount).entries
                    .filter { it.value.value.isFinite() && it.value >= viewport.top + GraphStyle.ROW_HEIGHT }
                    .minBy { it.key }
                    .key
                assertEquals(
                    anchorRow,
                    anchorAfter,
                    "clicking row $targetRow changed the first visible graph row",
                )
                assertTrue(
                    abs(rowTop(anchorTag) - anchorTopBefore) < 1f,
                    "clicking row $targetRow moved the graph from $anchorTopBefore to ${rowTop(anchorTag)}",
                )
                assertTrue(
                    abs(rowTop(UiTestTags.commitRow(targetRow)) - targetTopBefore) < 1f,
                    "clicking a commit moved its own row",
                )
                assertNotNull(thumbBefore, "expected a visible scrollbar thumb before clicking")
                assertEquals(
                    thumbBefore,
                    scrollbarPaint(UiTestTags.GRAPH_SCROLLBAR).thumb,
                    "the scrollbar thumb moved, so the visible graph position changed on click",
                )
                onNodeWithTag(UiTestTags.commitRowForHash(targetHash)).assertIsSelected()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun keyboardNavigationRevealsSelectedCommitAfterScrollbarScroll() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = polishGraphFixture(commitCount = commitCount, seed = 109)
            try {
                setContent { App(fixture.manager) }
                awaitGraph(fixture)
                val graph = fixture.manager.activeSession.value!!.state.value.graph!!
                val lastHash = graph.hash(graph.rowCount - 1)

                dragScrollbar(UiTestTags.GRAPH_SCROLLBAR, toFraction = 0.45f)
                waitForIdle()
                assertTrue(
                    onAllNodesWithTag(UiTestTags.commitRowForHash(lastHash))
                        .fetchSemanticsNodes()
                        .isEmpty(),
                    "precondition: the newest-to-oldest list should be scrolled away from the last commit",
                )

                onNodeWithTag(UiTestTags.GRAPH_LIST).requestFocus()
                waitForIdle()
                onNodeWithTag(UiTestTags.GRAPH_LIST).performKeyInput {
                    keyDown(Key.MoveEnd)
                    keyUp(Key.MoveEnd)
                }
                awaitSelection(fixture, Selection.Commit(lastHash))
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag(UiTestTags.commitRowForHash(lastHash))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                waitForIdle()

                val listBounds = onNodeWithTag(UiTestTags.GRAPH_LIST).getUnclippedBoundsInRoot()
                val rowBounds = onNodeWithTag(UiTestTags.commitRowForHash(lastHash))
                    .getUnclippedBoundsInRoot()
                assertTrue(
                    rowBounds.top.value >= listBounds.top.value - 1f,
                    "selected row is above the viewport: ${rowBounds.top} < ${listBounds.top}",
                )
                assertTrue(
                    rowBounds.bottom.value <= listBounds.bottom.value + 1f,
                    "selected row is below the viewport: ${rowBounds.bottom} > ${listBounds.bottom}",
                )
            } finally {
                fixture.close()
            }
        }
}
