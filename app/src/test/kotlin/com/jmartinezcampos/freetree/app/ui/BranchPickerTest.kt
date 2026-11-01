package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import com.jmartinezcampos.freetree.core.testing.RandomDag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BranchPickerTest {

    @Test
    fun branchEntriesPrioritizeCurrentAndExcludeMatchingLocalRemotes() {
        val refs = listOf(
            ref("refs/heads/main", "main", local = true, current = true),
            ref("refs/heads/feature", "feature", local = true),
            ref("refs/remotes/origin/main", "origin/main"),
            ref("refs/remotes/origin/topic", "origin/topic"),
            ref("refs/remotes/upstream/topic", "upstream/topic"),
        )

        val entries = buildBranchPickerEntries(refs, currentBranch = "main")

        assertEquals(
            listOf("main", "feature", "origin/topic", "upstream/topic"),
            entries.map { it.displayName },
        )
        assertTrue(entries.first().isCurrent)
        assertEquals("topic", entries[2].localName)
        assertEquals("origin/topic", entries[2].remoteRef)
    }

    @Test
    fun branchSearchIsCaseInsensitiveAndOnlyMatchesDisplayName() {
        val entries = listOf(
            BranchPickerEntry("main", "main", null, isRemote = false, isCurrent = true),
            BranchPickerEntry("topic", "topic", null, isRemote = false, isCurrent = false),
            BranchPickerEntry("origin/topic", "topic", "origin/topic", isRemote = true, isCurrent = false),
        )

        assertEquals(listOf("main"), filterBranchPickerEntries(entries, "MAIN").map { it.displayName })
        assertEquals(
            listOf("topic", "origin/topic"),
            filterBranchPickerEntries(entries, "top").map { it.displayName },
        )
    }

    @Test
    fun branchPickerSearchAndRemoteCheckout() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val commits = RandomDag.generate(seed = 140, commitCount = 10)
        val fixture = uiFixture(
            commits = commits,
            refs = listOf(
                ref("refs/heads/main", "main", commits[0].hash, local = true, current = true),
                ref("refs/heads/feature", "feature", commits[1].hash, local = true),
                ref("refs/remotes/origin/topic", "origin/topic", commits[2].hash),
            ),
            head = HeadInfo(commits[0].hash, "main"),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            onNodeWithTag(UiTestTags.BRANCH_BUTTON).performClick()
            onNodeWithTag(UiTestTags.BRANCH_PICKER_DIALOG).assertExists()
            onNodeWithTag(UiTestTags.branchPickerItem("main")).assertIsSelected()
            onNodeWithText("Local branches").assertExists()
            onNodeWithText("Remote branches").assertExists()
            onNodeWithTag(UiTestTags.branchPickerItem("origin/topic")).assertExists()
            saveScreenshot("30-branch-picker.png")

            onNodeWithTag(UiTestTags.BRANCH_PICKER_SEARCH).performTextInput("topic")
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(UiTestTags.branchPickerItem("feature"))
                    .fetchSemanticsNodes().isEmpty()
            }
            onNodeWithTag(UiTestTags.branchPickerItem("origin/topic")).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callCount(FakeMethod.CHECKOUT_REMOTE) == 1
            }
            val call = fixture.backend.callsOf(FakeMethod.CHECKOUT_REMOTE).single()
            assertEquals("topic", call.branch)
            assertEquals("origin/topic", call.name)
            onNodeWithTag(UiTestTags.BRANCH_PICKER_DIALOG).assertDoesNotExist()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun branchPickerChecksOutLocalBranch() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val commits = RandomDag.generate(seed = 141, commitCount = 10)
        val fixture = uiFixture(
            commits = commits,
            refs = listOf(
                ref("refs/heads/main", "main", commits[0].hash, local = true, current = true),
                ref("refs/heads/feature", "feature", commits[1].hash, local = true),
            ),
            head = HeadInfo(commits[0].hash, "main"),
        )
        try {
            setContent { App(fixture.manager) }
            waitUntil(timeoutMillis = 5_000) {
                fixture.manager.activeSession.value?.state?.value?.graph != null
            }

            onNodeWithTag(UiTestTags.BRANCH_BUTTON).performClick()
            onNodeWithTag(UiTestTags.branchPickerItem("feature")).performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.backend.callCount(FakeMethod.CHECKOUT_LOCAL) == 1
            }
            assertEquals(
                "feature",
                fixture.backend.callsOf(FakeMethod.CHECKOUT_LOCAL).single().branch,
            )
        } finally {
            fixture.close()
        }
    }

    private fun ref(
        fullName: String,
        shortName: String,
        hash: String = "a".repeat(40),
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
}
