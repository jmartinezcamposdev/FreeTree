package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.session.InMemorySessionPersistence
import com.jmartinezcampos.freetree.core.session.LoadState
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.testing.FakeWatcher
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RealRepositoryUiTest {

    @Test
    fun realRepositoryFlowRendersGraphDiffsChangesCommitAndRefresh() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val repo = GitTestRepo.create()
            val manager = realSessionManager()
            try {
                repo.writeFile("README.md", "line one\n")
                repo.commitAll("Initial commit")
                repo.writeFile("src/App.kt", "fun main() = Unit\n")
                val originalHead = repo.commitAll("Add app")
                repo.writeFile("README.md", "line one\nworking change\n")
                repo.createUntrackedFile("notes.txt", "delete me\n")
                repo.createUntrackedFile("src/Staged.kt", "class Staged\n")

                setContent { App(manager) }
                manager.openRepo(repo.workTree)
                awaitLoaded(manager)
                assertEquals(2, state(manager).graph!!.rowCount)

                onNodeWithTag(UiTestTags.commitRowForHash(originalHead)).performClick()
                waitUntil(timeoutMillis = 20_000) {
                    val sessionState = state(manager)
                    sessionState.selection == Selection.Commit(originalHead) &&
                        sessionState.commitDetail != null &&
                        sessionState.changedFiles.isNotEmpty()
                }
                onNodeWithTag(UiTestTags.COMMIT_PANEL).assertExists()
                onNodeWithTag(UiTestTags.changedFile("src/App.kt")).assertTextContains("src/App.kt")
                onNodeWithTag(UiTestTags.changedFile("src/App.kt")).performClick()
                waitUntil(timeoutMillis = 20_000) { state(manager).openDiff != null }
                onNodeWithTag(UiTestTags.DIFF_VIEW).assertExists()
                onNodeWithText("fun main() = Unit").assertExists()

                onNodeWithTag(UiTestTags.CHANGES_ROW).performClick()
                waitUntil(timeoutMillis = 20_000) {
                    state(manager).selection == Selection.WorkingTree
                }
                awaitStatus(manager) { entry ->
                    entry.path == "README.md" && entry.worktree == 'M'
                }

                onNodeWithTag(UiTestTags.stageFile("src/Staged.kt")).performClick()
                awaitStatus(manager) { entry ->
                    entry.path == "src/Staged.kt" && entry.index == 'A' && isStatusBlank(entry.worktree)
                }
                onNodeWithTag(UiTestTags.stagedFile("src/Staged.kt")).assertExists()
                onNodeWithTag(UiTestTags.unstagedFile("src/Staged.kt")).assertDoesNotExist()
                assertTrue(repo.statusPorcelain().lineSequence().any { it == "A  src/Staged.kt" })

                onNodeWithTag(UiTestTags.discardStaged("src/Staged.kt")).performClick()
                awaitStatus(manager) { entry ->
                    entry.path == "src/Staged.kt" && entry.kind == StatusKind.UNTRACKED
                }
                assertTrue(Files.exists(repo.path("src/Staged.kt")))
                assertEquals("?? src/Staged.kt", repo.gitOk("status", "--porcelain=v1", "--", "src/Staged.kt"))
                onNodeWithTag(UiTestTags.stagedFile("src/Staged.kt")).assertDoesNotExist()
                onNodeWithTag(UiTestTags.unstagedFile("src/Staged.kt")).assertExists()

                onNodeWithTag(UiTestTags.discardUnstaged("README.md")).performClick()
                onNodeWithTag(UiTestTags.DISCARD_DIALOG).assertExists()
                onNodeWithText("restores \"README.md\" from the index", substring = true).assertExists()
                onNodeWithTag(UiTestTags.DISCARD_CONFIRM).performClick()
                awaitNoStatus(manager, "README.md", timeoutMillis = 20_000)
                assertEquals("line one\n", repo.readFile("README.md"))
                assertFalse(repo.statusPorcelain().lineSequence().any { it.contains("README.md") })

                onNodeWithTag(UiTestTags.discardUnstaged("notes.txt")).performClick()
                onNodeWithText("permanently deletes \"notes.txt\" from disk", substring = true).assertExists()
                onNodeWithTag(UiTestTags.DISCARD_CONFIRM).performClick()
                awaitNoStatus(manager, "notes.txt", timeoutMillis = 20_000)
                assertFalse(Files.exists(repo.path("notes.txt")))

                onNodeWithTag(UiTestTags.stageFile("src/Staged.kt")).performClick()
                awaitStatus(manager) { entry ->
                    entry.path == "src/Staged.kt" && entry.index == 'A' && isStatusBlank(entry.worktree)
                }
                onNodeWithTag(UiTestTags.COMMIT_MESSAGE).performTextInput("Commit from UI")
                onNodeWithTag(UiTestTags.COMMIT_BUTTON).performClick()
                waitUntil(timeoutMillis = 20_000) {
                    val sessionState = state(manager)
                    sessionState.busy == null &&
                        sessionState.head?.commit?.let { it != originalHead } == true &&
                        sessionState.status?.entries?.isEmpty() == true
                }
                val committedHead = repo.headHash()
                assertEquals("Commit from UI", repo.gitOk("log", "-1", "--format=%s"))
                waitUntil(timeoutMillis = 20_000) {
                    val graph = state(manager).graph
                    graph != null && graph.complete && graph.indexOfHash(committedHead) >= 0
                }
                onNodeWithText("Working tree clean").assertExists()

                repo.writeFile("README.md", "line one\nrefresh me\n")
                manager.activeSession.value!!.refresh()
                awaitStatus(manager) { entry ->
                    entry.path == "README.md" && entry.worktree == 'M'
                }
                assertEquals("M README.md", repo.gitOk("status", "--porcelain=v1", "--", "README.md"))
                assertNotNull(state(manager).graph)
            } finally {
                manager.close()
                repo.close()
            }
        }

    @Test
    fun missingRepositoryShowsTheRealGitErrorAndRetry() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val directory = Files.createTempDirectory("freetree-not-a-repo-")
            val manager = realSessionManager()
            try {
                setContent { App(manager) }
                manager.openRepo(directory)
                waitUntil(timeoutMillis = 20_000) {
                    state(manager).load is LoadState.Error
                }
                onNodeWithTag(UiTestTags.GRAPH_ERROR).assertExists()
                onNodeWithTag(UiTestTags.MESSAGE_BAR).assertExists()
                onNodeWithTag(UiTestTags.MESSAGE_STDERR)
                    .assertTextContains("not a git repository", substring = true)
                onNodeWithText("Retry").assertExists()
            } finally {
                manager.close()
                deleteRecursively(directory)
            }
        }

    private fun realSessionManager() = SessionManager(
        backendFactory = { path -> CliGitBackend.open(path) },
        watcherFactory = { FakeWatcher() },
        persistence = InMemorySessionPersistence(),
        restore = false,
    )

    private fun state(manager: SessionManager) = manager.activeSession.value?.state?.value
        ?: error("no active session")

    private fun DesktopComposeUiTest.awaitLoaded(manager: SessionManager) {
        waitUntil(timeoutMillis = 30_000) {
            val current = state(manager)
            current.graph?.complete == true && current.status != null
        }
    }

    private fun DesktopComposeUiTest.awaitStatus(
        manager: SessionManager,
        predicate: (StatusEntry) -> Boolean,
    ) {
        waitUntil(timeoutMillis = 20_000) {
            val current = state(manager)
            current.busy == null && current.status?.entries?.any(predicate) == true
        }
    }

    private fun DesktopComposeUiTest.awaitNoStatus(
        manager: SessionManager,
        path: String,
        timeoutMillis: Long,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            val current = state(manager)
            current.busy == null && current.status?.entries?.none { it.path == path } == true
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
