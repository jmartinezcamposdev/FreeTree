package com.jmartinezcampos.freetree.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.session.Selection
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@Suppress("DEPRECATION")
class CommitContextMenuTest {

    private class RecordingClipboard : ClipboardManager {
        var text: String? = null

        override fun setText(annotatedString: AnnotatedString) {
            text = annotatedString.text
        }

        override fun getText(): AnnotatedString? = text?.let(::AnnotatedString)
    }

    @Test
    fun localAndRemoteBranchesDeduplicateCopyButPreferLocalCheckout() {
        val local = ref("refs/heads/topic", "topic", commits[0].hash, local = true)
        val remote = ref("refs/remotes/origin/topic", "origin/topic", commits[0].hash)

        val entries = buildCommitMenuEntries(
            refs = listOf(local, remote),
            hash = commits[0].hash,
            head = HeadInfo(commits[0].hash, "topic"),
            busy = false,
        )

        assertEquals(1, entries.count { it.labelOrNull() == "Copy branch name: topic" })
        assertEquals(1, entries.count { it.labelOrNull() == "Checkout topic" })
        assertTrue(entries.none { it.labelOrNull()?.startsWith("Checkout local") == true })
    }

    @Test
    fun remoteOnlyBranchOffersLogicalCopyAndTrackingCheckout() {
        val remote = ref("refs/remotes/origin/topic", "origin/topic", commits[1].hash)

        val entries = buildCommitMenuEntries(
            refs = listOf(remote),
            hash = commits[1].hash,
            head = HeadInfo(commits[0].hash, "main"),
            busy = false,
        )

        assertEquals(1, entries.count { it.labelOrNull() == "Copy branch name: topic" })
        assertEquals(
            1,
            entries.count { it.labelOrNull() == "Checkout local topic (from origin/topic)" },
        )
        assertEquals(1, entries.count { it.labelOrNull() == "Delete remote branch origin/topic" })
    }

    @Test
    fun sameNamedLocalElsewhereChecksOutTheLocalBranch() {
        val local = ref("refs/heads/topic", "topic", commits[5].hash, local = true)
        val remote = ref("refs/remotes/origin/topic", "origin/topic", commits[1].hash)

        val entries = buildCommitMenuEntries(
            refs = listOf(local, remote),
            hash = commits[1].hash,
            head = HeadInfo(commits[0].hash, "main"),
            busy = false,
        )

        assertEquals(1, entries.count { it.labelOrNull() == "Checkout topic" })
        assertTrue(entries.none { it.labelOrNull()?.startsWith("Checkout local") == true })
    }

    @Test
    fun twoRemotesWithTheSameBranchNameOfferTwoTrackingCheckouts() {
        val origin = ref("refs/remotes/origin/topic", "origin/topic", commits[1].hash)
        val upstream = ref("refs/remotes/upstream/topic", "upstream/topic", commits[1].hash)

        val entries = buildCommitMenuEntries(
            refs = listOf(origin, upstream),
            hash = commits[1].hash,
            head = HeadInfo(commits[0].hash, "main"),
            busy = false,
        )

        assertEquals(
            listOf(
                "Checkout local topic (from origin/topic)",
                "Checkout local topic (from upstream/topic)",
            ),
            entries.mapNotNull { it.labelOrNull() }.filter { it.startsWith("Checkout local") },
        )
    }

    @Test
    fun commitWithoutBranchesOffersDetachedCheckout() {
        val entries = buildCommitMenuEntries(
            refs = emptyList(),
            hash = commits[2].hash,
            head = HeadInfo(commits[0].hash, "main"),
            busy = false,
        )

        assertTrue(entries.any { it.labelOrNull() == "Checkout commit ${abbreviateHash(commits[2].hash)}" })
    }

    @Test
    fun currentBranchDeleteIsDisabled() {
        val current = ref("refs/heads/main", "main", commits[0].hash, local = true)
        val entries = buildCommitMenuEntries(
            refs = listOf(current),
            hash = commits[0].hash,
            head = HeadInfo(commits[0].hash, "main"),
            busy = false,
        )
        val item = entries.filterIsInstance<CommitMenuEntry.Item>()
            .single { it.label == "Delete local branch main" }
        assertTrue(!item.enabled)
    }

    @Test
    fun rightClickOpensCheckoutMenuAndSelectsCommit() = runDesktopComposeUiTest(
        width = 1400,
        height = 900,
    ) {
        val commits = RandomDag.generate(seed = 101, commitCount = 12)
        val fixture = uiFixture(
            commits = commits,
            refs = listOf(
                ref("refs/heads/main", "main", commits[0].hash, local = true, current = true),
                ref("refs/remotes/origin/topic", "origin/topic", commits[1].hash),
            ),
            head = HeadInfo(commits[0].hash, "main"),
        )
        val clipboard = RecordingClipboard()
        try {
            setContent {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    App(fixture.manager)
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            val row = onNodeWithTag(UiTestTags.commitRowForHash(commits[1].hash))
            row.performMouseInput { rightClick() }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(UiTestTags.CONTEXT_MENU).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(
                Selection.Commit(commits[1].hash),
                fixture.manager.activeSession.value?.state?.value?.selection,
            )
            saveScreenshot("20-commit-context-menu.png")
            onNodeWithTag(UiTestTags.contextMenuItem("Copy branch name: topic")).performClick()
            assertEquals("topic", clipboard.text)

            row.performMouseInput { rightClick() }
            onNodeWithTag(UiTestTags.contextMenuItem("Checkout local topic (from origin/topic)"))
                .performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callCount(FakeMethod.CHECKOUT_REMOTE) == 1
            }
            val call = fixture.backend.callsOf(FakeMethod.CHECKOUT_REMOTE).single()
            assertEquals("topic", call.branch)
            assertEquals("origin/topic", call.name)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun validatesBranchAndTagNamesBeforeSubmitting() {
        assertEquals(null, validateRefName("feature/new", isBranch = true))
        assertEquals(null, validateRefName("v1.2.3", isBranch = false))
        assertNotNull(validateRefName("bad name", isBranch = true))
        assertNotNull(validateRefName("feature..bad", isBranch = true))
        assertNotNull(validateRefName("tag:bad", isBranch = false))
    }

    @Test
    fun createBranchDialogCreatesAndChecksOutAtCommit() = runDesktopComposeUiTest(
        width = 1400,
        height = 900,
    ) {
        val commits = RandomDag.generate(seed = 102, commitCount = 10)
        val fixture = uiFixture(
            commits = commits,
            head = HeadInfo(commits[0].hash, "main"),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            onNodeWithTag(UiTestTags.commitRowForHash(commits[2].hash)).performMouseInput {
                rightClick()
            }
            onNodeWithTag(UiTestTags.contextMenuItem("Create branch here…")).performClick()
            onNodeWithTag(UiTestTags.COMMIT_NAME_DIALOG).assertExists()
            saveScreenshot("21-create-branch-dialog.png")
            onNodeWithTag(UiTestTags.COMMIT_NAME_INPUT).performTextInput("feature/new")
            onNodeWithTag(UiTestTags.COMMIT_NAME_CONFIRM).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callCount(FakeMethod.CREATE_BRANCH) == 1
            }
            val call = fixture.backend.callsOf(FakeMethod.CREATE_BRANCH).single()
            assertEquals("feature/new", call.name)
            assertEquals(commits[2].hash, call.hash)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun createTagDialogCreatesLightweightTagAtCommit() = runDesktopComposeUiTest(
        width = 1400,
        height = 900,
    ) {
        val commits = RandomDag.generate(seed = 105, commitCount = 10)
        val fixture = uiFixture(
            commits = commits,
            head = HeadInfo(commits[0].hash, "main"),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            onNodeWithTag(UiTestTags.commitRowForHash(commits[3].hash)).performMouseInput {
                rightClick()
            }
            onNodeWithTag(UiTestTags.contextMenuItem("Create tag here…")).performClick()
            onNodeWithTag(UiTestTags.COMMIT_NAME_INPUT).performTextInput("v-test")
            onNodeWithTag(UiTestTags.COMMIT_NAME_CONFIRM).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callCount(FakeMethod.CREATE_TAG) == 1
            }
            val call = fixture.backend.callsOf(FakeMethod.CREATE_TAG).single()
            assertEquals("v-test", call.name)
            assertEquals(commits[3].hash, call.hash)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unsafeLocalDeleteShowsWarningThenForcesDeletion() = runDesktopComposeUiTest(
        width = 1400,
        height = 900,
    ) {
        val commits = RandomDag.generate(seed = 103, commitCount = 12)
        val topic = ref("refs/heads/topic", "topic", commits[1].hash, local = true)
        val fixture = uiFixture(
            commits = commits,
            refs = listOf(
                ref("refs/heads/main", "main", commits[0].hash, local = true, current = true),
                topic,
            ),
            head = HeadInfo(commits[0].hash, "main"),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            onNodeWithTag(UiTestTags.commitRowForHash(commits[1].hash)).performMouseInput {
                rightClick()
            }
            onNodeWithTag(UiTestTags.contextMenuItem("Delete local branch topic")).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(UiTestTags.DELETE_BRANCH_DIALOG)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            saveScreenshot("22-delete-branch-warning.png")
            onNodeWithTag(UiTestTags.DELETE_BRANCH_CONFIRM).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callCount(FakeMethod.DELETE_LOCAL_BRANCH) == 1
            }
            val call = fixture.backend.callsOf(FakeMethod.DELETE_LOCAL_BRANCH).single()
            assertEquals("topic", call.branch)
            assertTrue(call.force)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun safeLocalDeleteSkipsWarningAndUsesNonForcedDelete() = runDesktopComposeUiTest(
        width = 1400,
        height = 900,
    ) {
        val commits = RandomDag.generate(seed = 106, commitCount = 10)
        val fixture = uiFixture(
            commits = commits,
            refs = listOf(
                ref("refs/heads/main", "main", commits[0].hash, local = true, current = true),
                ref("refs/heads/topic", "topic", commits[0].hash, local = true),
            ),
            head = HeadInfo(commits[0].hash, "main"),
        )
        fixture.backend.reachableFromHead = setOf(commits[0].hash)
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            onNodeWithTag(UiTestTags.commitRowForHash(commits[0].hash)).performMouseInput {
                rightClick()
            }
            onNodeWithTag(UiTestTags.contextMenuItem("Delete local branch topic")).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callCount(FakeMethod.DELETE_LOCAL_BRANCH) == 1
            }
            val call = fixture.backend.callsOf(FakeMethod.DELETE_LOCAL_BRANCH).single()
            assertEquals("topic", call.branch)
            assertTrue(!call.force)
            assertTrue(
                onAllNodesWithTag(UiTestTags.DELETE_BRANCH_DIALOG)
                    .fetchSemanticsNodes().isEmpty(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun remoteDeleteAlwaysConfirms() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val commits = RandomDag.generate(seed = 104, commitCount = 10)
        val fixture = uiFixture(
            commits = commits,
            refs = listOf(
                ref("refs/heads/main", "main", commits[0].hash, local = true, current = true),
                ref("refs/remotes/origin/topic", "origin/topic", commits[1].hash),
            ),
            head = HeadInfo(commits[0].hash, "main"),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            onNodeWithTag(UiTestTags.commitRowForHash(commits[1].hash)).performMouseInput {
                rightClick()
            }
            onNodeWithTag(UiTestTags.contextMenuItem("Delete remote branch origin/topic"))
                .performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(UiTestTags.DELETE_BRANCH_DIALOG)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(UiTestTags.DELETE_BRANCH_CONFIRM).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callCount(FakeMethod.DELETE_REMOTE_BRANCH) == 1
            }
            val call = fixture.backend.callsOf(FakeMethod.DELETE_REMOTE_BRANCH).single()
            assertEquals("origin", call.remote)
            assertEquals("topic", call.branch)
        } finally {
            fixture.close()
        }
    }

    private fun CommitMenuEntry.labelOrNull(): String? =
        (this as? CommitMenuEntry.Item)?.label

    private fun ref(
        fullName: String,
        shortName: String,
        hash: String,
        local: Boolean = false,
        current: Boolean = false,
    ) = GitRef(
        fullName = fullName,
        shortName = shortName,
        kind = if (local) RefKind.LOCAL_BRANCH else RefKind.REMOTE_BRANCH,
        commit = hash,
        isCurrentBranch = current,
        upstream = null,
    )

    private companion object {
        val commits = RandomDag.generate(seed = 100, commitCount = 10)
    }
}
