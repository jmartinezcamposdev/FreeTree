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
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SplitterTest {
    private class InMemorySplitPreferences(
        var value: Float = GraphStyle.GRAPH_PANE_WEIGHT,
        var commitValue: Float = GraphStyle.COMMIT_FILES_FRACTION,
        var changesValue: Float = GraphStyle.CHANGES_FILES_FRACTION,
    ) : SplitPreferences {
        override fun loadGraphFraction(): Float = value

        override fun saveGraphFraction(value: Float) {
            this.value = value
        }

        override fun loadCommitFilesFraction(): Float = commitValue

        override fun saveCommitFilesFraction(value: Float) {
            commitValue = value
        }

        override fun loadChangesFilesFraction(): Float = changesValue

        override fun saveChangesFilesFraction(value: Float) {
            changesValue = value
        }
    }

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
    fun splitPreferencesRoundTripsAndClamps() {
        val node = Preferences.userRoot().node("freetree-test-${UUID.randomUUID()}")
        try {
            val preferences = PreferencesSplitPreferences(node)
            assertEquals(GraphStyle.GRAPH_PANE_WEIGHT, preferences.loadGraphFraction(), 0.0001f)
            assertEquals(GraphStyle.COMMIT_FILES_FRACTION, preferences.loadCommitFilesFraction(), 0.0001f)
            assertEquals(GraphStyle.CHANGES_FILES_FRACTION, preferences.loadChangesFilesFraction(), 0.0001f)

            preferences.saveGraphFraction(0.42f)
            preferences.saveCommitFilesFraction(0.44f)
            preferences.saveChangesFilesFraction(0.46f)
            assertEquals(0.42f, preferences.loadGraphFraction(), 0.0001f)
            assertEquals(0.44f, preferences.loadCommitFilesFraction(), 0.0001f)
            assertEquals(0.46f, preferences.loadChangesFilesFraction(), 0.0001f)

            preferences.saveGraphFraction(2f)
            assertEquals(0.95f, preferences.loadGraphFraction(), 0.0001f)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun detailPanelSplittersResizeAndPersist() = runDesktopComposeUiTest(
        width = 1400,
        height = 900,
    ) {
        val scenario = FakeRepoScenarios.standard()
        val preferences = InMemorySplitPreferences()
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
            setContent { App(fixture.manager, splitPreferences = preferences) }
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
            assertTrue(preferences.commitValue > GraphStyle.COMMIT_FILES_FRACTION)

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
            assertTrue(preferences.changesValue > GraphStyle.CHANGES_FILES_FRACTION)
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
        val preferences = InMemorySplitPreferences(value = 0.7f)
        val fixture = uiFixture(commits = commits)
        try {
            setContent { App(fixture.manager, splitPreferences = preferences) }
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
        val preferences = InMemorySplitPreferences()
        val fixture = uiFixture(commits = commits)
        try {
            setContent { App(fixture.manager, splitPreferences = preferences) }
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
            assertTrue(preferences.value > GraphStyle.GRAPH_PANE_WEIGHT)
            assertTrue((splitter.right - splitter.left).value >= GraphStyle.SPLITTER_HIT_WIDTH.value - 0.5f)
        } finally {
            fixture.close()
        }
    }
}
