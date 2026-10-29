package com.jmartinezcampos.freetree.core.qa

import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitTestRepoTest {

    @Test
    fun createsCommitsBranchesAndMerge() {
        GitTestRepo.create().use { repo ->
            assertEquals("main", repo.currentBranch())
            assertFalse(repo.hasHead())
            assertTrue(repo.statusPorcelain().isEmpty())

            repo.writeFile("README.md", "hello\n")
            repo.stage("README.md").requireSuccess()
            val initial = repo.commit("initial")
            assertEquals(initial, repo.headHash())
            assertEquals("hello\n", repo.readFile("README.md"))

            repo.checkoutNewBranch("feature").requireSuccess()
            val feature = repo.commitFile("feature.txt", "feature\n", "feature commit")

            repo.checkout("main").requireSuccess()
            val main = repo.commitFile("main.txt", "main\n", "main commit")

            repo.merge("feature").requireSuccess()

            val parents = repo.gitOk("show", "-s", "--format=%P", "HEAD")
                .split(" ")
                .filter { it.isNotBlank() }
            assertEquals(2, parents.size)
            assertTrue(parents.contains(feature))
            assertTrue(parents.contains(main))
            assertEquals("main", repo.currentBranch())
            assertTrue(repo.statusPorcelain().isEmpty())

            val subjects = repo.git("log", "--format=%s", "--topo-order").stdoutLines()
            assertEquals("Merge branch 'feature'", subjects.first())
            assertEquals(setOf("main commit", "feature commit", "initial"), subjects.drop(1).toSet())
        }
    }

    @Test
    fun stagesUnstagesRenamesDeletesAndLeavesUntrackedFiles() {
        GitTestRepo.create().use { repo ->
            repo.commitFile("dir/a.txt", "one\n", "add a")
            repo.commitFile("dir/b.txt", "two\n", "add b")

            repo.renameTracked("dir/a.txt", "dir/renamed.txt").requireSuccess()
            repo.deleteTracked("dir/b.txt").requireSuccess()
            repo.createUntrackedFile("dir/new.txt", "new\n")

            val status = repo.statusPorcelain()
            assertTrue(status.contains("R  dir/a.txt -> dir/renamed.txt"), status)
            assertTrue(status.contains("D  dir/b.txt"), status)
            assertTrue(status.contains("?? dir/new.txt"), status)

            assertEquals("one\n", repo.readFile("dir/renamed.txt"))
            assertFalse(repo.exists("dir/b.txt"))
            assertEquals("new\n", repo.readFile("dir/new.txt"))

            repo.writeFile("dir/renamed.txt", "one\nmore\n")
            repo.appendFile("dir/new.txt", "extra\n")
            assertEquals("one\nmore\n", repo.readFile("dir/renamed.txt"))
            assertEquals("new\nextra\n", repo.readFile("dir/new.txt"))

            repo.renameFile("dir/new.txt", "dir/moved.txt")
            assertTrue(repo.exists("dir/moved.txt"))
            assertFalse(repo.exists("dir/new.txt"))
            repo.deleteFile("dir/moved.txt")
            assertFalse(repo.exists("dir/moved.txt"))
        }
    }

    @Test
    fun stageAndUnstageMatchPorcelainStatus() {
        GitTestRepo.create().use { repo ->
            repo.commitFile("tracked.txt", "v1\n", "base")
            repo.writeFile("tracked.txt", "v2\n")
            repo.writeFile("added.txt", "added\n")
            repo.createUntrackedFile("untracked.txt")

            repo.stage("tracked.txt", "added.txt").requireSuccess()

            var status = repo.statusPorcelain()
            assertTrue(status.contains("M  tracked.txt"), status)
            assertTrue(status.contains("A  added.txt"), status)
            assertTrue(status.contains("?? untracked.txt"), status)
            assertEquals(
                setOf("tracked.txt", "added.txt"),
                repo.gitOk("diff", "--cached", "--name-only").lines().toSet(),
            )

            repo.unstage("added.txt").requireSuccess()

            status = repo.statusPorcelain()
            assertTrue(status.contains("M  tracked.txt"), status)
            assertTrue(status.contains("?? added.txt"), status)
            assertEquals("tracked.txt", repo.gitOk("diff", "--cached", "--name-only"))

            repo.stageAll().requireSuccess()
            val committed = repo.commit("stage all")
            assertEquals(committed, repo.headHash())
            assertTrue(repo.statusPorcelain().isEmpty())
        }
    }

    @Test
    fun unbornRepositorySupportsUnstageAndFirstCommit() {
        GitTestRepo.create().use { repo ->
            assertFalse(repo.hasHead())
            repo.writeFile("first.txt", "first\n")
            repo.stage("first.txt").requireSuccess()

            assertTrue(repo.statusPorcelain().contains("A  first.txt"))
            repo.unstage("first.txt").requireSuccess()
            assertTrue(repo.statusPorcelain().contains("?? first.txt"))

            repo.stageAll().requireSuccess()
            val first = repo.commit("first commit")
            assertEquals(first, repo.headHash())
            assertEquals("first commit", repo.gitOk("log", "-1", "--format=%s"))
        }
    }

    @Test
    fun localBareOriginSupportsPushPullAndClone() {
        GitTestRepo.create().use { repo ->
            repo.commitFile("README.md", "v1\n", "initial")
            val origin = repo.createBareOrigin()

            assertTrue(Files.isDirectory(origin.path))
            assertEquals("origin", origin.name)
            repo.push().requireSuccess()

            GitTestRepo.clone(origin).use { clone ->
                assertEquals(repo.headHash(), clone.headHash())
                assertEquals("v1\n", clone.readFile("README.md"))

                repo.commitFile("README.md", "v2\n", "second")
                repo.push().requireSuccess()

                clone.pull().requireSuccess()
                assertEquals(repo.headHash(), clone.headHash())
                assertEquals("v2\n", clone.readFile("README.md"))

                clone.commitFile("clone.txt", "from clone\n", "clone commit")
                clone.push().requireSuccess()

                repo.pull().requireSuccess()
                assertEquals(clone.headHash(), repo.headHash())
                assertEquals("from clone\n", repo.readFile("clone.txt"))
            }
        }
    }

    @Test
    fun pushFailureIsReturnedNotThrown() {
        GitTestRepo.create().use { repo ->
            repo.commitFile("README.md", "v1\n", "initial")
            repo.addRemote("origin", Path.of(repo.root.toString(), "missing.git")).requireSuccess()

            val result = repo.push()

            assertFalse(result.succeeded)
            assertTrue(result.stderr.isNotBlank())
        }
    }

    @Test
    fun closeDeletesTheTemporaryTree() {
        val repo = GitTestRepo.create()
        val root = repo.root
        repo.close()

        assertFalse(Files.exists(root))
    }
}
