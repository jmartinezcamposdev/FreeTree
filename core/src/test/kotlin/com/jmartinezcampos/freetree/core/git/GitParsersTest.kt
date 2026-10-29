package com.jmartinezcampos.freetree.core.git

import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.RefKind
import com.jmartinezcampos.freetree.core.model.StatusKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitParsersTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/git/$name")) { "missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun refsPeelTagsAndDropSymrefsAndNonCommitRefs() {
        val refs = GitParsers.parseRefs(fixture("refs.bin"))

        assertEquals(8, refs.size)
        assertEquals(
            listOf("feature", "main", "octo1", "octo2"),
            refs.filter { it.kind == RefKind.LOCAL_BRANCH }.map { it.shortName },
        )
        val main = refs.single { it.fullName == "refs/heads/main" }
        assertTrue(main.isCurrentBranch)
        assertEquals("origin/main", main.upstream)
        assertEquals("4c81503612ee798066aec9edcce82bc95f4b9eeb", main.commit)

        val remote = refs.single { it.fullName == "refs/remotes/origin/feature" }
        assertEquals(RefKind.REMOTE_BRANCH, remote.kind)
        assertEquals("origin/feature", remote.shortName)

        val annotated = refs.single { it.fullName == "refs/tags/v1" }
        assertEquals(RefKind.TAG, annotated.kind)
        assertEquals("881f11e6ddd5a1b2ef78a3706b603822c960a46d", annotated.commit)
        assertFalse(annotated.isCurrentBranch)

        assertNull(refs.firstOrNull { it.fullName == "refs/remotes/origin/HEAD" })
        assertNull(refs.firstOrNull { it.fullName == "refs/tags/tree-tag" })
        assertNull(refs.firstOrNull { it.fullName == "refs/tags/blob-tag" })
    }

    @Test
    fun malformedRefLinesAreSkipped() {
        val refs = GitParsers.parseRefs("not enough fields\n${fixture("refs.bin")}")

        assertEquals(8, refs.size)
    }

    @Test
    fun emptyRefsProduceEmptyList() {
        assertTrue(GitParsers.parseRefs(fixture("refs_empty.bin")).isEmpty())
    }

    @Test
    fun logRecordsIncludeOctopusAndRootCommits() {
        val commits = fixture("log.bin").split('\u0000').filter { it.isNotEmpty() }
            .mapNotNull(GitParsers::parseLogRecord)

        assertEquals(5, commits.size)
        val octopus = commits.single { it.subject == "octopus merge" }
        assertEquals(3, octopus.parents.size)
        assertEquals("Author Ünicode", octopus.authorName)
        assertEquals(1793273700L, octopus.authorTimeSec)

        val root = commits.single { it.subject == "root commit" }
        assertTrue(root.parents.isEmpty())
    }

    @Test
    fun malformedLogRecordsAreSkipped() {
        val records = fixture("log.bin").split('\u0000').filter { it.isNotEmpty() } +
            listOf("bad", "aaaa\u001fbbbb\u001fname\u001fnot-a-time\u001fsubject")
        val commits = records.mapNotNull(GitParsers::parseLogRecord)

        assertEquals(5, commits.size)
    }

    @Test
    fun statusParsesBranchHeadersRenameUntrackedAndDeletes() {
        val status = GitParsers.parseStatus(fixture("status.bin"))

        assertEquals("4c81503612ee798066aec9edcce82bc95f4b9eeb", status.branchHead)
        assertEquals("origin/main", status.upstream)
        assertEquals(0, status.behind)
        assertEquals(4, status.ahead)
        assertFalse(status.unborn)
        assertEquals(8, status.entries.size)

        val both = status.entries.single { it.path == "both.txt" }
        assertEquals('M', both.index)
        assertEquals('M', both.worktree)
        assertEquals(StatusKind.ORDINARY, both.kind)

        val rename = status.entries.single { it.path == "status new.txt" }
        assertEquals(StatusKind.RENAMED, rename.kind)
        assertEquals("status-old.txt", rename.origPath)
        assertEquals('R', rename.index)
        assertEquals(' ', rename.worktree)

        val newline = status.entries.single { it.path == "line\nbreak.txt" }
        assertEquals(StatusKind.UNTRACKED, newline.kind)
        assertEquals('?', newline.index)

        assertEquals(
            "untracked ünïcode.txt",
            status.entries.single { it.kind == StatusKind.UNTRACKED && it.path != "line\nbreak.txt" }.path,
        )
        assertEquals('D', status.entries.single { it.path == "deleted-unstaged.txt" }.worktree)
        assertEquals(' ', status.entries.single { it.path == "deleted-unstaged.txt" }.index)
        assertTrue(status.entries.none { it.index == '.' || it.worktree == '.' })
    }

    @Test
    fun statusNormalizesPorcelainV2UnchangedSidesToSpaces() {
        val status = GitParsers.parseStatus(fixture("status_dots.bin"))
        val entries = status.entries.associateBy { it.path }
        assertEquals(5, entries.size)

        val staged = entries.getValue("staged.txt")
        assertEquals('M', staged.index)
        assertEquals(' ', staged.worktree)

        val unstaged = entries.getValue("unstaged.txt")
        assertEquals(' ', unstaged.index)
        assertEquals('M', unstaged.worktree)

        val partial = entries.getValue("partial.txt")
        assertEquals('M', partial.index)
        assertEquals('M', partial.worktree)

        val rename = entries.getValue("new.txt")
        assertEquals(StatusKind.RENAMED, rename.kind)
        assertEquals('R', rename.index)
        assertEquals(' ', rename.worktree)
        assertEquals("old.txt", rename.origPath)

        val unmerged = entries.getValue("conflict.txt")
        assertEquals(StatusKind.UNMERGED, unmerged.kind)
        assertEquals('U', unmerged.index)
        assertEquals('U', unmerged.worktree)

        assertTrue(status.entries.none { it.index == '.' || it.worktree == '.' })
    }

    @Test
    fun statusParsesUnbornDetachedAndConflicts() {
        val unborn = GitParsers.parseStatus(fixture("status_unborn.bin"))
        assertTrue(unborn.unborn)
        assertNull(unborn.branchHead)
        assertEquals(1, unborn.entries.size)
        assertEquals('A', unborn.entries.single().index)
        assertEquals(' ', unborn.entries.single().worktree)

        val detached = GitParsers.parseStatus(fixture("status_detached.bin"))
        assertFalse(detached.unborn)
        assertEquals(fixture("head_detached_commit.txt").trim(), detached.branchHead)
        assertTrue(detached.entries.isEmpty())

        val conflict = GitParsers.parseStatus(fixture("status_conflict.bin"))
        val entry = conflict.entries.single()
        assertEquals(StatusKind.UNMERGED, entry.kind)
        assertEquals('U', entry.index)
        assertEquals('U', entry.worktree)
        assertEquals("conflict.txt", entry.path)
    }

    @Test
    fun statusParsesAheadBehindAndIgnoresIgnoredEntries() {
        val status = GitParsers.parseStatus(
            "# branch.oid abc\u0000# branch.head main\u0000# branch.upstream origin/main\u0000" +
                "# branch.ab +3 -4\u0000! ignored.txt\u0000? new.txt\u0000",
        )

        assertEquals("origin/main", status.upstream)
        assertEquals(3, status.ahead)
        assertEquals(4, status.behind)
        assertEquals(1, status.entries.size)
        assertEquals("new.txt", status.entries.single().path)
    }

    @Test
    fun emptyStatusHasNeutralDefaults() {
        val status = GitParsers.parseStatus("")

        assertNull(status.branchHead)
        assertNull(status.upstream)
        assertFalse(status.unborn)
        assertTrue(status.entries.isEmpty())
    }

    @Test
    fun nameStatusParsesRenameDeleteAddAndUnicode() {
        val changes = GitParsers.parseNameStatus(fixture("name_status.bin"))

        assertEquals(6, changes.size)
        assertEquals(listOf('M', 'D', 'M', 'A', 'R', 'M'), changes.map { it.status })
        val rename = changes.single { it.status == 'R' }
        assertEquals("rename new.txt", rename.path)
        assertEquals("rename old.txt", rename.oldPath)
        assertEquals("unicodé/naïve.txt", changes.last().path)
        assertNull(changes.first().oldPath)
    }

    @Test
    fun nameStatusHandlesCopyAndTrailingTokens() {
        val changes = GitParsers.parseNameStatus("C75\u0000old\u0000new\u0000M\u0000plain\u0000")

        assertEquals(2, changes.size)
        assertEquals('C', changes[0].status)
        assertEquals("old", changes[0].oldPath)
        assertEquals("new", changes[0].path)
        assertEquals("plain", changes[1].path)
    }

    @Test
    fun commitDetailPreservesMultilineUnicodeMessage() {
        val detail = checkNotNull(GitParsers.parseCommitDetail(fixture("commit_detail.bin")))

        assertEquals("4c81503612ee798066aec9edcce82bc95f4b9eeb", detail.hash)
        assertEquals(listOf("752d669f949c164ea61eed4850e09948de635c47"), detail.parents)
        assertEquals("Author Ünicode", detail.authorName)
        assertEquals("author@example.com", detail.authorEmail)
        assertEquals("changes: rename and ünïcode\n\nSecond line.\nThird line.\n", detail.message)
    }

    @Test
    fun diffParsesHunksAndLineNumbers() {
        val diff = GitParsers.parseDiff(fixture("diff.txt"))

        assertFalse(diff.binary)
        assertFalse(diff.truncated)
        assertEquals(DiffLineKind.FILE_HEADER, diff.lines.first().kind)
        val hunk = diff.lines.first { it.kind == DiffLineKind.HUNK_HEADER }
        assertEquals("@@ -1,5 +1,5 @@", hunk.text)

        val removed = diff.lines.single { it.kind == DiffLineKind.DEL && it.text == "line 2" }
        assertEquals(2, removed.oldNo)
        assertNull(removed.newNo)
        val added = diff.lines.single { it.kind == DiffLineKind.ADD && it.text == "line 2 changed" }
        assertNull(added.oldNo)
        assertEquals(2, added.newNo)
        val context = diff.lines.single { it.kind == DiffLineKind.CONTEXT && it.text == "line 1" }
        assertEquals(1, context.oldNo)
        assertEquals(1, context.newNo)

        val secondHunk = diff.lines.filter { it.kind == DiffLineKind.HUNK_HEADER }[1]
        assertEquals("@@ -15,6 +15,6 @@ line 14", secondHunk.text)
        val secondAdd = diff.lines.single { it.kind == DiffLineKind.ADD && it.text == "line 18 changed" }
        assertEquals(18, secondAdd.newNo)
    }

    @Test
    fun diffParsesRenameHeadersAndBinaryFiles() {
        val rename = GitParsers.parseDiff(fixture("diff_rename.txt"))
        assertEquals(4, rename.lines.size)
        assertTrue(rename.lines.all { it.kind == DiffLineKind.FILE_HEADER })
        assertFalse(rename.binary)

        val binary = GitParsers.parseDiff(fixture("diff_binary.txt"))
        assertTrue(binary.binary)
        assertTrue(binary.lines.any { it.kind == DiffLineKind.NOTE && it.text.contains("Binary files") })
    }

    @Test
    fun diffParsesNoNewlineNote() {
        val diff = GitParsers.parseDiff(
            "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-old\n+new\n\\ No newline at end of file\n",
        )

        val note = diff.lines.single { it.kind == DiffLineKind.NOTE }
        assertEquals("\\ No newline at end of file", note.text)
        assertEquals(1, diff.lines.single { it.kind == DiffLineKind.ADD }.newNo)
    }

    @Test
    fun emptyDiffParsesToEmptyText() {
        val diff = GitParsers.parseDiff("")

        assertTrue(diff.lines.isEmpty())
        assertFalse(diff.binary)
        assertFalse(diff.truncated)
    }

    @Test
    fun addedTextHandlesEmptyCrLfAndMissingFinalNewline() {
        assertTrue(GitParsers.addedTextDiff("").isEmpty())
        val oneEmptyLine = GitParsers.addedTextDiff("\n")
        assertEquals(1, oneEmptyLine.size)
        assertEquals("", oneEmptyLine.single().text)
        assertEquals(1, oneEmptyLine.single().newNo)

        val crlf = GitParsers.addedTextDiff("a\r\nb\r\n")
        assertEquals(listOf("a", "b"), crlf.map { it.text })

        val noFinalNewline = GitParsers.addedTextDiff("a\nb")
        assertEquals(listOf("a", "b"), noFinalNewline.map { it.text })
        assertEquals(listOf(1, 2), noFinalNewline.map { it.newNo })
    }
}
