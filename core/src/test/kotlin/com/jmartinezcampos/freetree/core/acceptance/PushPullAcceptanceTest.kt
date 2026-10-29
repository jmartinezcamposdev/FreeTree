package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class PushPullAcceptanceTest {
    private lateinit var repo: GitTestRepo
    private lateinit var backend: CliGitBackend

    @BeforeTest
    fun setUp() {
        repo = GitTestRepo.create()
        backend = runBlocking { repo.openBackend() }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    @Test
    fun missingUpstreamFailsPushAndPull() = runBlocking {
        repo.commitFile("a.txt", "a\n", "root")
        repo.createBareOrigin()

        assertFailure(backend.push())
        assertFailure(backend.pull())
    }

    @Test
    fun pushAndFastForwardPullAgainstLocalBareOrigin() = runBlocking {
        repo.commitFile("a.txt", "one\n", "root")
        val origin = repo.createBareOrigin()
        repo.git("push", "-u", "origin", "main").requireSuccess()

        repo.commitFile("a.txt", "two\n", "second")
        assertOk(backend.push())
        assertEquals(
            repo.headHash(),
            repo.gitIn(origin.path, "rev-parse", "refs/heads/main").requireSuccess().stdout.trim(),
        )

        GitTestRepo.clone(origin).use { clone ->
            val cloneBackend = clone.openBackend()
            repo.commitFile("a.txt", "three\n", "third")
            assertOk(backend.push())

            assertOk(cloneBackend.pull())

            assertEquals(repo.headHash(), clone.headHash())
            assertEquals("three\n", clone.readFile("a.txt"))
        }
    }

    @Test
    fun divergentPullAndRejectedPushPreserveGitErrors() = runBlocking {
        repo.commitFile("a.txt", "one\n", "root")
        val origin = repo.createBareOrigin()
        repo.git("push", "-u", "origin", "main").requireSuccess()

        GitTestRepo.clone(origin).use { clone ->
            val cloneBackend = clone.openBackend()

            repo.commitFile("remote.txt", "remote\n", "remote")
            assertOk(backend.push())
            val remoteHead = repo.headHash()

            clone.commitFile("local.txt", "local\n", "local")
            val localHead = clone.headHash()

            assertFailure(cloneBackend.pull())
            assertFailure(cloneBackend.push())

            assertEquals(localHead, clone.headHash())
            assertEquals(
                remoteHead,
                repo.gitIn(origin.path, "rev-parse", "refs/heads/main").requireSuccess().stdout.trim(),
            )
        }
    }
}
