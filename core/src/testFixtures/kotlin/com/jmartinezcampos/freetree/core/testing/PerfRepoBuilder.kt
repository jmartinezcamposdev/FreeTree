package com.jmartinezcampos.freetree.core.testing

import com.jmartinezcampos.freetree.core.git.GitDiscovery
import com.jmartinezcampos.freetree.core.git.GitExecutable
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class PerfRepo(
    val path: Path,
    val commitCount: Int,
    val branchCount: Int,
    val tagCount: Int,
    val head: String,
    val fromCache: Boolean,
    val buildMillis: Long,
)

/**
 * Deterministic 200k-commit Git repository for manual benchmarks. The cache lives beside
 * `build/perf-repo/`; callers can use a smaller [commitCount] and temporary [root] for smoke tests.
 */
class PerfRepoBuilder(
    val root: Path = defaultRoot(),
    val seed: Long = 20_261_029L,
    val commitCount: Int = 200_000,
    val authorCount: Int = 15,
    val branchCount: Int = 300,
    val tagCount: Int = 100,
    val mergeEvery: Int = 10,
) {
    init {
        require(commitCount >= 1) { "commitCount must be positive" }
        require(authorCount >= 1) { "authorCount must be positive" }
        require(branchCount >= 0) { "branchCount must not be negative" }
        require(tagCount >= 0) { "tagCount must not be negative" }
        require(mergeEvery >= 1) { "mergeEvery must be positive" }
    }

    private val marker: Path = root.resolveSibling("${root.fileName}.perf.properties")

    fun isCacheValid(): Boolean {
        if (!Files.isDirectory(root.resolve(".git")) || !Files.isRegularFile(marker)) return false
        val properties = readMetadata() ?: return false
        return properties.matches()
    }

    fun buildIfNeeded(): PerfRepo {
        if (isCacheValid()) {
            val properties = readMetadata() ?: error("cache metadata disappeared: $marker")
            return PerfRepo(
                path = root.toAbsolutePath().normalize(),
                commitCount = commitCount,
                branchCount = branchCount,
                tagCount = tagCount,
                head = properties.getProperty("head") ?: error("cache metadata has no head: $marker"),
                fromCache = true,
                buildMillis = 0,
            )
        }
        return build()
    }

    fun build(): PerfRepo {
        val started = System.nanoTime()
        deleteRecursively(root)
        Files.deleteIfExists(marker)
        Files.createDirectories(root)
        val home = Files.createTempDirectory("freetree-perf-home-")
        try {
            Files.createDirectories(home.resolve("hooks"))
            Files.writeString(home.resolve(".gitconfig"), "")
            initialize(home)
            fastImport(home)
            deleteWorkRef(home)
            runGit(home, listOf("reset", "--hard", "--quiet", "HEAD")).requireSuccess()

            val head = runGit(home, listOf("rev-parse", "HEAD")).requireSuccess().stdout.trim()
            validate(head, home)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            writeMetadata(head, elapsed)
            return PerfRepo(
                path = root.toAbsolutePath().normalize(),
                commitCount = commitCount,
                branchCount = branchCount,
                tagCount = tagCount,
                head = head,
                fromCache = false,
                buildMillis = elapsed,
            )
        } finally {
            deleteRecursively(home)
        }
    }

    private fun initialize(home: Path) {
        runGit(home, listOf("init", "--quiet", "--initial-branch=main")).requireSuccess()
        runGit(home, listOf("config", "user.name", "FreeTree Perf")).requireSuccess()
        runGit(home, listOf("config", "user.email", "perf@example.com")).requireSuccess()
        runGit(home, listOf("config", "commit.gpgsign", "false")).requireSuccess()
        runGit(home, listOf("config", "tag.gpgsign", "false")).requireSuccess()
        runGit(home, listOf("config", "core.autocrlf", "false")).requireSuccess()
        runGit(home, listOf("config", "core.quotepath", "false")).requireSuccess()
        runGit(home, listOf("config", "core.hooksPath", home.resolve("hooks").toString())).requireSuccess()
        runGit(home, listOf("config", "gc.auto", "0")).requireSuccess()
        runGit(home, listOf("config", "maintenance.auto", "false")).requireSuccess()
    }

    private fun fastImport(home: Path) {
        val builder = ProcessBuilder(
            gitCommand() + listOf("fast-import", "--quiet", "--date-format=raw"),
        ).directory(root.toFile())
        applyEnvironment(builder, home)
        val process = try {
            builder.start()
        } catch (error: IOException) {
            throw IllegalStateException("could not start git fast-import", error)
        }
        val stdout = drain(process.inputStream)
        val stderr = drain(process.errorStream)

        BufferedWriter(OutputStreamWriter(process.outputStream, UTF_8), 1 shl 20).use { writer ->
            writeStream(writer)
        }

        val finished = process.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        val stdoutBytes = readDrained(stdout, finished, "fast-import")
        val stderrText = String(readDrained(stderr, finished, "fast-import"), UTF_8)
        if (!finished) throw IllegalStateException("git fast-import timed out\n$stderrText")
        if (process.exitValue() != 0) {
            throw IllegalStateException("git fast-import failed (${process.exitValue()})\n$stderrText")
        }
        check(String(stdoutBytes, UTF_8).isBlank()) { "unexpected fast-import stdout" }
    }

    private fun writeStream(writer: Writer) {
        val random = Random(seed)
        val authors = List(authorCount) { index -> "Perf Author ${index + 1}" }
        val startEpoch = 1_600_000_000L
        val spanSeconds = 5L * 365L * 24L * 60L * 60L
        val mainMarks = ArrayList<Int>()
        val branchMarks = ArrayList<Int>()
        var nextMark = 0
        var written = 0
        var sinceMerge = 0
        var mainMark: Int? = null

        while (written < commitCount) {
            val author = authors[random.nextInt(authors.size)]
            val time = startEpoch + (written.toLong() * spanSeconds / commitCount)
            val mainFile = "main-${written % 8}.txt"
            if (mainMark == null) {
                val mark = ++nextMark
                writeCommit(
                    writer = writer,
                    ref = "refs/heads/main",
                    mark = mark,
                    parents = emptyList(),
                    author = author,
                    time = time,
                    message = "root",
                    filePath = mainFile,
                    content = "main 0\n",
                )
                mainMark = mark
                mainMarks += mark
                written++
                continue
            }

            if (sinceMerge >= mergeEvery && written + 2 <= commitCount) {
                val sideMark = ++nextMark
                writeCommit(
                    writer = writer,
                    ref = "refs/heads/_work",
                    mark = sideMark,
                    parents = listOf(mainMark),
                    author = author,
                    time = time,
                    message = "side $written",
                    filePath = "side-$written.txt",
                    content = "side $written\n",
                )
                written++
                if (branchMarks.size < branchCount) branchMarks += sideMark

                val mergeMark = ++nextMark
                writeCommit(
                    writer = writer,
                    ref = "refs/heads/main",
                    mark = mergeMark,
                    parents = listOf(mainMark, sideMark),
                    author = author,
                    time = time,
                    message = "merge $written",
                    filePath = null,
                    content = null,
                )
                mainMark = mergeMark
                mainMarks += mergeMark
                written++
                sinceMerge = 0
            } else {
                val mark = ++nextMark
                writeCommit(
                    writer = writer,
                    ref = "refs/heads/main",
                    mark = mark,
                    parents = listOf(mainMark),
                    author = author,
                    time = time,
                    message = "commit $written",
                    filePath = mainFile,
                    content = "main $written\n",
                )
                mainMark = mark
                mainMarks += mark
                written++
                sinceMerge++
            }
        }

        while (branchMarks.size < branchCount) {
            branchMarks += mainMarks[branchMarks.size % mainMarks.size]
        }
        branchMarks.forEachIndexed { index, mark ->
            writer.write("reset refs/heads/branch-$index\nfrom :$mark\n\n")
        }

        if (tagCount > 0) {
            repeat(tagCount) { index ->
                val mark = mainMarks[(index * mainMarks.size) / tagCount]
                writer.write("reset refs/tags/v${index + 1}\nfrom :$mark\n\n")
            }
        }
    }

    private fun writeCommit(
        writer: Writer,
        ref: String,
        mark: Int,
        parents: List<Int>,
        author: String,
        time: Long,
        message: String,
        filePath: String?,
        content: String?,
    ) {
        writer.write("commit $ref\n")
        writer.write("mark :$mark\n")
        writer.write("author $author <perf@example.com> $time +0000\n")
        writer.write("committer $author <perf@example.com> $time +0000\n")
        writeData(writer, message)
        parents.firstOrNull()?.let { writer.write("from :$it\n") }
        parents.drop(1).forEach { writer.write("merge :$it\n") }
        if (filePath != null && content != null) {
            writer.write("M 100644 inline $filePath\n")
            writeData(writer, content)
        }
        writer.write("\n")
    }

    private fun writeData(writer: Writer, text: String) {
        val bytes = text.toByteArray(UTF_8)
        writer.write("data ${bytes.size}\n")
        writer.write(String(bytes, UTF_8))
        writer.write("\n")
    }

    private fun deleteWorkRef(home: Path) {
        val exists = runGit(home, listOf("show-ref", "--verify", "--quiet", "refs/heads/_work"))
        if (exists.exitCode == 0) {
            runGit(home, listOf("update-ref", "-d", "refs/heads/_work")).requireSuccess()
        }
    }

    private fun validate(head: String, home: Path) {
        val count = runGit(home, listOf("rev-list", "--count", head)).requireSuccess().stdout.trim().toInt()
        check(count == commitCount) { "expected $commitCount commits, found $count" }
        val heads = runGit(home, listOf("for-each-ref", "--format=%(refname)", "refs/heads"))
            .requireSuccess()
            .stdoutLines()
        check(heads.size == branchCount + 1) { "expected ${branchCount + 1} branches, found ${heads.size}" }
        val tags = runGit(home, listOf("for-each-ref", "--format=%(refname)", "refs/tags"))
            .requireSuccess()
            .stdoutLines()
        check(tags.size == tagCount) { "expected $tagCount tags, found ${tags.size}" }
    }

    private fun runGit(home: Path, args: List<String>): CommandResult {
        val builder = ProcessBuilder(gitCommand() + args).directory(root.toFile())
        applyEnvironment(builder, home)
        val process = try {
            builder.start()
        } catch (error: IOException) {
            throw IllegalStateException("could not start git", error)
        }
        process.outputStream.close()
        val stdout = drain(process.inputStream)
        val stderr = drain(process.errorStream)
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        val stdoutBytes = readDrained(stdout, finished, "git")
        val stderrText = String(readDrained(stderr, finished, "git"), UTF_8)
        if (!finished) throw IllegalStateException("git timed out: ${args.joinToString(" ")}\n$stderrText")
        return CommandResult(
            exitCode = process.exitValue(),
            stdout = String(stdoutBytes, UTF_8),
            stderr = stderrText,
        )
    }

    private fun gitCommand(): List<String> =
        listOf(git.path.toString(), "--no-pager", "-c", "core.quotepath=off")

    private val git: GitExecutable by lazy { GitDiscovery.discover() }

    private fun applyEnvironment(builder: ProcessBuilder, home: Path) {
        val environment = builder.environment()
        environment["GIT_CONFIG_NOSYSTEM"] = "1"
        environment["GIT_CONFIG_GLOBAL"] = home.resolve(".gitconfig").toString()
        environment["HOME"] = home.toString()
        environment["XDG_CONFIG_HOME"] = home.resolve(".config").toString()
        environment["GIT_ATTR_NOSYSTEM"] = "1"
        environment["GIT_TERMINAL_PROMPT"] = "0"
        environment["GIT_EDITOR"] = "true"
        environment["GIT_MERGE_AUTOEDIT"] = "no"
        environment["GIT_PAGER"] = "cat"
        environment["PAGER"] = "cat"
    }

    private fun readDrained(
        future: CompletableFuture<ByteArray>,
        finished: Boolean,
        label: String,
    ): ByteArray = try {
        future.get(30, TimeUnit.SECONDS)
    } catch (error: Exception) {
        if (!finished) throw IllegalStateException("$label timed out", error)
        throw IllegalStateException("could not read $label output", error)
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
        thread.name = "freetree-perf-git-drain"
        thread.start()
        return future
    }

    private fun writeMetadata(head: String, elapsedMillis: Long) {
        val properties = Properties()
        properties["version"] = CACHE_VERSION.toString()
        properties["seed"] = seed.toString()
        properties["commitCount"] = commitCount.toString()
        properties["authorCount"] = authorCount.toString()
        properties["branchCount"] = branchCount.toString()
        properties["tagCount"] = tagCount.toString()
        properties["mergeEvery"] = mergeEvery.toString()
        properties["head"] = head
        properties["buildMillis"] = elapsedMillis.toString()
        Files.newOutputStream(marker).use { properties.store(it, null) }
    }

    private fun readMetadata(): Properties? = try {
        if (!Files.isRegularFile(marker)) {
            null
        } else {
            Properties().apply { Files.newInputStream(marker).use { load(it) } }
        }
    } catch (error: IOException) {
        throw IllegalStateException("could not read cache metadata $marker", error)
    }

    private fun Properties.matches(): Boolean =
        getProperty("version") == CACHE_VERSION.toString() &&
            getProperty("seed") == seed.toString() &&
            getProperty("commitCount") == commitCount.toString() &&
            getProperty("authorCount") == authorCount.toString() &&
            getProperty("branchCount") == branchCount.toString() &&
            getProperty("tagCount") == tagCount.toString() &&
            getProperty("mergeEvery") == mergeEvery.toString() &&
            !getProperty("head").isNullOrBlank()

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        fun requireSuccess(): CommandResult {
            check(exitCode == 0) { "git failed (${exitCode}): $stderr" }
            return this
        }

        fun stdoutLines(): List<String> =
            stdout.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList()
    }

    companion object {
        private const val CACHE_VERSION = 1

        fun defaultRoot(): Path = Path.of("build", "perf-repo")

        private fun deleteRecursively(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
