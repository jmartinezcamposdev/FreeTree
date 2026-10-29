package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.jmartinezcampos.freetree.app.App
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus
import com.jmartinezcampos.freetree.core.testing.FakeMethod
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ChangesPanelUiTest {

    private val commits = listOf(
        RawCommit("a".repeat(40), emptyList(), "Ada", 1_700_000_000L, "root"),
        RawCommit("b".repeat(40), listOf("a".repeat(40)), "Grace", 1_700_000_060L, "tip"),
    )
    private val refs = listOf(
        GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, commits[1].hash, true, null),
    )

    private fun fixture(status: WorkingStatus) = uiFixture(
        commits = commits,
        refs = refs,
        head = HeadInfo(commits[1].hash, "main"),
        status = status,
    )

    @Test
    fun fileInBothSectionsShowsBothDiscardActionsAndConflictsAreReadOnly() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture(
                WorkingStatus(
                    commits[1].hash,
                    null,
                    0,
                    0,
                    false,
                    listOf(
                        StatusEntry("src/App.kt", null, 'M', 'M', StatusKind.ORDINARY),
                        StatusEntry("src/Staged.kt", null, 'M', ' ', StatusKind.ORDINARY),
                        StatusEntry("README.md", null, ' ', 'M', StatusKind.ORDINARY),
                        StatusEntry("notes.txt", null, '?', '?', StatusKind.UNTRACKED),
                        StatusEntry("conflict.txt", null, 'U', 'U', StatusKind.UNMERGED),
                    ),
                ),
            )
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.status != null
                }

                onNodeWithTag(UiTestTags.CHANGES_ROW).assertTextContains("Changes (5)")
                onNodeWithTag(UiTestTags.CONFLICTS_SECTION).assertExists()
                onNodeWithTag(UiTestTags.STAGED_SECTION).assertExists()
                onNodeWithTag(UiTestTags.UNSTAGED_SECTION).assertExists()
                onNodeWithTag(UiTestTags.stagedFile("src/App.kt")).assertTextContains("src/App.kt")
                onNodeWithTag(UiTestTags.unstagedFile("src/App.kt")).assertTextContains("src/App.kt")
                onNodeWithTag(UiTestTags.discardStaged("src/App.kt")).assertExists()
                onNodeWithTag(UiTestTags.discardUnstaged("src/App.kt")).assertExists()
                onNodeWithTag(UiTestTags.stageFile("src/App.kt")).assertExists()
                onNodeWithTag(UiTestTags.conflictFile("conflict.txt")).assertExists()
                onNodeWithTag(UiTestTags.discardUnstaged("conflict.txt")).assertDoesNotExist()
                onNodeWithTag(UiTestTags.discardStaged("conflict.txt")).assertDoesNotExist()

                onNodeWithTag(UiTestTags.discardStaged("src/App.kt")).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    fixture.backend.callCount(FakeMethod.DISCARD_STAGED) == 1
                }
                assertEquals(
                    listOf("src/App.kt"),
                    fixture.backend.callsOf(FakeMethod.DISCARD_STAGED).single().paths,
                )

                onNodeWithTag(UiTestTags.stageFile("README.md")).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    fixture.backend.callCount(FakeMethod.STAGE) == 1
                }
                assertEquals(listOf("README.md"), fixture.backend.callsOf(FakeMethod.STAGE).single().paths)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun unstagedDiscardRequiresConfirmationAndStatesWhatIsLost() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture(
                WorkingStatus(
                    commits[1].hash,
                    null,
                    0,
                    0,
                    false,
                    listOf(
                        StatusEntry("README.md", null, ' ', 'M', StatusKind.ORDINARY),
                        StatusEntry("notes.txt", null, '?', '?', StatusKind.UNTRACKED),
                    ),
                ),
            )
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.status != null
                }

                onNodeWithTag(UiTestTags.discardUnstaged("notes.txt")).performClick()
                onNodeWithTag(UiTestTags.DISCARD_DIALOG).assertExists()
                onNodeWithText("permanently deletes \"notes.txt\" from disk", substring = true).assertExists()
                onNodeWithTag(UiTestTags.DISCARD_CANCEL).performClick()
                onNodeWithTag(UiTestTags.DISCARD_DIALOG).assertDoesNotExist()
                assertEquals(0, fixture.backend.callCount(FakeMethod.DISCARD_UNSTAGED))

                onNodeWithTag(UiTestTags.discardUnstaged("README.md")).performClick()
                onNodeWithText("restores \"README.md\" from the index", substring = true).assertExists()
                onNodeWithText("The staged content is kept.", substring = true).assertExists()
                onNodeWithTag(UiTestTags.DISCARD_CONFIRM).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    fixture.backend.callCount(FakeMethod.DISCARD_UNSTAGED) == 1
                }
                assertEquals(
                    listOf("README.md"),
                    fixture.backend.callsOf(FakeMethod.DISCARD_UNSTAGED).single().paths,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun porcelainDotStatusesDoNotMoveFilesIntoTheWrongSection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture(
                WorkingStatus(
                    commits[1].hash,
                    null,
                    0,
                    0,
                    false,
                    listOf(
                        StatusEntry("src/Staged.kt", null, 'A', '.', StatusKind.ORDINARY),
                        StatusEntry("README.md", null, '.', 'M', StatusKind.ORDINARY),
                        StatusEntry("src/App.kt", null, 'M', 'M', StatusKind.ORDINARY),
                    ),
                ),
            )
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.status != null
                }

                onNodeWithTag(UiTestTags.stagedFile("src/Staged.kt")).assertExists()
                onNodeWithTag(UiTestTags.unstagedFile("src/Staged.kt")).assertDoesNotExist()
                onNodeWithTag(UiTestTags.stagedFile("README.md")).assertDoesNotExist()
                onNodeWithTag(UiTestTags.unstagedFile("README.md")).assertExists()
                onNodeWithTag(UiTestTags.stagedFile("src/App.kt")).assertExists()
                onNodeWithTag(UiTestTags.unstagedFile("src/App.kt")).assertExists()

                onNodeWithTag(UiTestTags.COMMIT_MESSAGE).performTextInput("Commit staged")
                waitForIdle()
                onNodeWithTag(UiTestTags.COMMIT_BUTTON).assertIsEnabled()
                onNodeWithTag(UiTestTags.COMMIT_BUTTON).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    fixture.backend.callCount(FakeMethod.COMMIT) == 1
                }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun commitIsDisabledWithoutMessageAndEnabledWithMessageAndStagedFiles() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture(
                WorkingStatus(
                    commits[1].hash,
                    null,
                    0,
                    0,
                    false,
                    listOf(
                        StatusEntry("src/Staged.kt", null, 'M', ' ', StatusKind.ORDINARY),
                        StatusEntry("README.md", null, ' ', 'M', StatusKind.ORDINARY),
                    ),
                ),
            )
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.status != null
                }

                onNodeWithTag(UiTestTags.COMMIT_BUTTON).assertIsNotEnabled()
                onNodeWithTag(UiTestTags.COMMIT_MESSAGE).performTextInput("Stage the panel")
                waitForIdle()
                onNodeWithTag(UiTestTags.COMMIT_BUTTON).assertIsEnabled()
                onNodeWithTag(UiTestTags.COMMIT_BUTTON).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    fixture.backend.callCount(FakeMethod.COMMIT) == 1
                }
                assertEquals("Stage the panel", fixture.backend.callsOf(FakeMethod.COMMIT).single().message)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun commitStaysDisabledWithoutStagedFilesEvenWithMessage() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val fixture = fixture(
                WorkingStatus(
                    commits[1].hash,
                    null,
                    0,
                    0,
                    false,
                    listOf(StatusEntry("README.md", null, ' ', 'M', StatusKind.ORDINARY)),
                ),
            )
            try {
                setContent { App(fixture.manager) }
                waitUntil(timeoutMillis = 5_000) {
                    fixture.manager.activeSession.value?.state?.value?.status != null
                }

                onNodeWithTag(UiTestTags.COMMIT_MESSAGE).performTextInput("No staged files")
                waitForIdle()
                onNodeWithTag(UiTestTags.COMMIT_BUTTON).assertIsNotEnabled()
            } finally {
                fixture.close()
            }
        }
}
