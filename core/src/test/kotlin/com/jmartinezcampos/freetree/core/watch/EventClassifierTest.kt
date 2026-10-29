package com.jmartinezcampos.freetree.core.watch

import com.jmartinezcampos.freetree.core.model.GitDirs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventClassifierTest {
    private val base: Path = Path.of(System.getProperty("java.io.tmpdir")).resolve("freetree-classifier")
    private val workTree: Path = base.resolve("work")
    private val gitDir: Path = workTree.resolve(".git")
    private val dirs = GitDirs(workTree = workTree, gitDir = gitDir, commonDir = gitDir)

    @Test
    fun lockFilesAreIgnoredEverywhere() {
        assertNull(EventClassifier.classify(gitDir.resolve("index.lock"), dirs))
        assertNull(EventClassifier.classify(commonRefs().resolve("heads/main.lock"), dirs))
        assertNull(EventClassifier.classify(workTree.resolve("file.lock"), dirs))
        assertNull(EventClassifier.classify(gitDir.resolve("packed-refs.lock"), dirs))
    }

    @Test
    fun refsPackedRefsAndHeadAreRefChanges() {
        assertEquals(WatchEvent.REFS_CHANGED, EventClassifier.classify(commonRefs().resolve("heads/main"), dirs))
        assertEquals(WatchEvent.REFS_CHANGED, EventClassifier.classify(commonRefs().resolve("tags/v1"), dirs))
        assertEquals(WatchEvent.REFS_CHANGED, EventClassifier.classify(commonRefs().resolve("remotes/origin/main"), dirs))
        assertEquals(WatchEvent.REFS_CHANGED, EventClassifier.classify(gitDir.resolve("packed-refs"), dirs))
        assertEquals(WatchEvent.REFS_CHANGED, EventClassifier.classify(gitDir.resolve("HEAD"), dirs))
    }

    @Test
    fun indexAndOperationFilesAreStatusChanges() {
        assertEquals(WatchEvent.STATUS_CHANGED, EventClassifier.classify(gitDir.resolve("index"), dirs))
        listOf("MERGE_HEAD", "REBASE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD").forEach { file ->
            assertEquals(WatchEvent.STATUS_CHANGED, EventClassifier.classify(gitDir.resolve(file), dirs), file)
        }
        assertEquals(
            WatchEvent.STATUS_CHANGED,
            EventClassifier.classify(gitDir.resolve("rebase-merge/head-name"), dirs),
        )
        assertEquals(
            WatchEvent.STATUS_CHANGED,
            EventClassifier.classify(gitDir.resolve("rebase-apply/applying"), dirs),
        )
    }

    @Test
    fun otherFilesInsideGitDirAreIgnored() {
        assertNull(EventClassifier.classify(gitDir.resolve("objects/ab/cdef"), dirs))
        assertNull(EventClassifier.classify(gitDir.resolve("logs/HEAD"), dirs))
        assertNull(EventClassifier.classify(gitDir.resolve("config"), dirs))
        assertNull(EventClassifier.classify(gitDir.resolve("MERGE_MSG"), dirs))
    }

    @Test
    fun workTreeChangesOutsideDotGitAreStatusChanges() {
        assertEquals(WatchEvent.STATUS_CHANGED, EventClassifier.classify(workTree.resolve("src/Main.kt"), dirs))
        assertEquals(WatchEvent.STATUS_CHANGED, EventClassifier.classify(workTree.resolve("new file ünïcode"), dirs))
        assertNull(EventClassifier.classify(workTree.resolve(".git/config"), dirs))
        assertNull(EventClassifier.classify(workTree.resolve("nested/.git/config"), dirs))
    }

    @Test
    fun pathsOutsideTheRepositoryAreIgnored() {
        assertNull(EventClassifier.classify(base.resolve("elsewhere/file.txt"), dirs))
        assertNull(EventClassifier.classify(Path.of("/somewhere/else"), dirs))
    }

    @Test
    fun separateCommonAndGitDirectoriesAreClassified() {
        val mainGit = base.resolve("main/.git")
        val worktreeGit = mainGit.resolve("worktrees/wt")
        val linkedWorkTree = base.resolve("wt")
        val linked = GitDirs(workTree = linkedWorkTree, gitDir = worktreeGit, commonDir = mainGit)

        assertEquals(WatchEvent.REFS_CHANGED, EventClassifier.classify(mainGit.resolve("refs/heads/main"), linked))
        assertEquals(WatchEvent.REFS_CHANGED, EventClassifier.classify(mainGit.resolve("packed-refs"), linked))
        assertEquals(WatchEvent.REFS_CHANGED, EventClassifier.classify(worktreeGit.resolve("HEAD"), linked))
        assertEquals(WatchEvent.STATUS_CHANGED, EventClassifier.classify(worktreeGit.resolve("index"), linked))
        assertEquals(WatchEvent.STATUS_CHANGED, EventClassifier.classify(linkedWorkTree.resolve("file.txt"), linked))
        assertNull(EventClassifier.classify(linkedWorkTree.resolve(".git"), linked))
    }

    private fun commonRefs(): Path = gitDir.resolve("refs")
}
