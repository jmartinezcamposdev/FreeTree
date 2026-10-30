package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.dragAndDrop
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ScrollbarUiTest {

    @Test
    fun draggingGraphScrollbarChangesVisibleGraphPosition() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val commits = RandomDag.generate(seed = 41, commitCount = 80)
            val fixture = uiFixture(
                commits = commits,
                refs = listOf(GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null)),
                head = HeadInfo(commits[0].hash, "main"),
            )
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.graph != null
                }
                onNodeWithTag(UiTestTags.GRAPH_SCROLLBAR).assertExists()
                onNodeWithTag(UiTestTags.commitRow(0)).assertExists()

                onNodeWithTag(UiTestTags.GRAPH_SCROLLBAR).performMouseInput {
                    dragAndDrop(
                        start = Offset(centerX, 20f),
                        end = Offset(centerX, height * 0.8f),
                    )
                }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag(UiTestTags.commitRow(0)).fetchSemanticsNodes().isEmpty()
                }
                val visibleRows = (0 until commits.size).filter {
                    onAllNodesWithTag(UiTestTags.commitRow(it)).fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(visibleRows.isNotEmpty(), "graph should still render rows after dragging the scrollbar")
                assertTrue(visibleRows.first() > 0, "dragging down should move the graph away from the first row")
            } finally {
                fixture.close()
            }
        }

    @Test
    fun diffViewHasFloatingScrollbarAndStillScrolls() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val lines = (1..200).map { number ->
                DiffLine(DiffLineKind.CONTEXT, number, number, "line $number")
            }
            setContent {
                CompositionLocalProvider(LocalGraphColors provides GraphStyle.dark) {
                    MaterialTheme {
                        DiffView(
                            diff = DiffText(lines, binary = false, truncated = false),
                            loading = false,
                            error = null,
                            onLoadFull = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            onNodeWithTag(UiTestTags.DIFF_SCROLLBAR).assertExists()
            onNodeWithTag(UiTestTags.diffLine(1, 1)).assertExists()

            onNodeWithTag(UiTestTags.DIFF_VIEW).performTouchInput { swipeUp() }

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(UiTestTags.diffLine(1, 1)).fetchSemanticsNodes().isEmpty()
            }
            onNodeWithTag(UiTestTags.DIFF_SCROLLBAR).assertExists()
        }

    @Test
    fun commitPanelExposesChangedFilesAndDiffScrollbars() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val root = com.jmartinezcampos.freetree.core.model.RawCommit(
                "a".repeat(40),
                emptyList(),
                "Ada",
                1_700_000_000L,
                "root",
            )
            val tip = com.jmartinezcampos.freetree.core.model.RawCommit(
                "b".repeat(40),
                listOf(root.hash),
                "Grace",
                1_700_000_060L,
                "tip",
            )
            val files = (0 until 80).map { FileChange('M', "file-$it.txt", null) }
            val firstFile = files.first()
            val diff = DiffText(
                lines = (1..80).map { number ->
                    DiffLine(DiffLineKind.CONTEXT, number, number, "line $number")
                },
                binary = false,
                truncated = false,
            )
            val fixture = uiFixture(
                commits = listOf(tip, root),
                refs = listOf(GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, tip.hash, true, null)),
                head = HeadInfo(tip.hash, "main"),
                changedFiles = mapOf(tip.hash to files),
                diffs = mapOf(DiffTarget.InCommit(tip.hash, firstFile) to diff),
            )
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.graph != null
                }
                onNodeWithTag(UiTestTags.commitRowForHash(tip.hash)).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.commitDetail != null
                }

                onNodeWithTag(UiTestTags.COMMIT_FILES_SCROLLBAR).assertExists()
                onNodeWithTag(UiTestTags.changedFile(firstFile.path)).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.openDiff != null
                }
                onNodeWithTag(UiTestTags.DIFF_SCROLLBAR).assertExists()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun changesPanelHasFloatingScrollbarAndStillScrolls() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val commits = RandomDag.generate(seed = 43, commitCount = 4)
            val entries = (0 until 80).map { number ->
                StatusEntry("working-$number.txt", null, ' ', 'M', StatusKind.ORDINARY)
            }
            val fixture = uiFixture(
                commits = commits,
                refs = listOf(GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null)),
                head = HeadInfo(commits[0].hash, "main"),
                status = WorkingStatus(commits[0].hash, null, 0, 80, false, entries),
            )
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.status != null
                }
                onNodeWithTag(UiTestTags.CHANGES_SCROLLBAR).assertExists()

                val first = UiTestTags.unstagedFile("working-0.txt")
                val before = onNodeWithTag(first).fetchSemanticsNode().boundsInRoot.top
                onNodeWithTag(first).performTouchInput { swipeUp() }
                waitForIdle()
                val after = onNodeWithTag(first).fetchSemanticsNode().boundsInRoot.top
                assertTrue(after < before, "swiping the changes list should move its content upwards")
                onNodeWithTag(UiTestTags.CHANGES_SCROLLBAR).assertExists()
            } finally {
                fixture.close()
            }
        }
}
