package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.app.smoke.saveScreenshot
import com.jmartinezcampos.freetree.core.filter.FilterField
import com.jmartinezcampos.freetree.core.filter.RuleMode
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FiltersDialogUiTest {

    @Test
    fun addsMessageIncludeRuleAppliesItAndReopensWithItsState() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture()
            try {
                setContent { App(fixture.manager) }
                awaitGraph(fixture)

                onNodeWithTag(UiTestTags.FILTERS_BUTTON).performClick()
                onNodeWithTag(UiTestTags.FILTERS_DIALOG).assertExists()
                onNodeWithTag(UiTestTags.FILTERS_ADD_INCLUDE).performClick()
                onNodeWithTag(UiTestTags.filtersRule("include", 0), useUnmergedTree = true).assertExists()
                onNodeWithTag(UiTestTags.filtersRuleValue("include", 0), useUnmergedTree = true)
                    .performTextInput("target topic commit")
                onNodeWithTag(UiTestTags.filtersModeOption("include", RuleMode.ALL.name), useUnmergedTree = true)
                    .performClick()
                saveScreenshot("35-filters-rules.png")

                onNodeWithTag(UiTestTags.FILTERS_APPLY).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    val state = state(fixture)
                    state.branchFilter.includes.size == 1 &&
                        state.branchFilter.includeMode == RuleMode.ALL &&
                        state.filterVisibleRefs == setOf("refs/heads/topic") &&
                        !state.filterBusy
                }
                onNodeWithTag(UiTestTags.FILTERS_DIALOG).assertDoesNotExist()
                onNodeWithTag(UiTestTags.FILTERS_ACTIVE_COUNT).assertExists()
                onNodeWithText("HEAD").assertExists()
                assertEquals(
                    listOf("refs/heads/topic"),
                    labelsAtTopicTip(fixture).filter { it.kind != RefKind.TAG }.map { it.fullName },
                )
                saveScreenshot("36-filtered-history.png")

                onNodeWithTag(UiTestTags.FILTERS_BUTTON).performClick()
                onNodeWithTag(UiTestTags.FILTERS_DIALOG).assertExists()
                onNodeWithTag(UiTestTags.filtersRuleValue("include", 0), useUnmergedTree = true)
                    .assertTextContains("target topic commit")
                onNodeWithTag(UiTestTags.FILTERS_CANCEL).performClick()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun fieldSelectorChangesTheRuleToAuthor() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val fixture = fixture()
        try {
            setContent { App(fixture.manager) }
            awaitGraph(fixture)

            onNodeWithTag(UiTestTags.FILTERS_BUTTON).performClick()
            onNodeWithTag(UiTestTags.FILTERS_ADD_INCLUDE).performClick()
            onNodeWithTag(UiTestTags.filtersRuleField("include", 0), useUnmergedTree = true).performClick()
            onNodeWithTag(UiTestTags.filtersFieldOption(FilterField.AUTHOR.name), useUnmergedTree = true).performClick()
            onNodeWithTag(UiTestTags.filtersRuleValue("include", 0), useUnmergedTree = true)
                .performTextInput("Topic Author")
            onNodeWithTag(UiTestTags.FILTERS_APPLY).performClick()

            waitUntil(timeoutMillis = 5_000) {
                val state = state(fixture)
                state.branchFilter.includes.firstOrNull()?.field == FilterField.AUTHOR &&
                    state.filterVisibleRefs == setOf("refs/heads/topic") &&
                    !state.filterBusy
            }
            assertEquals(
                FilterField.AUTHOR,
                state(fixture).branchFilter.includes.single().field,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dateRuleShowsAfterAndBeforeAndValidatesBeforeApply() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture()
            try {
                setContent { App(fixture.manager) }
                awaitGraph(fixture)

                onNodeWithTag(UiTestTags.FILTERS_BUTTON).performClick()
                onNodeWithTag(UiTestTags.FILTERS_ADD_INCLUDE).performClick()
                onNodeWithTag(UiTestTags.filtersRuleField("include", 0), useUnmergedTree = true).performClick()
                onNodeWithTag(UiTestTags.filtersFieldOption(FilterField.DATE.name), useUnmergedTree = true).performClick()

                onNodeWithTag(UiTestTags.filtersRuleAfter("include", 0), useUnmergedTree = true).assertExists()
                onNodeWithTag(UiTestTags.filtersRuleBefore("include", 0), useUnmergedTree = true).assertExists()
                onNodeWithTag(UiTestTags.FILTERS_APPLY).assertIsNotEnabled()

                onNodeWithTag(UiTestTags.filtersRuleAfter("include", 0), useUnmergedTree = true)
                    .performTextInput("2024-13-01")
                onNodeWithTag(UiTestTags.FILTERS_APPLY).assertIsNotEnabled()

                onNodeWithTag(UiTestTags.filtersRuleAfter("include", 0), useUnmergedTree = true).performTextClearance()
                onNodeWithTag(UiTestTags.filtersRuleAfter("include", 0), useUnmergedTree = true)
                    .performTextInput("2024-01-01")
                onNodeWithTag(UiTestTags.FILTERS_APPLY).assertIsEnabled()
                saveScreenshot("37-filters-date.png")

                onNodeWithTag(UiTestTags.FILTERS_CANCEL).performClick()
                assertTrue(!state(fixture).branchFilter.isActive)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun clearAllAndCancelDoNotUnexpectedlyApplyDrafts() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture()
            try {
                setContent { App(fixture.manager) }
                awaitGraph(fixture)

                onNodeWithTag(UiTestTags.FILTERS_BUTTON).performClick()
                onNodeWithTag(UiTestTags.FILTERS_ADD_INCLUDE).performClick()
                onNodeWithTag(UiTestTags.filtersRuleValue("include", 0), useUnmergedTree = true).performTextInput("draft")
                onNodeWithTag(UiTestTags.FILTERS_CANCEL).performClick()
                assertTrue(!state(fixture).branchFilter.isActive)

                onNodeWithTag(UiTestTags.FILTERS_BUTTON).performClick()
                onNodeWithTag(UiTestTags.FILTERS_ADD_INCLUDE).performClick()
                onNodeWithTag(UiTestTags.filtersRuleValue("include", 0), useUnmergedTree = true)
                    .performTextInput("target topic commit")
                onNodeWithTag(UiTestTags.FILTERS_APPLY).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    state(fixture).filterVisibleRefs == setOf("refs/heads/topic")
                }

                onNodeWithTag(UiTestTags.FILTERS_BUTTON).performClick()
                onNodeWithTag(UiTestTags.FILTERS_CLEAR_ALL).performClick()
                onNodeWithTag(UiTestTags.filtersRule("include", 0), useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag(UiTestTags.FILTERS_APPLY).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    !state(fixture).branchFilter.isActive &&
                        state(fixture).filterVisibleRefs == null &&
                        !state(fixture).filterBusy
                }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun narrowWindowsStackTheTwoGroups() = runDesktopComposeUiTest(width = 900, height = 800) {
        val fixture = fixture()
        try {
            setContent { App(fixture.manager) }
            awaitGraph(fixture)
            onNodeWithTag(UiTestTags.FILTERS_BUTTON).performClick()
            onNodeWithTag(UiTestTags.FILTERS_DIALOG).assertExists()
            onNodeWithTag(UiTestTags.FILTERS_INCLUDE_SECTION, useUnmergedTree = true).assertExists()
            onNodeWithTag(UiTestTags.FILTERS_EXCLUDE_SECTION, useUnmergedTree = true).assertExists()
            saveScreenshot("38-filters-narrow.png")
        } finally {
            fixture.close()
        }
    }

    private fun ComposeUiTest.awaitGraph(fixture: UiFixture) {
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            fixture.manager.activeSession.value?.state?.value?.graph != null
        }
    }

    private fun state(fixture: UiFixture) =
        checkNotNull(fixture.manager.activeSession.value?.state?.value)

    private fun labelsAtTopicTip(fixture: UiFixture) =
        state(fixture).labelsByCommit["t".repeat(40)].orEmpty()

    private fun fixture(): UiFixture {
        val base = RawCommit(
            hash = "a".repeat(40),
            parents = emptyList(),
            authorName = "Base Author",
            authorTimeSec = 1_700_000_000L,
            subject = "shared root",
            authorEmail = "base@example.com",
        )
        val mainWork = RawCommit(
            hash = "m".repeat(40),
            parents = listOf(base.hash),
            authorName = "Main Author",
            authorTimeSec = 1_700_000_100L,
            subject = "main work",
            authorEmail = "main@example.com",
        )
        val mainTip = RawCommit(
            hash = "n".repeat(40),
            parents = listOf(mainWork.hash),
            authorName = "Main Author",
            authorTimeSec = 1_700_000_200L,
            subject = "main tip",
            authorEmail = "main@example.com",
        )
        val topicWork = RawCommit(
            hash = "s".repeat(40),
            parents = listOf(base.hash),
            authorName = "Topic Author",
            authorTimeSec = 1_700_000_300L,
            subject = "target topic commit",
            authorEmail = "topic@example.com",
        )
        val topicTip = RawCommit(
            hash = "t".repeat(40),
            parents = listOf(topicWork.hash),
            authorName = "Topic Author",
            authorTimeSec = 1_700_000_400L,
            subject = "topic tip",
            authorEmail = "topic@example.com",
        )
        val commits = listOf(mainTip, mainWork, topicTip, topicWork, base)
        return uiFixture(
            commits = commits,
            refs = listOf(
                GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, mainTip.hash, true, null),
                GitRef("refs/heads/topic", "topic", RefKind.LOCAL_BRANCH, topicTip.hash, false, null),
                GitRef("refs/remotes/origin/topic", "origin/topic", RefKind.REMOTE_BRANCH, topicTip.hash, false, null),
            ),
            head = HeadInfo(mainTip.hash, "main"),
            path = "/repo/filters",
        )
    }
}
