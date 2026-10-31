package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.core.model.GitDirs
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.session.InMemorySessionPersistence
import com.jmartinezcampos.freetree.core.session.SessionManager
import com.jmartinezcampos.freetree.core.session.SessionPhase
import com.jmartinezcampos.freetree.core.session.UserMessage
import com.jmartinezcampos.freetree.core.testing.FakeGitBackend
import com.jmartinezcampos.freetree.core.testing.FakeWatcher
import com.jmartinezcampos.freetree.core.testing.RandomDag
import com.jmartinezcampos.freetree.core.watch.RepoWatcher
import com.jmartinezcampos.freetree.core.watch.WatchEvent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppStatesUiTest {

    @Test
    fun emptyStateOffersTheOpenRepositoryAction() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val manager = SessionManager(
                backendFactory = { error("no repository is open") },
                watcherFactory = { FakeWatcher() },
                persistence = InMemorySessionPersistence(),
                restore = false,
            )
            var opened = 0
            try {
                setContent { App(manager, onOpenRepository = { opened++ }) }
                onNodeWithTag(UiTestTags.EMPTY_STATE).assertExists()
                onNodeWithTag(UiTestTags.OPEN_REPOSITORY).performClick()
                waitForIdle()
                assertEquals(1, opened)
            } finally {
                manager.close()
            }
        }

    @Test
    fun watcherFailureIsVisibleAndTheGraphStaysUsable() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val commits = RandomDag.generate(seed = 23, commitCount = 4)
            val backend = FakeGitBackend(
                commits = commits,
                refs = listOf(
                    GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[0].hash, true, null),
                ),
                head = HeadInfo(commits[0].hash, "main"),
                status = WorkingStatus(commits[0].hash, null, 0, 0, false, emptyList()),
            )
            val manager = SessionManager(
                backendFactory = { backend },
                watcherFactory = { FailingWatcher() },
                persistence = InMemorySessionPersistence(),
                restore = false,
            )
            try {
                setContent { App(manager) }
                manager.openRepo(Path.of("/repo/watcher-failure"))
                waitUntil(timeoutMillis = 10_000) {
                    val state = manager.activeSession.value?.state?.value
                    state?.graph != null &&
                        (state.message as? UserMessage.Error)?.text?.contains("watcher unavailable") == true
                }
                onNodeWithTag(UiTestTags.MESSAGE_BAR).assertExists()
                onNodeWithText("Repository watcher unavailable", substring = true).assertExists()
                onNodeWithTag(UiTestTags.CHANGES_ROW).assertExists()
                manager.activeSession.value!!.refresh()
                waitUntil(timeoutMillis = 10_000) {
                    val session = manager.activeSession.value
                    session?.state?.value?.graph != null && session.phase.value == SessionPhase.ACTIVE
                }
                assertTrue(manager.activeSession.value!!.state.value.graph!!.rowCount > 0)
            } finally {
                manager.close()
            }
        }

    private class FailingWatcher : RepoWatcher {
        override fun start(dirs: GitDirs, watchWorkTree: Boolean, onEvent: (WatchEvent) -> Unit) {
            throw IllegalStateException("watch service unavailable")
        }

        override fun stop() = Unit
    }
}
