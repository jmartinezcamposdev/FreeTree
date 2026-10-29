package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.GitResult
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    fun missingUpstreamFailsPushAndPullWithGitStderrAndNoHang() = runBlocking {
        repo.commitFile("a.txt", "a\n", "root")
        repo.createBareOrigin()

        val expectedPush = repo.git("push")
        val push = timed { backend.push() }
        assertFailure(push.value)
        assertEquals(expectedPush.stderr, (push.value as GitResult.Failure).stderr)
        assertTrue(push.elapsedMillis < NETWORK_TIMEOUT_MILLIS)

        val expectedPull = repo.git("pull", "--no-edit")
        val pull = timed { backend.pull() }
        assertFailure(pull.value)
        assertEquals(expectedPull.stderr, (pull.value as GitResult.Failure).stderr)
        assertTrue(pull.elapsedMillis < NETWORK_TIMEOUT_MILLIS)
    }

    @Test
    fun pushAndFastForwardPullAgainstLocalBareOrigin() = runBlocking {
        repo.commitFile("a.txt", "one\n", "root")
        val origin = repo.createBareOrigin()
        repo.git("push", "-u", "origin", "main").requireSuccess()

        repo.commitFile("a.txt", "two\n", "second")
        val push = timed { backend.push() }
        assertOk(push.value)
        assertTrue(push.elapsedMillis < NETWORK_TIMEOUT_MILLIS)
        assertEquals(
            repo.headHash(),
            repo.gitIn(origin.path, "rev-parse", "refs/heads/main").requireSuccess().stdout.trim(),
        )

        GitTestRepo.clone(origin).use { clone ->
            val cloneBackend = clone.openBackend()
            repo.commitFile("a.txt", "three\n", "third")
            assertOk(backend.push())

            val pull = timed { cloneBackend.pull() }
            assertOk(pull.value)
            assertTrue(pull.elapsedMillis < NETWORK_TIMEOUT_MILLIS)

            assertEquals(repo.headHash(), clone.headHash())
            assertEquals("three\n", clone.readFile("a.txt"))
            val status = cloneBackend.status()
            assertEquals(0, status.ahead)
            assertEquals(0, status.behind)
            assertEquals(repo.headHash(), clone.git("rev-parse", "@{upstream}").requireSuccess().stdout.trim())
        }
    }

    @Test
    fun divergentPullAndRejectedPushPreserveGitErrorsAndLeaveStateUntouched() = runBlocking {
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

            val pull = timed { cloneBackend.pull() }
            assertFailure(pull.value)
            val pullStderr = (pull.value as GitResult.Failure).stderr
            assertTrue(
                pullStderr.contains("divergent", ignoreCase = true) ||
                    pullStderr.contains("fast-forward", ignoreCase = true),
                pullStderr,
            )
            assertTrue(pullStderr.contains("hint:", ignoreCase = true), pullStderr)
            assertTrue(pull.elapsedMillis < NETWORK_TIMEOUT_MILLIS)

            val expectedPush = clone.git("push")
            val push = timed { cloneBackend.push() }
            assertFailure(push.value)
            assertEquals(expectedPush.stderr, (push.value as GitResult.Failure).stderr)
            assertTrue(push.elapsedMillis < NETWORK_TIMEOUT_MILLIS)

            assertEquals(localHead, clone.headHash())
            assertEquals(
                remoteHead,
                repo.gitIn(origin.path, "rev-parse", "refs/heads/main").requireSuccess().stdout.trim(),
            )
            val status = cloneBackend.status()
            assertEquals(1, status.ahead)
            assertEquals(1, status.behind)
        }
    }

    private data class TimedResult<T>(val value: T, val elapsedMillis: Long)

    private inline fun <T> timed(action: () -> T): TimedResult<T> {
        val started = System.nanoTime()
        val value = action()
        return TimedResult(value, (System.nanoTime() - started) / 1_000_000)
    }

    private companion object {
        const val NETWORK_TIMEOUT_MILLIS = 60_000L
    }
}
