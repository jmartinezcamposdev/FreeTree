package com.jmartinezcampos.freetree.core.git

import com.jmartinezcampos.freetree.core.model.LogSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeFalse

class GitRunnerTest {
    private lateinit var workDir: Path
    private val scriptDirectories = mutableListOf<Path>()

    @BeforeTest
    fun setUp() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        workDir = Files.createTempDirectory("freetree-git-runner-")
    }

    @AfterTest
    fun tearDown() {
        deleteRecursively(workDir)
        scriptDirectories.forEach(::deleteRecursively)
    }

    @Test
    fun passesArgumentsAsAListAndUsesTheDocumentedPrefix() = runBlocking {
        val script = script("printf '%s\\n' \"\$@\"")
        val result = runner(script).run(listOf("diff", "path with space"))

        assertTrue(result.succeeded)
        assertEquals(
            listOf("--no-pager", "-c", "core.quotepath=off", "diff", "path with space"),
            result.stdoutText().lineSequence().filter { it.isNotEmpty() }.toList(),
        )
    }

    @Test
    fun passesUnicodeAndNewlineArgumentsWithoutShellParsing() = runBlocking {
        val script = script("printf '%s\\n' \"\$@\"")
        val argument = "path ünïcode\nline\ttab"

        val result = runner(script).run(listOf("diff", argument))

        assertTrue(result.succeeded)
        assertEquals(
            "--no-pager\n-c\ncore.quotepath=off\ndiff\n$argument\n",
            result.stdoutText(),
        )
    }

    @Test
    fun setsPromptsAndLockEnvironmentPerCommandType() = runBlocking {
        val script = script(
            "printf '%s|%s|%s|%s' \"\$GIT_TERMINAL_PROMPT\" \"\$GCM_INTERACTIVE\" " +
                "\"\$GIT_LITERAL_PATHSPECS\" \"\${GIT_OPTIONAL_LOCKS-}\"",
        )
        val git = runner(script)

        assertEquals("0|never|1|0", git.run(listOf("status"), GitCommandType.READ).stdoutText())
        assertEquals("0|never|1|", git.run(listOf("add", "x"), GitCommandType.LOCAL_WRITE).stdoutText())
        assertEquals("0|never|1|", git.run(listOf("push"), GitCommandType.NETWORK).stdoutText())
    }

    @Test
    fun usesDocumentedDefaultTimeouts() {
        val timeouts = GitTimeouts()

        assertEquals(60_000, timeouts.readMillis)
        assertEquals(120_000, timeouts.localWriteMillis)
        assertEquals(300_000, timeouts.networkMillis)
    }

    @Test
    fun preservesGitStderrOnFailure() = runBlocking {
        val script = script("echo 'fatal: nope' >&2\nexit 7")

        val result = runner(script).run(listOf("status"))

        assertFalse(result.succeeded)
        assertEquals(7, result.exitCode)
        assertEquals("fatal: nope\n", result.stderr)
    }

    @Test
    fun writesLargeInputWhileDrainingLargeOutput() = runBlocking {
        val script = script("dd if=/dev/zero bs=1024 count=1024 2>/dev/null\ncat >/dev/null")
        val input = ByteArray(1024 * 1024)

        val result = runner(script, GitTimeouts(readMillis = 10_000)).run(listOf("log"), stdin = input)

        assertTrue(result.succeeded, result.stderr)
        assertEquals(input.size, result.stdout.size)
    }

    @Test
    fun closesStdinWhenNoInputIsSuppliedAndWritesInputWhenSupplied() = runBlocking {
        val script = script("cat")
        val git = runner(script)

        assertEquals("", git.run(listOf("log")).stdoutText())
        assertEquals("message ünïcode", git.run(listOf("commit"), stdin = "message ünïcode".toByteArray()).stdoutText())
    }

    @Test
    fun timeoutKillsTheProcessAndReportsAnError() = runBlocking {
        val script = script("echo started\nsleep 30")
        val git = runner(script, GitTimeouts(readMillis = 200))

        val started = System.nanoTime()
        val result = git.run(listOf("status"))

        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("timed out after 200 ms"), result.stderr)
        assertTrue((System.nanoTime() - started) < 5_000_000_000L)
    }

    @Test
    fun timeoutKillsTheProcessAndItsDescendants() = runBlocking {
        val script = script(
            """
            sleep 30 &
            child=${'$'}!
            echo "${'$'}child" > child.pid
            echo "${'$'}${'$'}" > parent.pid
            wait
            """.trimIndent(),
        )
        val deferred = async {
            runner(script, GitTimeouts(readMillis = 1_000)).run(listOf("status"))
        }
        assertTrue(waitForFile(workDir.resolve("child.pid")))
        assertTrue(waitForFile(workDir.resolve("parent.pid")))
        val childPid = Files.readString(workDir.resolve("child.pid")).trim().toLong()
        val parentPid = Files.readString(workDir.resolve("parent.pid")).trim().toLong()

        val result = deferred.await()

        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(waitUntilGone(childPid), "child process $childPid is still alive")
        assertTrue(waitUntilGone(parentPid), "parent process $parentPid is still alive")
    }

    @Test
    fun truncationKillsTheProcessAndItsDescendants() = runBlocking {
        val script = script(
            """
            sleep 30 &
            echo "${'$'}!" > child.pid
            echo "${'$'}${'$'}" > parent.pid
            while true; do printf '0123456789abcdef'; done
            """.trimIndent(),
        )
        val deferred = async {
            runner(script).run(listOf("diff"), maxStdoutBytes = 1_024)
        }
        assertTrue(waitForFile(workDir.resolve("child.pid")))
        assertTrue(waitForFile(workDir.resolve("parent.pid")))
        val childPid = Files.readString(workDir.resolve("child.pid")).trim().toLong()
        val parentPid = Files.readString(workDir.resolve("parent.pid")).trim().toLong()

        val result = deferred.await()

        assertTrue(result.truncated)
        assertEquals(1_024, result.stdout.size)
        assertTrue(waitUntilGone(childPid), "child process $childPid is still alive")
        assertTrue(waitUntilGone(parentPid), "parent process $parentPid is still alive")
    }

    @Test
    fun localWriteTimeoutUsesTheLocalTimeout() = runBlocking {
        val script = script("sleep 30")
        val git = runner(script, GitTimeouts(readMillis = 10_000, localWriteMillis = 200))

        val result = git.run(listOf("add", "x"), GitCommandType.LOCAL_WRITE)

        assertTrue(result.timedOut)
        assertTrue(result.stderr.contains("timed out after 200 ms"), result.stderr)
    }

    @Test
    fun networkTimeoutUsesTheNetworkTimeout() = runBlocking {
        val script = script("sleep 30")
        val git = runner(script, GitTimeouts(readMillis = 10_000, localWriteMillis = 10_000, networkMillis = 200))

        val result = git.run(listOf("push"), GitCommandType.NETWORK)

        assertTrue(result.timedOut)
        assertTrue(result.stderr.contains("timed out after 200 ms"), result.stderr)
    }

    @Test
    fun cancellationKillsTheProcessAndItsDescendants() = runBlocking {
        val script = script(
            """
            sleep 30 &
            child=${'$'}!
            echo "${'$'}child" > child.pid
            echo "${'$'}${'$'}" > parent.pid
            wait
            """.trimIndent(),
        )
        val git = runner(script)
        val deferred = async { git.run(listOf("status")) }
        assertTrue(waitForFile(workDir.resolve("child.pid")))
        val childPid = Files.readString(workDir.resolve("child.pid")).trim().toLong()
        val parentPid = Files.readString(workDir.resolve("parent.pid")).trim().toLong()

        deferred.cancel()
        assertFailsWith<CancellationException> { deferred.await() }

        assertTrue(waitUntilGone(childPid), "child process $childPid is still alive")
        assertTrue(waitUntilGone(parentPid), "parent process $parentPid is still alive")
    }

    @Test
    fun streamsNulDelimitedRecords() = runBlocking {
        val script = script("printf 'one\\000two\\000three'")
        val records = mutableListOf<String>()

        val result = runner(script).streamRecords(listOf("log")) { records += it }

        assertTrue(result.succeeded)
        assertEquals(listOf("one", "two", "three"), records)
    }

    @Test
    fun capsStdoutAndMarksTheResultTruncated() = runBlocking {
        val script = script("printf '0123456789abcdef'")
        val result = runner(script).run(listOf("diff"), maxStdoutBytes = 8)

        assertTrue(result.truncated)
        assertEquals("01234567", result.stdoutText())
    }

    @Test
    fun reportsAProcessThatCannotStart() = runBlocking {
        val result = GitRunner(workDir, GitExecutable(workDir.resolve("does-not-exist"), GitVersion(2, 30)))
            .run(listOf("status"))

        assertFalse(result.succeeded)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("Could not start Git"), result.stderr)
    }

    @Test
    fun logStreamIsCancellableAndKillsItsGitProcess() = runBlocking {
        val script = script(
            """
            case "${'$'}4" in
              rev-parse)
                case "${'$'}5" in
                  --show-toplevel) echo "${'$'}PWD" ;;
                  --absolute-git-dir) echo "${'$'}PWD/.git" ;;
                  --git-common-dir) echo ".git" ;;
                  *) echo "0123456789012345678901234567890123456789" ;;
                esac
                ;;
              symbolic-ref)
                echo main
                ;;
              log)
                echo "${'$'}${'$'}" > log.pid
                sleep 30
                ;;
            esac
            """.trimIndent(),
        )
        val backend = CliGitBackend.open(workDir, GitExecutable(script, GitVersion(2, 39)))

        val collection = async {
            backend.logStream(LogSpec(revs = listOf("HEAD"), includeHead = false)).collect { }
        }
        assertTrue(waitForFile(workDir.resolve("log.pid")))
        val pid = Files.readString(workDir.resolve("log.pid")).trim().toLong()

        collection.cancel()
        assertFailsWith<CancellationException> { collection.await() }

        assertTrue(waitUntilGone(pid), "git process $pid is still alive")
    }

    private fun runner(script: Path, timeouts: GitTimeouts = GitTimeouts()): GitRunner =
        GitRunner(
            workTree = workDir,
            executable = GitExecutable(script, GitVersion(2, 39)),
            timeouts = timeouts,
            baseEnvironment = mapOf("HOME" to workDir.toString()),
        )

    private fun script(body: String): Path {
        val directory = Files.createTempDirectory("freetree-fake-git-")
        scriptDirectories.add(directory)
        val file = directory.resolve("git")
        Files.writeString(file, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(
            file,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
        )
        return file
    }

    private suspend fun waitForFile(path: Path): Boolean =
        withTimeoutOrNull(3_000) {
            while (!Files.exists(path)) delay(20)
            true
        } ?: false

    private suspend fun waitUntilGone(pid: Long): Boolean =
        withTimeoutOrNull(5_000) {
            while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(50)
            true
        } ?: false

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
