package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GraphSelectionScrollUiTest {

    @Test
    fun clickingCommitAfterScrollingDoesNotMoveTheGraph() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val commits = RandomDag.generate(seed = 51, commitCount = 80)
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
                scrollGraphAwayFromTop()

                val visible = visibleCommitRows(commits.size)
                assertTrue(visible.isNotEmpty(), "expected visible commit rows after scrolling")
                val targetRow = visible[visible.size / 2]
                val targetHash = commits[targetRow].hash
                val before = rowTop(UiTestTags.commitRow(targetRow))

                onNodeWithTag(UiTestTags.commitRowForHash(targetHash)).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.selection == Selection.Commit(targetHash)
                }
                waitForIdle()

                val after = rowTop(UiTestTags.commitRow(targetRow))
                assertTrue(
                    abs(after - before) < 1f,
                    "clicking a commit moved the graph from $before to $after",
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun clickingParentLinkDoesNotMoveTheGraph() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val commits = RandomDag.generate(seed = 53, commitCount = 80)
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
                scrollGraphAwayFromTop()

                val visible = visibleCommitRows(commits.size)
                val candidates = visible.filter { commits[it].parents.isNotEmpty() }
                val targetRow = candidates[candidates.size / 2]
                val targetHash = commits[targetRow].hash
                onNodeWithTag(UiTestTags.commitRowForHash(targetHash)).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.selection == Selection.Commit(targetHash)
                }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.commitDetail?.hash == targetHash
                }

                val parentHash = fixture.manager.activeSession.value!!
                    .state.value.commitDetail!!.parents.first()
                val anchorRow = visible[visible.size / 2].takeIf { it != targetRow }
                    ?: visible.first { it != targetRow }
                val anchorTag = UiTestTags.commitRow(anchorRow)
                val before = rowTop(anchorTag)

                onNodeWithTag(UiTestTags.parentLink(parentHash)).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.selection == Selection.Commit(parentHash)
                }
                waitForIdle()

                val after = rowTop(anchorTag)
                assertTrue(
                    abs(after - before) < 1f,
                    "clicking a parent link moved the graph from $before to $after",
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun keyboardEndRevealsTheSelectedRow() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val commits = RandomDag.generate(seed = 55, commitCount = 80)
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
                onNodeWithTag(UiTestTags.GRAPH_LIST).requestFocus()
                waitForIdle()

                onNodeWithTag(UiTestTags.GRAPH_LIST).performKeyInput {
                    keyDown(Key.MoveEnd)
                    keyUp(Key.MoveEnd)
                }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.selection ==
                        Selection.Commit(commits.last().hash)
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag(UiTestTags.commitRowForHash(commits.last().hash))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                onNodeWithTag(UiTestTags.commitRowForHash(commits.last().hash)).assertExists()
                assertEquals(
                    Selection.Commit(commits.last().hash),
                    fixture.manager.activeSession.value?.state?.value?.selection,
                )
            } finally {
                fixture.close()
            }
        }

    private fun ComposeUiTest.scrollGraphAwayFromTop() {
        onNodeWithTag(UiTestTags.GRAPH_LIST).performTouchInput { swipeUp() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(UiTestTags.commitRow(0)).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeUiTest.visibleCommitRows(commitCount: Int): List<Int> =
        (0 until commitCount).filter {
            onAllNodesWithTag(UiTestTags.commitRow(it)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun ComposeUiTest.rowTop(tag: String): Float =
        onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
}
