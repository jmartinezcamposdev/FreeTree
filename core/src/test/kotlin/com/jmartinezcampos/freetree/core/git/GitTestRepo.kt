package com.jmartinezcampos.freetree.core.git

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

internal class GitTestRepo private constructor(
    val root: Path,
    val gitExecutable: GitExecutable,
) : AutoCloseable {
    private val home: Path = Files.createTempDirectory("freetree-git-home-")

    data class CommandResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: String,
    ) {
        val stdoutText: String get() = stdout.toString(StandardCharsets.UTF_8)
    }

    fun git(vararg args: String): String = gitWithInput(null, *args).stdoutText

    fun gitResult(vararg args: String): CommandResult = gitWithInput(null, *args)

    fun gitWithInput(input: ByteArray?, vararg args: String): CommandResult {
        val builder = ProcessBuilder(
            listOf(gitExecutable.path.toString(), "--no-pager", "-c", "core.quotepath=off") + args,
        )
            .directory(root.toFile())
            .redirectErrorStream(false)
        builder.environment().apply {
            put("GIT_TERMINAL_PROMPT", "0")
            put("GCM_INTERACTIVE", "never")
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("HOME", home.toString())
            put("XDG_CONFIG_HOME", home.resolve(".config").toString())
        }
        val process = builder.start()
        process.outputStream.use { output ->
            if (input != null) output.write(input)
        }
        val stdout = process.inputStream.readBytes()
        val stderr = process.errorStream.readBytes().toString(StandardCharsets.UTF_8)
        val exitCode = process.waitFor()
        return CommandResult(exitCode, stdout, stderr)
    }

    fun write(relative: String, content: String) {
        write(relative, content.toByteArray(StandardCharsets.UTF_8))
    }

    fun write(relative: String, content: ByteArray) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.write(file, content)
    }

    fun delete(relative: String) {
        Files.deleteIfExists(root.resolve(relative))
    }

    fun commitAll(message: String): String {
        check(gitResult("add", "-A").exitCode == 0) { "git add failed" }
        val commit = gitResult("commit", "-q", "-m", message)
        check(commit.exitCode == 0) { "git commit failed: ${commit.stderr}" }
        return revParse("HEAD")
    }

    fun revParse(ref: String): String = git("rev-parse", ref).trim()

    fun head(): String = revParse("HEAD")

    fun statusPorcelainV1(): List<String> =
        git("status", "--porcelain=v1", "-z")
            .split('\u0000')
            .filter { it.isNotEmpty() }

    override fun close() {
        deleteRecursively(root)
        deleteRecursively(home)
    }

    companion object {
        private val executable: GitExecutable by lazy { GitDiscovery.discover() }

        fun create(branch: String = "main", objectFormat: String? = null): GitTestRepo {
            val root = Files.createTempDirectory("freetree-git-repo-")
            val repo = GitTestRepo(root, executable)
            val args = mutableListOf("init", "-q", "-b", branch)
            if (objectFormat != null) {
                args += "--object-format"
                args += objectFormat
            }
            check(repo.gitResult(*args.toTypedArray()).exitCode == 0) { "git init failed" }
            repo.git("config", "user.email", "test@example.com")
            repo.git("config", "user.name", "Test User")
            repo.git("config", "commit.gpgsign", "false")
            repo.git("config", "tag.gpgsign", "false")
            return repo
        }

        fun createBare(branch: String = "main"): GitTestRepo {
            val root = Files.createTempDirectory("freetree-git-bare-")
            val repo = GitTestRepo(root, executable)
            check(repo.gitResult("init", "--bare", "-q", "-b", branch).exitCode == 0) {
                "git init --bare failed"
            }
            return repo
        }

        fun addOrigin(repo: GitTestRepo, bare: GitTestRepo, name: String = "origin") {
            repo.git("remote", "add", name, bare.root.toString())
        }

        private fun deleteRecursively(root: Path) {
            if (!Files.exists(root)) return
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
