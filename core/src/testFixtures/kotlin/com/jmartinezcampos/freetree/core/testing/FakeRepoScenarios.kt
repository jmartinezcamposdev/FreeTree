package com.jmartinezcampos.freetree.core.testing

import com.jmartinezcampos.freetree.core.model.CommitDetail
import com.jmartinezcampos.freetree.core.model.DiffLine
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.model.DiffText
import com.jmartinezcampos.freetree.core.model.FileChange
import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.HeadInfo
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusEntry
import com.jmartinezcampos.freetree.core.model.StatusKind
import com.jmartinezcampos.freetree.core.model.WorkingStatus

data class FakeRepoScenario(
    val commits: List<RawCommit>,
    val refs: List<GitRef>,
    val head: HeadInfo,
    val status: WorkingStatus,
    val commitDetails: Map<String, CommitDetail> = emptyMap(),
    val changedFiles: Map<String, List<FileChange>> = emptyMap(),
    val diffs: Map<DiffTarget, DiffText> = emptyMap(),
) {
    fun backend(statefulWrites: Boolean = false): FakeGitBackend = FakeGitBackend(
        commits = commits,
        refs = refs,
        head = head,
        status = status,
        commitDetails = commitDetails,
        changedFiles = changedFiles,
        diffs = diffs,
        statefulWrites = statefulWrites,
    )
}

object FakeRepoScenarios {

    fun hash(number: Int): String = number.toString(16).padStart(2, '0').repeat(20)

    val mainHash: String = hash(9)

    private val Merge = hash(9)
    private val TopicTip = hash(8)
    private val TopicBase = hash(7)
    private val SharedBase = hash(6)
    private val Older = hash(5)
    private val Tagged = hash(4)
    private val Root = hash(1)

    private val commitList: List<RawCommit> = listOf(
        RawCommit(Merge, listOf(TopicTip, TopicBase), "Ada Lovelace", 1_700_000_900L, "Merge topic into main", "ada@example.com"),
        RawCommit(TopicTip, listOf(SharedBase), "Grace Hopper", 1_700_000_800L, "Add graph panel", "grace@example.com"),
        RawCommit(TopicBase, listOf(SharedBase), "Ada Lovelace", 1_700_000_700L, "Start topic work", "ada@example.com"),
        RawCommit(SharedBase, listOf(Older), "Linus Torvalds", 1_700_000_600L, "Refactor session wiring", "linus@example.com"),
        RawCommit(Older, listOf(Tagged), "Grace Hopper", 1_700_000_500L, "Fix query parser", "grace@example.com"),
        RawCommit(Tagged, listOf(hash(3)), "Ada Lovelace", 1_700_000_400L, "Release candidate", "ada@example.com"),
        RawCommit(hash(3), listOf(hash(2)), "Linus Torvalds", 1_700_000_300L, "Add diff view", "linus@example.com"),
        RawCommit(hash(2), listOf(Root), "Grace Hopper", 1_700_000_200L, "Add graph list", "grace@example.com"),
        RawCommit(Root, emptyList(), "Ada Lovelace", 1_700_000_100L, "Initial commit", "ada@example.com"),
    )

    private val refList: List<GitRef> = listOf(
        GitRef("refs/heads/main", "main", RefKind.LOCAL_BRANCH, Merge, true, "origin/main"),
        GitRef("refs/remotes/origin/main", "origin/main", RefKind.REMOTE_BRANCH, Merge, false, null),
        GitRef("refs/heads/topic", "topic", RefKind.LOCAL_BRANCH, TopicTip, false, null),
        GitRef("refs/remotes/origin/topic", "origin/topic", RefKind.REMOTE_BRANCH, TopicTip, false, null),
        GitRef("refs/tags/v1.0", "v1.0", RefKind.TAG, Tagged, false, null),
        GitRef("refs/tags/v2.0", "v2.0", RefKind.TAG, Merge, false, null),
    )

    private val appChange = FileChange('M', "src/App.kt", null)
    private val graphChange = FileChange('A', "src/Graph.kt", null)
    private val renameChange = FileChange('R', "src/Names.kt", "src/Old.kt")

    private val commitDetailMap: Map<String, CommitDetail> = commitList.associate { commit ->
        commit.hash to CommitDetail(
            hash = commit.hash,
            parents = commit.parents,
            authorName = commit.authorName,
            authorEmail = commit.authorName.lowercase().replace(' ', '.') + "@example.com",
            authorTimeSec = commit.authorTimeSec,
            committerName = commit.authorName,
            committerTimeSec = commit.authorTimeSec,
            message = commit.subject,
        )
    }

    private val changedFilesMap: Map<String, List<FileChange>> = mapOf(
        Merge to listOf(appChange, graphChange, renameChange),
        TopicTip to listOf(FileChange('M', "src/Graph.kt", null)),
        TopicBase to listOf(graphChange),
    )

    val commitDiff: DiffText = unifiedDiff(
        path = "src/App.kt",
        oldLines = listOf("fun main() {", "    oldPanel()", "}"),
        newLines = listOf("fun main() {", "    appShell()", "    graphPanel()", "}"),
    )

    val stagedDiff: DiffText = unifiedDiff(
        path = "src/Staged.kt",
        oldLines = listOf("val staged = false"),
        newLines = listOf("val staged = true"),
    )

    val unstagedDiff: DiffText = unifiedDiff(
        path = "README.md",
        oldLines = listOf("FreeTree"),
        newLines = listOf("FreeTree", "Graph client"),
    )

    private val diffMap: Map<DiffTarget, DiffText> = buildMap {
        put(DiffTarget.InCommit(Merge, appChange), commitDiff)
        put(DiffTarget.InCommit(Merge, graphChange), unifiedDiff("src/Graph.kt", emptyList(), listOf("class Graph")))
        put(DiffTarget.InCommit(Merge, renameChange), unifiedDiff("src/Names.kt", listOf("old"), listOf("new")))
        put(DiffTarget.Staged("src/Staged.kt"), stagedDiff)
        put(DiffTarget.Unstaged("README.md"), unstagedDiff)
        put(DiffTarget.Unstaged("src/App.kt"), commitDiff)
    }

    fun standard(): FakeRepoScenario = FakeRepoScenario(
        commits = commitList,
        refs = refList,
        head = HeadInfo(Merge, "main"),
        status = WorkingStatus(
            branchHead = Merge,
            upstream = "origin/main",
            ahead = 2,
            behind = 1,
            unborn = false,
            entries = standardEntries(),
        ),
        commitDetails = commitDetailMap,
        changedFiles = changedFilesMap,
        diffs = diffMap,
    )

    fun changesOnly(): FakeRepoScenario = standard().copy(
        commits = emptyList(),
        refs = emptyList(),
        head = HeadInfo(null, "main"),
        status = WorkingStatus(
            branchHead = null,
            upstream = null,
            ahead = 0,
            behind = 0,
            unborn = true,
            entries = standardEntries(),
        ),
        commitDetails = emptyMap(),
        changedFiles = emptyMap(),
        diffs = diffMap,
    )

    fun standardEntries(): List<StatusEntry> = listOf(
        StatusEntry("src/App.kt", null, 'M', 'M', StatusKind.ORDINARY),
        StatusEntry("src/Staged.kt", null, 'M', ' ', StatusKind.ORDINARY),
        StatusEntry("README.md", null, ' ', 'M', StatusKind.ORDINARY),
        StatusEntry("notes.txt", null, '?', '?', StatusKind.UNTRACKED),
    )

    fun unifiedDiff(path: String, oldLines: List<String>, newLines: List<String>): DiffText {
        val lines = buildList {
            add(DiffLine(DiffLineKind.FILE_HEADER, null, null, "diff --git a/$path b/$path"))
            add(DiffLine(DiffLineKind.FILE_HEADER, null, null, "--- a/$path"))
            add(DiffLine(DiffLineKind.FILE_HEADER, null, null, "+++ b/$path"))
            add(
                DiffLine(
                    DiffLineKind.HUNK_HEADER,
                    null,
                    null,
                    "@@ -1,${oldLines.size} +1,${newLines.size} @@",
                ),
            )
            oldLines.forEachIndexed { index, text ->
                add(DiffLine(DiffLineKind.DEL, index + 1, null, text))
            }
            newLines.forEachIndexed { index, text ->
                add(DiffLine(DiffLineKind.ADD, null, index + 1, text))
            }
        }
        return DiffText(lines = lines, binary = false, truncated = false)
    }
}
