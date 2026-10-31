package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.core.testing.FakeRepoScenarios
import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SplitterTest {
    private fun settings(
        graph: Float = GraphStyle.GRAPH_PANE_WEIGHT,
        commit: Float = GraphStyle.COMMIT_FILES_FRACTION,
        changes: Float = GraphStyle.CHANGES_FILES_FRACTION,
    ) = AppSettingsController(
        InMemorySettingsStore(
            AppSettings(
                graphPaneFraction = graph,
                commitFilesFraction = commit,
                changesFilesFraction = changes,
            ),
        ),
    )

    @Test
    fun resizeFractionMovesAndClampsToMinimumWidths() {
        assertEquals(
            0.7f,
            resizePaneFraction(
                current = 0.5f,
                deltaPx = 200f,
                contentWidthPx = 1000f,
                minLeftPx = 300f,
                minRightPx = 300f,
            ),
            0.0001f,
        )
        assertEquals(
            0.3f,
            resizePaneFraction(0.5f, -1000f, 1000f, 300f, 300f),
            0.0001f,
        )
        assertEquals(
            0.7f,
            resizePaneFraction(0.5f, 1000f, 1000f, 300f, 300f),
            0.0001f,
        )
    }

    @Test
    fun resizeFractionHandlesZeroAndOverConstrainedWidths() {
        assertEquals(0.5f, resizePaneFraction(0.5f, 100f, 0f, 300f, 300f), 0.0001f)
        assertEquals(0.5f, resizePaneFraction(0.5f, 100f, 400f, 300f, 300f), 0.0001f)
    }

    @Test
    fun detailPanelSplittersResizeAndPersist() = runDesktopComposeUiTest(
        width = 1400,
        height = 900,
    ) {
        val scenario = FakeRepoScenarios.standard()
        val controller = settings()
        val fixture = uiFixture(
            commits = scenario.commits,
            refs = scenario.refs,
            head = scenario.head,
            status = scenario.status,
            commitDetails = scenario.commitDetails,
            changedFiles = scenario.changedFiles,
            diffs = scenario.diffs,
            path = "/repo/split",
        )
        try {
            setContent { App(fixture.manager, settingsController = controller) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            onNodeWithTag(UiTestTags.commitRowForHash(FakeRepoScenarios.mainHash)).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.commitDetail != null
            }
            onNodeWithTag(UiTestTags.changedFile("src/App.kt")).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.openDiff != null
            }
            onNodeWithTag(UiTestTags.COMMIT_SPLITTER).performTouchInput {
                swipe(center, Offset(center.x, center.y + 100f), 250)
            }
            waitForIdle()
            assertTrue(controller.state.value.commitFilesFraction > GraphStyle.COMMIT_FILES_FRACTION)

            onNodeWithTag(UiTestTags.CHANGES_ROW).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.status != null
            }
            onNodeWithTag(UiTestTags.stagedFile("src/Staged.kt")).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.openDiff != null
            }
            onNodeWithTag(UiTestTags.CHANGES_SPLITTER).performTouchInput {
                swipe(center, Offset(center.x, center.y + 100f), 250)
            }
            waitForIdle()
            assertTrue(controller.state.value.changesFilesFraction > GraphStyle.CHANGES_FILES_FRACTION)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun appStartsWithPersistedGraphFraction() = runDesktopComposeUiTest(
        width = 1400,
        height = 900,
    ) {
        val commits = RandomDag.generate(seed = 92, commitCount = 8)
        val controller = settings(graph = 0.7f)
        val fixture = uiFixture(commits = commits)
        try {
            setContent { App(fixture.manager, settingsController = controller) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            val graph = onNodeWithTag(UiTestTags.GRAPH_LIST).getUnclippedBoundsInRoot()
            assertTrue(
                (graph.right - graph.left).value > 900f,
                "expected the saved 70% graph fraction to be applied: $graph",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun draggingTheSplitterChangesPaneWidthsAndIsPersisted() = runDesktopComposeUiTest(
        width = 1400,
        height = 900,
    ) {
        val commits = RandomDag.generate(seed = 91, commitCount = 12)
        val controller = settings()
        val fixture = uiFixture(commits = commits)
        try {
            setContent { App(fixture.manager, settingsController = controller) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            val graphBefore = onNodeWithTag(UiTestTags.GRAPH_LIST).getUnclippedBoundsInRoot()
            val splitter = onNodeWithTag(UiTestTags.SPLITTER).getUnclippedBoundsInRoot()
            onNodeWithTag(UiTestTags.SPLITTER).performTouchInput {
                swipe(
                    start = Offset(center.x, center.y),
                    end = Offset(center.x + 180f, center.y),
                    durationMillis = 250,
                )
            }
            waitForIdle()
            val graphAfter = onNodeWithTag(UiTestTags.GRAPH_LIST).getUnclippedBoundsInRoot()

            assertTrue(
                graphAfter.right > graphBefore.right,
                "expected the graph pane to grow: $graphBefore -> $graphAfter",
            )
            assertTrue(controller.state.value.graphPaneFraction > GraphStyle.GRAPH_PANE_WEIGHT)
            assertTrue((splitter.right - splitter.left).value >= GraphStyle.SPLITTER_HIT_WIDTH.value - 0.5f)
        } finally {
            fixture.close()
        }
    }
}
