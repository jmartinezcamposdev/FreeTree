package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.session.Selection
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CommitPanelUiTest {

    @Test
    fun commitPanelShowsMetadataParentsAndOpensFileDiff() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val root = RawCommit("a".repeat(40), emptyList(), "Ada", 1_700_000_000L, "root commit")
        val tip = RawCommit("b".repeat(40), listOf(root.hash), "Grace", 1_700_000_060L, "tip commit")
        val change = FileChange('M', "README.md", null)
        val diff = DiffText(
            lines = listOf(
                DiffLine(DiffLineKind.FILE_HEADER, null, null, "diff --git a/README.md b/README.md"),
                DiffLine(DiffLineKind.HUNK_HEADER, null, null, "@@ -1 +1 @@"),
                DiffLine(DiffLineKind.DEL, 1, null, "old line"),
                DiffLine(DiffLineKind.ADD, null, 1, "new line"),
            ),
            binary = false,
            truncated = false,
        )
        val fixture = uiFixture(
            commits = listOf(tip, root),
            refs = listOf(GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, tip.hash, true, null)),
            head = HeadInfo(tip.hash, "main"),
            changedFiles = mapOf(tip.hash to listOf(change)),
            diffs = mapOf(DiffTarget.InCommit(tip.hash, change) to diff),
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

            onNodeWithTag(UiTestTags.COMMIT_PANEL).assertExists()
            onNodeWithTag(UiTestTags.COMMIT_HASH).assertTextContains(tip.hash)
            onNodeWithTag(UiTestTags.COMMIT_AUTHOR).assertTextContains("Grace")
            onNodeWithTag(UiTestTags.parentLink(root.hash)).assertExists()
            onNodeWithText("Merge commit with", substring = true).assertDoesNotExist()
            onNodeWithTag(UiTestTags.COMMIT_DETAIL_MESSAGE_TOGGLE).assertDoesNotExist()
            onNodeWithTag(UiTestTags.changedFile("README.md")).assertTextContains("README.md")
            onNodeWithTag(UiTestTags.changedFile("README.md")).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.openDiff != null
            }
            onNodeWithTag(UiTestTags.DIFF_VIEW).assertExists()
            onNodeWithText("+new line").assertExists()
            onNodeWithText("-old line").assertExists()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun clickingParentSelectsTheParentCommit() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val root = RawCommit("a".repeat(40), emptyList(), "Ada", 1_700_000_000L, "root commit")
        val tip = RawCommit("b".repeat(40), listOf(root.hash), "Grace", 1_700_000_060L, "tip commit")
        val fixture = uiFixture(
            commits = listOf(tip, root),
            refs = listOf(GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, tip.hash, true, null)),
            head = HeadInfo(tip.hash, "main"),
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

            onNodeWithTag(UiTestTags.parentLink(root.hash)).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.selection == Selection.Commit(root.hash)
            }
            assertEquals(Selection.Commit(root.hash), fixture.manager.activeSession.value?.state?.value?.selection)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun multilineCommitMessagesStartCompactAndToggle() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val root = RawCommit("a".repeat(40), emptyList(), "Ada", 1_700_000_000L, "root commit")
        val message = "Subject line\n\nBody line one\nBody line two"
        val tip = RawCommit("b".repeat(40), listOf(root.hash), "Grace", 1_700_000_060L, message)
        val fixture = uiFixture(
            commits = listOf(tip, root),
            refs = listOf(GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, tip.hash, true, null)),
            head = HeadInfo(tip.hash, "main"),
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

            onNodeWithTag(UiTestTags.COMMIT_DETAIL_MESSAGE).assertTextEquals("Subject line")
            onNodeWithTag(UiTestTags.COMMIT_DETAIL_MESSAGE_TOGGLE)
                .assertContentDescriptionEquals("Expand commit message")
            saveScreenshot("11-commit-message-compact.png")

            onNodeWithTag(UiTestTags.COMMIT_DETAIL_MESSAGE_TOGGLE).performClick()
            waitForIdle()

            onNodeWithTag(UiTestTags.COMMIT_DETAIL_MESSAGE)
                .assertTextEquals("Subject line\n\nBody line one\nBody line two")
            onNodeWithTag(UiTestTags.COMMIT_DETAIL_MESSAGE_TOGGLE)
                .assertContentDescriptionEquals("Collapse commit message")
            saveScreenshot("12-commit-message-expanded.png")

            onNodeWithTag(UiTestTags.COMMIT_DETAIL_MESSAGE_TOGGLE).performClick()
            waitForIdle()

            onNodeWithTag(UiTestTags.COMMIT_DETAIL_MESSAGE).assertTextEquals("Subject line")
            onNodeWithTag(UiTestTags.COMMIT_DETAIL_MESSAGE_TOGGLE)
                .assertContentDescriptionEquals("Expand commit message")
        } finally {
            fixture.close()
        }
    }
}
