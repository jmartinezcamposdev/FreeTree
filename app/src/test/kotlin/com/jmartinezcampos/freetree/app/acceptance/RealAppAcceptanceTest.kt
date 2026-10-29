package com.jmartinezcampos.freetree.app.acceptance

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.ui.UiTestTags
import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.session.InMemorySessionPersistence
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.session.UserMessage
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import com.jmartinezcampos.freetree.core.watch.JdkRepoWatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RealAppAcceptanceTest {

    @Test
    fun realGitFlowCoversGraphDetailChangesDiscardCommitPushAndPull() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val repo = GitTestRepo.create()
            val manager = realSessionManager()
            try {
                repo.writeFile("README.md", "line one\n")
                repo.commitAll("Initial commit")
                val origin = repo.createBareOrigin()
                repo.git("push", "-u", "origin", "main").requireSuccess()
                repo.writeFile("src/App.kt", "fun main() = Unit\n")
                val appCommit = repo.commitAll("Add app")
                repo.writeFile("README.md", "line one\nlocal change\n")
                repo.createUntrackedFile("notes.txt", "delete me\n")
                repo.createUntrackedFile("staged.txt", "staged from ui\n")

                setContent { App(manager) }
                manager.openRepo(repo.workTree)
                awaitLoaded(manager)
                assertTrue(state(manager).graph!!.indexOfHash(appCommit) >= 0)

                onNodeWithTag(UiTestTags.commitRowForHash(appCommit)).performClick()
                waitUntil(timeoutMillis = 20_000) {
                    val current = state(manager)
                    current.selection == Selection.Commit(appCommit) &&
                        current.commitDetail != null &&
                        current.changedFiles.isNotEmpty()
                }
                onNodeWithTag(UiTestTags.COMMIT_PANEL).assertExists()
                onNodeWithTag(UiTestTags.changedFile("src/App.kt")).assertTextContains("src/App.kt")
                onNodeWithTag(UiTestTags.changedFile("src/App.kt")).performClick()
                waitUntil(timeoutMillis = 20_000) { state(manager).openDiff != null }
                onNodeWithTag(UiTestTags.DIFF_VIEW).assertExists()
                onNodeWithText("+fun main() = Unit").assertExists()

                onNodeWithTag(UiTestTags.CHANGES_ROW).performClick()
                awaitStatus(manager) { it.path == "README.md" && it.worktree == 'M' }
                awaitStatus(manager) { it.path == "staged.txt" && it.kind == StatusKind.UNTRACKED }

                onNodeWithTag(UiTestTags.stageFile("staged.txt")).performClick()
                awaitStatus(manager) {
                    it.path == "staged.txt" && it.index == 'A' && it.worktree == ' '
                }
                assertTrue(
                    "staged.txt" in repo.gitOk("diff", "--cached", "--name-only").lineSequence().toList(),
                )

                onNodeWithTag(UiTestTags.discardStaged("staged.txt")).performClick()
                awaitStatus(manager) {
                    it.path == "staged.txt" && it.kind == StatusKind.UNTRACKED
                }
                assertTrue(repo.exists("staged.txt"))
                assertEquals("?? staged.txt", repo.gitOk("status", "--porcelain=v1", "--", "staged.txt"))

                onNodeWithTag(UiTestTags.stageFile("staged.txt")).performClick()
                awaitStatus(manager) {
                    it.path == "staged.txt" && it.index == 'A' && it.worktree == ' '
                }

                onNodeWithTag(UiTestTags.discardUnstaged("README.md")).performClick()
                onNodeWithTag(UiTestTags.DISCARD_DIALOG).assertExists()
                onNodeWithText("restores \"README.md\" from the index", substring = true).assertExists()
                onNodeWithTag(UiTestTags.DISCARD_CONFIRM).performClick()
                awaitNoStatus(manager, "README.md")
                assertEquals("line one\n", repo.readFile("README.md"))

                onNodeWithTag(UiTestTags.discardUnstaged("notes.txt")).performClick()
                onNodeWithText("permanently deletes \"notes.txt\" from disk", substring = true).assertExists()
                onNodeWithTag(UiTestTags.DISCARD_CONFIRM).performClick()
                awaitNoStatus(manager, "notes.txt")
                assertFalse(repo.exists("notes.txt"))

                val previousHead = repo.headHash()
                onNodeWithTag(UiTestTags.COMMIT_MESSAGE).performTextInput("Commit from real UI")
                onNodeWithTag(UiTestTags.COMMIT_BUTTON).performClick()
                waitUntil(timeoutMillis = 20_000) {
                    val current = state(manager)
                    current.busy == null &&
                        current.head?.commit?.let { it != previousHead } == true &&
                        current.status?.entries?.isEmpty() == true
                }
                val committedHead = repo.headHash()
                assertEquals("Commit from real UI", repo.gitOk("log", "-1", "--format=%s"))
                assertTrue(repo.statusPorcelain().isBlank())
                waitUntil(timeoutMillis = 20_000) {
                    val graph = state(manager).graph
                    graph != null && graph.complete && graph.indexOfHash(committedHead) >= 0
                }

                onNodeWithTag(UiTestTags.PUSH).performClick()
                awaitCondition("push reaches the bare origin") {
                    repo.gitIn(origin.path, "rev-parse", "refs/heads/main")
                        .requireSuccess()
                        .stdout
                        .trim() == committedHead
                }

                val remoteHead = GitTestRepo.clone(origin).use { other ->
                    other.commitFile("remote.txt", "remote\n", "Remote advance")
                    other.git("push").requireSuccess()
                    other.headHash()
                }

                onNodeWithTag(UiTestTags.PULL).performClick()
                waitUntil(timeoutMillis = 20_000) {
                    val current = state(manager)
                    current.busy == null &&
                        current.head?.commit == remoteHead &&
                        current.status?.entries?.isEmpty() == true
                }
                assertEquals(remoteHead, repo.headHash())
                assertEquals("remote\n", repo.readFile("remote.txt"))
                waitUntil(timeoutMillis = 20_000) {
                    val graph = state(manager).graph
                    graph != null && graph.complete && graph.indexOfHash(remoteHead) >= 0
                }
                assertFalse(state(manager).message is UserMessage.Error)
            } finally {
                manager.close()
                repo.close()
            }
        }

    @Test
    fun toolbarSurfacesPullAndPushGitErrorsWithoutLosingTheView() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val repo = GitTestRepo.create()
            val manager = realSessionManager()
            try {
                repo.writeFile("a.txt", "one\n")
                repo.commitAll("root")
                val origin = repo.createBareOrigin()
                repo.git("push", "-u", "origin", "main").requireSuccess()

                repo.commitFile("local.txt", "local\n", "local change")
                val localHead = repo.headHash()
                GitTestRepo.clone(origin).use { other ->
                    other.commitFile("remote.txt", "remote\n", "remote change")
                    other.git("push").requireSuccess()
                }

                setContent { App(manager) }
                manager.openRepo(repo.workTree)
                awaitLoaded(manager)

                onNodeWithTag(UiTestTags.PULL).performClick()
                val pullError = awaitMessageError(manager) {
                    it.contains("divergent", ignoreCase = true) ||
                        it.contains("fast-forward", ignoreCase = true)
                }
                assertTrue(pullError.isNotBlank())
                onNodeWithTag(UiTestTags.MESSAGE_STDERR).assertExists()
                assertEquals(localHead, repo.headHash())

                onNodeWithTag(UiTestTags.PUSH).performClick()
                val pushError = awaitMessageError(manager) {
                    it.contains("rejected", ignoreCase = true) ||
                        it.contains("non-fast-forward", ignoreCase = true) ||
                        it.contains("fetch first", ignoreCase = true)
                }
                assertTrue(pushError.isNotBlank())
                onNodeWithTag(UiTestTags.MESSAGE_STDERR).assertExists()
                assertEquals(localHead, repo.headHash())
                assertNotNull(state(manager).graph)
            } finally {
                manager.close()
                repo.close()
            }
        }

    private fun realSessionManager() = SessionManager(
        backendFactory = { path -> CliGitBackend.open(path) },
        watcherFactory = { JdkRepoWatcher() },
        persistence = InMemorySessionPersistence(),
        restore = false,
    )

    private fun state(manager: SessionManager) = manager.activeSession.value?.state?.value
        ?: error("no active session")

    private fun DesktopComposeUiTest.awaitLoaded(manager: SessionManager) {
        waitUntil(timeoutMillis = 30_000) {
            val current = state(manager)
            current.graph?.complete == true && current.status != null && current.refs.isNotEmpty()
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

    private fun DesktopComposeUiTest.awaitNoStatus(manager: SessionManager, path: String) {
        waitUntil(timeoutMillis = 20_000) {
            val current = state(manager)
            current.busy == null && current.status?.entries?.none { it.path == path } == true
        }
    }

    private fun DesktopComposeUiTest.awaitMessageError(
        manager: SessionManager,
        predicate: (String) -> Boolean,
    ): String {
        lateinit var found: String
        waitUntil(timeoutMillis = 20_000) {
            val current = state(manager)
            val message = current.message as? UserMessage.Error
            val stderr = message?.stderr
            if (current.busy == null && message != null && !stderr.isNullOrBlank() && predicate(stderr)) {
                found = stderr
                true
            } else {
                false
            }
        }
        return found
    }

    private fun awaitCondition(description: String, timeoutMillis: Long = 20_000, block: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (block()) return
            Thread.sleep(25)
        }
        error("Timed out waiting for $description")
    }
}
