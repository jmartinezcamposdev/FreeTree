package com.jmartinezcampos.freetree.app.hardening

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.session.InMemorySessionPersistence
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.testing.FakeGitBackend
import com.jmartinezcampos.freetree.core.testing.FakeWatcher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A discard confirmation belongs to the tab that opened it. Switching tabs must not leave a
 * confirmation that would delete a path in a different repository.
 */
@OptIn(ExperimentalTestApi::class)
class DiscardDialogTabIsolationTest {

    private val firstPath = Path.of("/repo/hardening-one")
    private val secondPath = Path.of("/repo/hardening-two")

    private fun backend(path: String, untracked: String): FakeGitBackend {
        val commit = RawCommit("a".repeat(40), emptyList(), "Ada", 1_700_000_000L, "root")
        return FakeGitBackend(
            commits = listOf(commit),
            refs = listOf(
                GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commit.hash, true, null),
            ),
            head = HeadInfo(commit.hash, "main"),
            status = WorkingStatus(
                branchHead = commit.hash,
                upstream = null,
                ahead = 0,
                behind = 0,
                unborn = false,
                entries = listOf(StatusEntry(path, null, '?', '?', StatusKind.UNTRACKED)),
            ),
        )
    }

    @Test
    fun switchingTabsDismissesAPendingDiscardConfirmation() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val first = backend("one.txt", "one.txt")
            val second = backend("two.txt", "two.txt")
            val manager = manager(first, second)
            try {
                manager.openRepo(firstPath)
                manager.openRepo(secondPath)
                setContent { App(sessionManager = manager) }

                onNodeWithTag(UiTestTags.tab(0)).performClick()
                awaitDiscardAction("one.txt")
                onNodeWithTag(UiTestTags.discardUnstaged("one.txt")).performClick()
                onNodeWithTag(UiTestTags.DISCARD_DIALOG).assertExists()

                // The dialog is modal, so a real tab switch is only reachable programmatically
                // (shortcut, session manager); the confirmation must not leak into the other tab.
                val secondId = manager.tabs.value[1].id
                manager.activate(secondId)
                awaitDiscardAction("two.txt")
                onNodeWithTag(UiTestTags.DISCARD_DIALOG).assertDoesNotExist()
                onNodeWithTag(UiTestTags.discardUnstaged("two.txt")).assertExists()
                assertEquals(secondId, manager.activeId.value)

                manager.activate(manager.tabs.value[0].id)
                awaitDiscardAction("one.txt")
                onNodeWithTag(UiTestTags.DISCARD_DIALOG).assertDoesNotExist()
            } finally {
                manager.close()
            }
        }

    private fun DesktopComposeUiTest.awaitDiscardAction(path: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(UiTestTags.discardUnstaged(path)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun manager(first: FakeGitBackend, second: FakeGitBackend): SessionManager =
        SessionManager(
            backendFactory = { path ->
                when (path.toAbsolutePath().normalize().toString()) {
                    firstPath.toString() -> first
                    secondPath.toString() -> second
                    else -> error("unexpected repository: $path")
                }
            },
            watcherFactory = { FakeWatcher() },
            persistence = InMemorySessionPersistence(),
            restore = false,
        )
}
