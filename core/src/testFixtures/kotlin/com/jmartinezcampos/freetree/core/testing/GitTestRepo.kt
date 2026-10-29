package com.jmartinezcampos.freetree.core.testing

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class GitCommandResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val succeeded: Boolean
        get() = exitCode == 0

    fun requireSuccess(): GitCommandResult {
        check(succeeded) { "git failed (${exitCode}): ${command.joinToString(" ")}\n$stderr" }
        return this
    }

    fun stdoutLines(): List<String> =
        stdout.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList()

    fun stderrLines(): List<String> =
        stderr.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList()
}

/**
 * Temporary real Git repository driven through [ProcessBuilder]. It never uses a shell and never
 * reaches the network; every commit is written by the git binary.
 */
class GitTestRepo private constructor(
    val root: Path,
    val workTree: Path,
    val defaultAuthorName: String = "FreeTree Test",
    val defaultAuthorEmail: String = "freetree@example.com",
) : AutoCloseable {

    data class BareOrigin(val name: String, val path: Path)

    private val globalConfig: Path = root.resolve(".gitconfig")
    private val hooksDir: Path = root.resolve("hooks")

    val gitDir: Path
        get() = workTree.resolve(".git")

    fun path(relative: String): Path {
        val resolved = workTree.resolve(relative).normalize()
        require(resolved.startsWith(workTree)) { "path escapes the work tree: $relative" }
        return resolved
    }

    fun writeFile(relative: String, content: String) {
        writeBytes(relative, content.toByteArray(UTF_8))
    }

    fun writeBytes(relative: String, content: ByteArray) {
        val target = path(relative)
        target.parent?.let { Files.createDirectories(it) }
        Files.write(target, content)
    }

    fun appendFile(relative: String, content: String) {
        val target = path(relative)
        target.parent?.let { Files.createDirectories(it) }
        Files.writeString(target, content, UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun createUntrackedFile(relative: String, content: String = "untracked\n") {
        writeFile(relative, content)
    }

    fun readFile(relative: String): String = Files.readString(path(relative), UTF_8)

    fun deleteFile(relative: String) {
        Files.deleteIfExists(path(relative))
    }

    fun renameFile(from: String, to: String) {
        val target = path(to)
        target.parent?.let { Files.createDirectories(it) }
        Files.move(path(from), target, StandardCopyOption.REPLACE_EXISTING)
    }

    fun exists(relative: String): Boolean = Files.exists(path(relative))

    fun git(vararg args: String): GitCommandResult = runGit(workTree, args.toList())

    fun gitOk(vararg args: String): String = git(*args).requireSuccess().stdout.trim()

    fun gitWithInput(input: String, vararg args: String): GitCommandResult =
        runGit(workTree, args.toList(), input.toByteArray(UTF_8))

    fun gitIn(directory: Path, vararg args: String): GitCommandResult = runGit(directory, args.toList())

    fun revParse(ref: String): String = gitOk("rev-parse", "--verify", "$ref^{commit}")

    fun headHash(): String = revParse("HEAD")

    fun hasHead(): Boolean = git("rev-parse", "--verify", "--quiet", "HEAD").succeeded

    fun currentBranch(): String? {
        val result = git("symbolic-ref", "--short", "--quiet", "HEAD")
        return if (result.succeeded) result.stdout.trim() else null
    }

    fun statusPorcelain(): String = git("status", "--porcelain=v1").requireSuccess().stdout

    fun stage(vararg paths: String): GitCommandResult {
        require(paths.isNotEmpty()) { "stage requires at least one path" }
        return git("--literal-pathspecs", "add", "--", *paths)
    }

    fun stageAll(): GitCommandResult = git("add", "-A")

    fun unstage(vararg paths: String): GitCommandResult {
        require(paths.isNotEmpty()) { "unstage requires at least one path" }
        return if (hasHead()) {
            git("--literal-pathspecs", "restore", "--staged", "--", *paths)
        } else {
            git("--literal-pathspecs", "rm", "--cached", "--", *paths)
        }
    }

    fun commit(
        message: String,
        authorName: String = defaultAuthorName,
        authorEmail: String = defaultAuthorEmail,
        date: String? = null,
        allowEmpty: Boolean = false,
    ): String {
        val args = mutableListOf("commit", "--quiet")
        if (allowEmpty) args += "--allow-empty"
        args += listOf("-m", message)
        val environment = mutableMapOf(
            "GIT_AUTHOR_NAME" to authorName,
            "GIT_AUTHOR_EMAIL" to authorEmail,
            "GIT_COMMITTER_NAME" to authorName,
            "GIT_COMMITTER_EMAIL" to authorEmail,
        )
        if (date != null) {
            environment["GIT_AUTHOR_DATE"] = date
            environment["GIT_COMMITTER_DATE"] = date
        }
        val result = runGit(workTree, args, environment = environment)
        result.requireSuccess()
        return headHash()
    }

    fun commitAll(
        message: String,
        authorName: String = defaultAuthorName,
        authorEmail: String = defaultAuthorEmail,
        date: String? = null,
        allowEmpty: Boolean = false,
    ): String {
        stageAll().requireSuccess()
        return commit(message, authorName, authorEmail, date, allowEmpty)
    }

    fun commitFile(
        relative: String,
        content: String,
        message: String,
        authorName: String = defaultAuthorName,
        authorEmail: String = defaultAuthorEmail,
        date: String? = null,
    ): String {
        writeFile(relative, content)
        stage(relative).requireSuccess()
        return commit(message, authorName, authorEmail, date)
    }

    fun createBranch(name: String, startPoint: String = "HEAD"): GitCommandResult =
        git("branch", name, startPoint)

    fun checkoutNewBranch(name: String, startPoint: String = "HEAD"): GitCommandResult =
        git("checkout", "--quiet", "-b", name, startPoint)

    fun checkout(ref: String): GitCommandResult = git("checkout", "--quiet", ref)

    fun merge(
        branch: String,
        message: String = "Merge branch '$branch'",
        noFastForward: Boolean = true,
        date: String? = null,
    ): GitCommandResult {
        val args = mutableListOf("merge")
        if (noFastForward) args += "--no-ff"
        args += listOf("-m", message, branch)
        val environment = if (date == null) {
            emptyMap()
        } else {
            mapOf("GIT_AUTHOR_DATE" to date, "GIT_COMMITTER_DATE" to date)
        }
        return runGit(workTree, args, environment = environment)
    }

    fun renameTracked(from: String, to: String): GitCommandResult =
        git("--literal-pathspecs", "mv", "--", from, to)

    fun deleteTracked(vararg paths: String): GitCommandResult {
        require(paths.isNotEmpty()) { "deleteTracked requires at least one path" }
        return git("--literal-pathspecs", "rm", "--", *paths)
    }

    fun addRemote(name: String, path: Path): GitCommandResult =
        git("remote", "add", name, path.toAbsolutePath().toString())

    fun createBareOrigin(
        name: String = "origin",
        initialBranch: String = currentBranch() ?: "main",
    ): BareOrigin {
        val barePath = root.resolve("$name.git")
        runGit(
            root,
            listOf("init", "--bare", "--initial-branch=$initialBranch", barePath.toAbsolutePath().toString()),
        ).requireSuccess()
        addRemote(name, barePath).requireSuccess()
        return BareOrigin(name, barePath)
    }

    fun push(
        remote: String = "origin",
        refspec: String = "HEAD",
        setUpstream: Boolean = true,
    ): GitCommandResult {
        val args = mutableListOf("push")
        if (setUpstream) args += "--set-upstream"
        args += listOf(remote, refspec)
        return git(*args.toTypedArray())
    }

    fun pull(
        remote: String = "origin",
        branch: String? = null,
        ffOnly: Boolean = true,
    ): GitCommandResult {
        val target = branch ?: currentBranch()
            ?: error("cannot pull from a detached HEAD without an explicit branch")
        val args = mutableListOf("pull")
        if (ffOnly) args += "--ff-only"
        args += listOf(remote, target)
        return git(*args.toTypedArray())
    }

    fun fetch(remote: String = "origin"): GitCommandResult = git("fetch", remote)

    override fun close() {
        deleteRecursively(root)
    }

    private fun configure() {
        Files.createDirectories(hooksDir)
        Files.writeString(globalConfig, "")
        git("config", "user.name", defaultAuthorName).requireSuccess()
        git("config", "user.email", defaultAuthorEmail).requireSuccess()
        git("config", "commit.gpgsign", "false").requireSuccess()
        git("config", "tag.gpgsign", "false").requireSuccess()
        git("config", "core.autocrlf", "false").requireSuccess()
        git("config", "core.quotepath", "false").requireSuccess()
        git("config", "core.hooksPath", hooksDir.toAbsolutePath().toString()).requireSuccess()
        git("config", "gc.auto", "0").requireSuccess()
        git("config", "maintenance.auto", "false").requireSuccess()
    }

    private fun runGit(
        directory: Path,
        args: List<String>,
        input: ByteArray? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 60,
    ): GitCommandResult {
        val command = listOf("git", "-C", directory.toAbsolutePath().toString()) + args
        val builder = ProcessBuilder(command)
        builder.directory(directory.toFile())
        val processEnvironment = builder.environment()
        processEnvironment["GIT_CONFIG_NOSYSTEM"] = "1"
        processEnvironment["GIT_CONFIG_GLOBAL"] = globalConfig.toAbsolutePath().toString()
        processEnvironment["HOME"] = root.toAbsolutePath().toString()
        processEnvironment["XDG_CONFIG_HOME"] = root.resolve(".config").toAbsolutePath().toString()
        processEnvironment["GIT_ATTR_NOSYSTEM"] = "1"
        processEnvironment["GIT_TERMINAL_PROMPT"] = "0"
        processEnvironment["GIT_EDITOR"] = "true"
        processEnvironment["GIT_MERGE_AUTOEDIT"] = "no"
        processEnvironment["GIT_PAGER"] = "cat"
        processEnvironment["PAGER"] = "cat"
        processEnvironment.putAll(environment)

        val process = try {
            builder.start()
        } catch (error: IOException) {
            throw IllegalStateException("could not start git; is it installed?", error)
        }

        val stdout = drain(process.inputStream)
        val stderr = drain(process.errorStream)
        process.outputStream.use { stream ->
            if (input != null) stream.write(input)
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }

        val stdoutBytes = readDrained(stdout, finished, timeoutSeconds, command)
        val stderrBytes = readDrained(stderr, finished, timeoutSeconds, command)
        return GitCommandResult(
            command = command,
            exitCode = if (finished) process.exitValue() else -1,
            stdout = String(stdoutBytes, UTF_8),
            stderr = String(stderrBytes, UTF_8),
        )
    }

    private fun readDrained(
        future: CompletableFuture<ByteArray>,
        finished: Boolean,
        timeoutSeconds: Long,
        command: List<String>,
    ): ByteArray = try {
        future.get(10, TimeUnit.SECONDS)
    } catch (error: Exception) {
        if (!finished) {
            throw IllegalStateException(
                "git timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}",
                error,
            )
        }
        throw IllegalStateException("failed to read git output: ${command.joinToString(" ")}", error)
    }

    private fun drain(stream: InputStream): CompletableFuture<ByteArray> {
        val future = CompletableFuture<ByteArray>()
        val thread = Thread {
            try {
                future.complete(stream.readAllBytes())
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        thread.isDaemon = true
        thread.name = "freetree-git-test-drain"
        thread.start()
        return future
    }

    companion object {
        fun create(parent: Path? = null): GitTestRepo {
            val repo = skeleton("repo", parent)
            repo.runGit(
                repo.workTree,
                listOf("init", "--initial-branch=main", "."),
            ).requireSuccess()
            repo.configure()
            return repo
        }

        fun clone(origin: Path, parent: Path? = null): GitTestRepo {
            val repo = skeleton("clone", parent)
            repo.runGit(
                repo.root,
                listOf(
                    "clone",
                    "--no-local",
                    origin.toAbsolutePath().toString(),
                    repo.workTree.toAbsolutePath().toString(),
                ),
            ).requireSuccess()
            repo.configure()
            return repo
        }

        fun clone(origin: BareOrigin, parent: Path? = null): GitTestRepo = clone(origin.path, parent)

        private fun skeleton(name: String, parent: Path?): GitTestRepo {
            val root = if (parent == null) {
                Files.createTempDirectory("freetree-git-")
            } else {
                Files.createTempDirectory(parent, "freetree-git-")
            }
            val workTree = root.resolve(name)
            Files.createDirectories(workTree)
            Files.writeString(root.resolve(".gitconfig"), "")
            val repo = GitTestRepo(root, workTree)
            root.toFile().deleteOnExit()
            workTree.toFile().deleteOnExit()
            return repo
        }

        private fun deleteRecursively(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
