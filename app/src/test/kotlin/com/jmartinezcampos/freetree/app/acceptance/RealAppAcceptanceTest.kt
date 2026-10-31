package com.jmartinezcampos.freetree.app.acceptance

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import kotlin.test.assertSame
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
                    val result = repo.gitIn(origin.path, "rev-parse", "--verify", "-q", "refs/heads/main")
                    result.succeeded && result.stdout.trim() == committedHead
                }
                awaitCondition("push sets the origin upstream") {
                    val result = repo.gitIn(repo.workTree, "rev-parse", "--abbrev-ref", "@{upstream}")
                    result.succeeded && result.stdout.trim() == "origin/main"
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

    @Test
    fun realHistoryShowsMergeNodeRefChipsAndHead() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val repo = GitTestRepo.create()
            val manager = realSessionManager()
            try {
                repo.writeFile("README.md", "root\n")
                val root = repo.commitAll("root")
                repo.git("tag", "-a", "v1.0", "-m", "release", root).requireSuccess()
                repo.checkoutNewBranch("feature/topic").requireSuccess()
                repo.commitFile("topic.txt", "topic\n", "topic commit")
                repo.checkout("main").requireSuccess()
                repo.commitFile("main.txt", "main\n", "main commit")
                repo.merge("feature/topic").requireSuccess()
                val merge = repo.headHash()
                repo.createBareOrigin()
                repo.git("push", "-u", "origin", "main").requireSuccess()
                repo.createBranch("release/1.0").requireSuccess()

                setContent { App(manager) }
                manager.openRepo(repo.workTree)
                awaitLoaded(manager)

                val state = state(manager)
                val graph = assertNotNull(state.graph)
                val mergeRow = graph.indexOfHash(merge)
                assertTrue(mergeRow >= 0, "merge commit missing from the graph")
                assertEquals(2, graph.parentCount(mergeRow))
                assertEquals(merge, state.head?.commit)
                assertEquals("main", state.head?.branchShortName)

                val mergeLabels = state.labelsByCommit.getValue(merge).map { it.fullName }.toSet()
                assertTrue("refs/heads/main" in mergeLabels)
                assertTrue("refs/remotes/origin/main" in mergeLabels)
                assertTrue("refs/heads/release/1.0" in mergeLabels)
                assertEquals(
                    "refs/tags/v1.0",
                    state.labelsByCommit.getValue(root).single().fullName,
                )

                onNodeWithTag(UiTestTags.commitRowForHash(merge)).assertExists()
                onNodeWithText("HEAD").assertDoesNotExist()
                onNodeWithText("release/1.0").assertExists()
                onNodeWithText("+1").assertDoesNotExist()
                onNodeWithText("v1.0").assertExists()
            } finally {
                manager.close()
                repo.close()
            }
        }

    @Test
    fun realCommitSelectionShowsTextAndBinaryDiffs() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val repo = GitTestRepo.create()
            val manager = realSessionManager()
            try {
                repo.writeFile("text.txt", "one\n")
                repo.writeBytes("binary.bin", byteArrayOf(0, 1, 2, 3))
                repo.commitAll("root")
                repo.writeFile("text.txt", "one\ntwo\n")
                repo.writeBytes("binary.bin", byteArrayOf(0, 9, 8, 7))
                val commit = repo.commitAll("text and binary")

                setContent { App(manager) }
                manager.openRepo(repo.workTree)
                awaitLoaded(manager)

                onNodeWithTag(UiTestTags.commitRowForHash(commit)).performClick()
                waitUntil(timeoutMillis = 20_000) {
                    val current = state(manager)
                    current.selection == Selection.Commit(commit) && current.changedFiles.size == 2
                }
                onNodeWithTag(UiTestTags.changedFile("text.txt")).performClick()
                waitUntil(timeoutMillis = 20_000) {
                    val diff = state(manager).openDiff
                    diff != null && !diff.binary
                }
                onNodeWithTag(UiTestTags.DIFF_VIEW).assertExists()
                onNodeWithText("+two").assertExists()

                onNodeWithTag(UiTestTags.changedFile("binary.bin")).performClick()
                waitUntil(timeoutMillis = 20_000) {
                    state(manager).openDiff?.binary == true
                }
                onNodeWithTag(UiTestTags.DIFF_BINARY).assertExists()
                assertTrue(state(manager).openDiff?.lines?.none { it.text == "+two" } == true)
            } finally {
                manager.close()
                repo.close()
            }
        }

    @Test
    fun realUiStagesDeletedFileAndCommitsMultilineMessageWithRename() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val repo = GitTestRepo.create()
            val manager = realSessionManager()
            try {
                repo.writeFile("delete.txt", "delete me\n")
                repo.writeFile("rename old.txt", (1..20).joinToString("\n") { "line $it" } + "\n")
                repo.writeFile("keep.txt", "keep\n")
                repo.commitAll("root")
                repo.deleteFile("delete.txt")
                repo.renameTracked("rename old.txt", "rename new.txt").requireSuccess()
                repo.writeFile("keep.txt", "keep changed\n")

                setContent { App(manager) }
                manager.openRepo(repo.workTree)
                awaitLoaded(manager)
                awaitStatus(manager) { it.path == "delete.txt" && it.worktree == 'D' }
                awaitStatus(manager) { it.path == "rename new.txt" && it.index == 'R' }

                onNodeWithTag(UiTestTags.CHANGES_ROW).performClick()
                onNodeWithTag(UiTestTags.stagedFile("rename new.txt")).assertExists()
                onNodeWithTag(UiTestTags.unstagedFile("delete.txt")).assertExists()
                onNodeWithTag(UiTestTags.stageFile("delete.txt")).performClick()
                awaitStatus(manager) {
                    it.path == "delete.txt" && it.index == 'D' && it.worktree == ' '
                }
                assertEquals("D  delete.txt", repo.gitOk("status", "--porcelain=v1", "--", "delete.txt"))

                val previousHead = repo.headHash()
                val message = "Commit delete and rename\n\nBody line one\nBody line two"
                onNodeWithTag(UiTestTags.COMMIT_MESSAGE).performTextReplacement(message)
                onNodeWithTag(UiTestTags.COMMIT_BUTTON).performClick()
                waitUntil(timeoutMillis = 20_000) {
                    val current = state(manager)
                    current.busy == null && current.head?.commit?.let { it != previousHead } == true
                }
                val head = repo.headHash()
                assertEquals(message, repo.git("log", "-1", "--format=%B", head).stdout.trimEnd())
                val nameStatus = repo.gitOk("show", "--name-status", "--format=", "-M", head).lines()
                assertTrue(nameStatus.any { it.startsWith("D\tdelete.txt") }, nameStatus.toString())
                assertTrue(
                    nameStatus.any {
                        it.startsWith("R") && it.contains("rename old.txt") && it.endsWith("rename new.txt")
                    },
                    nameStatus.toString(),
                )
                val remaining = repo.git("status", "--porcelain=v1").requireSuccess().stdout
                    .trimEnd()
                    .lines()
                assertEquals(listOf(" M keep.txt"), remaining)
                assertFalse(repo.exists("delete.txt"))
                assertTrue(repo.exists("rename new.txt"))
                waitUntil(timeoutMillis = 20_000) {
                    val graph = state(manager).graph
                    graph != null && graph.complete && graph.indexOfHash(head) >= 0
                }
            } finally {
                manager.close()
                repo.close()
            }
        }

    @Test
    fun realQueryHidesRefsFiltersFlatAndKeepsTheViewOnSyntaxErrors() =
        runDesktopComposeUiTest(width = 1500, height = 950) {
            val repo = GitTestRepo.create()
            val manager = realSessionManager()
            try {
                repo.writeFile("README.md", "root\n")
                repo.commitAll("root", authorName = "Ada", authorEmail = "ada@example.com")
                repo.checkoutNewBranch("topic").requireSuccess()
                val topic = repo.commitFile(
                    relative = "topic.txt",
                    content = "topic\n",
                    message = "topic work",
                    authorName = "Bob",
                    authorEmail = "bob@example.com",
                )
                repo.checkout("main").requireSuccess()
                repo.commitFile(
                    relative = "main.txt",
                    content = "main\n",
                    message = "main work",
                    authorName = "Ada",
                    authorEmail = "ada@example.com",
                )

                setContent { App(manager) }
                manager.openRepo(repo.workTree)
                awaitLoaded(manager)

                val session = manager.activeSession.value!!
                session.toggleRef("refs/heads/topic")
                waitUntil(timeoutMillis = 20_000) {
                    val current = state(manager)
                    "refs/heads/topic" in current.hiddenRefs &&
                        current.graph?.complete == true &&
                        current.graph!!.indexOfHash(topic) < 0
                }

                session.applyQuery("author:Ada")
                waitUntil(timeoutMillis = 20_000) {
                    val current = state(manager)
                    current.query == "author:Ada" &&
                        current.queryError == null &&
                        current.graph?.complete == true &&
                        current.graph!!.flat
                }
                val filtered = assertNotNull(state(manager).graph)
                assertTrue(filtered.rowCount > 0)
                assertTrue((0 until filtered.rowCount).all { filtered.author(it) == "Ada" })
                assertTrue(filtered.indexOfHash(topic) < 0)
                onNodeWithText("Flat view (content filters active)").assertExists()

                session.applyQuery("bogus:value")
                waitUntil(timeoutMillis = 20_000) {
                    state(manager).queryError != null
                }
                assertSame(filtered, state(manager).graph)
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
