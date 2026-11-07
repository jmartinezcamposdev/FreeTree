package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.app.menu.stagedPaths
import com.jmartinezcampos.freetree.core.graph.SegKind
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GraphListUiTest {

    @Test
    fun chipAppearanceHighlightsCurrentBranchAndHead() {
        val colors = GraphStyle.dark
        assertEquals(
            colors.chipCurrentBranchBackground to colors.chipCurrentBranchForeground,
            chipAppearance(RefKind.LOCAL_BRANCH, isHead = false, isCurrentBranch = true, colors),
        )
        assertEquals(
            colors.chipHeadBackground to colors.chipHeadForeground,
            chipAppearance(RefKind.LOCAL_BRANCH, isHead = true, isCurrentBranch = true, colors),
        )
        assertEquals(
            colors.chipLocalBackground to colors.chipLocalForeground,
            chipAppearance(RefKind.LOCAL_BRANCH, isHead = false, isCurrentBranch = false, colors),
        )
    }

    @Test
    fun changesRowIsItemZeroAndCommitRowsRenderWithChips() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val commits = RandomDag.generate(seed = 7, commitCount = 12)
        val refs = listOf(
            GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null),
            GitRef("refs/remotes/origin/main", "origin/main", RefKind.REMOTE_BRANCH, commits[0].hash, false, null),
            GitRef("refs/tags/v1.0", "v1.0", RefKind.TAG, commits[4].hash, false, null),
        )
        val fixture = uiFixture(
            commits = commits,
            refs = refs,
            head = HeadInfo(commits[0].hash, "main"),
            status = WorkingStatus(
                commits[0].hash,
                null,
                1,
                2,
                false,
                listOf(
                    StatusEntry("src/App.kt", null, 'M', ' ', StatusKind.ORDINARY),
                    StatusEntry("README.md", null, ' ', 'M', StatusKind.ORDINARY),
                    StatusEntry("notes.txt", null, '?', '?', StatusKind.UNTRACKED),
                ),
            ),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            onNodeWithTag(UiTestTags.CHANGES_ROW).assertTextContains("Changed files (3)", substring = true)
            onNodeWithTag(UiTestTags.CHANGES_ROW)
                .assertTextContains("Staged (1), Unstaged (2)", substring = true)
            onNodeWithTag(UiTestTags.CHANGES_ROW).assertIsSelected()
            onNodeWithTag(UiTestTags.commitRowForHash(commits[0].hash)).assertIsNotSelected()
            onNodeWithTag(UiTestTags.commitRow(0)).assertExists()
            onNodeWithTag(UiTestTags.commitRowForHash(commits[0].hash)).assertExists()
            val firstSubject = fixture.manager.activeSession.value!!.state.value.graph!!.subject(0)
            onNodeWithTag(UiTestTags.commitRowForHash(commits[0].hash))
                .assertTextContains(firstSubject, substring = true)
            onNodeWithTag(UiTestTags.commitRow(commits.size - 1)).assertExists()
            onNodeWithTag(UiTestTags.commitRow(commits.size)).assertDoesNotExist()
            onNodeWithText("HEAD").assertDoesNotExist()
            onNodeWithText("origin/main").assertExists()

            val session = fixture.manager.activeSession.value!!
            session.toggleRef("refs/heads/main")
            session.toggleRef("refs/remotes/origin/main")
            waitUntil(timeoutMillis = 5_000) {
                session.state.value.labelsByCommit[commits[0].hash].orEmpty().none {
                    it.kind == RefKind.LOCAL_BRANCH || it.kind == RefKind.REMOTE_BRANCH
                }
            }
            onNodeWithText("HEAD").assertExists()
            saveScreenshot("23-head-chip.png")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun workingTreeLineConnectsToHeadWhenHeadIsNotTheNewestRow() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val commits = RandomDag.generate(seed = 24, commitCount = 12)
            val head = commits[3].hash
            val fixture = uiFixture(
                commits = commits,
                refs = listOf(
                    GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, false, null),
                    GitRef("refs/heads/topic", "topic", RefKind.LOCAL_BRANCH, head, true, null),
                ),
                head = HeadInfo(head, "topic"),
            )
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.graph?.complete == true
                }
                val graph = fixture.manager.activeSession.value!!.state.value.graph!!
                assertTrue(graph.indexOfHash(head) > 0)
                assertEquals(0, graph.workingTreeLane)
                saveScreenshot("24-working-tree-head-link.png")
            } finally {
                fixture.close()
            }
        }

    @Test
    fun workingTreeHeadAtFirstRowKeepsTheHeadParentLane() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val commits = RandomDag.generate(seed = 25, commitCount = 10)
            val head = commits[0].hash
            val fixture = uiFixture(
                commits = commits,
                refs = listOf(
                    GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, head, true, null),
                ),
                head = HeadInfo(head, "main"),
            )
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.graph?.complete == true
                }
                val graph = fixture.manager.activeSession.value!!.state.value.graph!!
                assertEquals(0, graph.workingTreeLane)
                val segments = graph.segments(0)
                assertTrue(
                    (0 until segments.size).any { index ->
                        val segment = segments[index]
                        segment.kind == SegKind.OUT && segment.from == graph.nodeLane(0)
                    },
                )
                saveScreenshot("25-head-first-working-tree.png")
            } finally {
                fixture.close()
            }
        }

    @Test
    fun changesRowShowsNoChangedFilesWhenClean() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val commits = RandomDag.generate(seed = 17, commitCount = 4)
        val fixture = uiFixture(
            commits = commits,
            refs = listOf(
                GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null),
            ),
            head = HeadInfo(commits[0].hash, "main"),
            status = WorkingStatus(commits[0].hash, null, 0, 0, false, emptyList()),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }
            onNodeWithTag(UiTestTags.CHANGES_ROW).assertTextContains("Changed files (0)")
            saveScreenshot("18-changes-row-empty.png")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun clickingCommitRowSelectsItAndShowsCommitPanel() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val commits = RandomDag.generate(seed = 9, commitCount = 8)
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

            onNodeWithTag(UiTestTags.commitRowForHash(commits[2].hash)).performClick()
            waitUntil(timeoutMillis = 5_000) {
                val session = fixture.manager.activeSession.value
                session?.state?.value?.selection == Selection.Commit(commits[2].hash) &&
                    session.state.value.commitDetail != null
            }

            onNodeWithTag(UiTestTags.commitRowForHash(commits[2].hash)).assertIsSelected()
            onNodeWithTag(UiTestTags.CHANGES_ROW).assertIsNotSelected()
            onNodeWithTag(UiTestTags.COMMIT_PANEL).assertExists()
            onNodeWithTag(UiTestTags.COMMIT_HASH).assertTextContains(commits[2].hash)
            assertEquals(Selection.Commit(commits[2].hash), fixture.manager.activeSession.value?.state?.value?.selection)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun changedFilesLabelOmitsEmptyCounts() {
        assertEquals("Changed files (0)", changedFilesLabel(0, 0, 0))
        assertEquals("Changed files (1): Staged (1)", changedFilesLabel(1, 1, 0))
        assertEquals("Changed files (2): Unstaged (2)", changedFilesLabel(2, 0, 2))
        assertEquals(
            "Changed files (3): Staged (1), Unstaged (2)",
            changedFilesLabel(3, 1, 2),
        )
    }

    @Test
    fun clickingChangesRowSelectsWorkingTree() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val commits = RandomDag.generate(seed = 11, commitCount = 4)
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

            onNodeWithTag(UiTestTags.CHANGES_ROW).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.selection == Selection.WorkingTree
            }
            onNodeWithTag(UiTestTags.CHANGES_ROW).assertIsSelected()
            onNodeWithTag(UiTestTags.CHANGES_PANEL).assertExists()
        } finally {
            fixture.close()
        }
    }
}
