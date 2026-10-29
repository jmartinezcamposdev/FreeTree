package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.DiffLineKind
import com.jmartinezcampos.freetree.core.model.DiffTarget
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DiffSafetyAcceptanceTest {
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
    fun binaryDiffsAreLabelledWithoutTextPayloadInEveryTarget() = runBlocking {
        repo.writeFile("binary.bin", "v1\n")
        repo.commitAll("root")
        repo.writeBytes("binary.bin", byteArrayOf(0, 1, 2, 3, 10))
        val root = repo.headHash()

        repo.writeBytes("binary.bin", byteArrayOf(0, 9, 9, 9, 10))
        val unstaged = backend.diff(DiffTarget.Unstaged("binary.bin"))
        assertBinaryIsSafe(unstaged.binary, unstaged.lines.map { it.kind }, unstaged.lines.map { it.text })
        assertTrue(unstaged.lines.any { it.kind == DiffLineKind.NOTE && it.text.contains("Binary files") })

        assertOk(backend.stage(listOf("binary.bin")))
        val staged = backend.diff(DiffTarget.Staged("binary.bin"))
        assertBinaryIsSafe(staged.binary, staged.lines.map { it.kind }, staged.lines.map { it.text })

        assertOk(backend.commit("binary change"))
        val change = backend.changedFiles(repo.headHash()).single { it.path == "binary.bin" }
        val committed = backend.diff(DiffTarget.InCommit(repo.headHash(), change))
        assertBinaryIsSafe(committed.binary, committed.lines.map { it.kind }, committed.lines.map { it.text })

        repo.writeBytes("untracked.bin", byteArrayOf(0, 4, 5, 6))
        val untrackedAfterWrite = backend.diff(DiffTarget.Unstaged("untracked.bin"))
        assertBinaryIsSafe(
            untrackedAfterWrite.binary,
            untrackedAfterWrite.lines.map { it.kind },
            untrackedAfterWrite.lines.map { it.text },
        )
        assertEquals(listOf(DiffLineKind.NOTE), untrackedAfterWrite.lines.map { it.kind })
        assertEquals("Binary file", untrackedAfterWrite.lines.single().text)
        assertTrue(root != repo.headHash())
    }

    @Test
    fun hugeTrackedDiffIsCappedAndTheExplicitFullLoadCompletes() = runBlocking {
        repo.writeFile("big.txt", "small\n")
        repo.commitAll("root")
        val lines = 220_000
        repo.writeFile("big.txt", "0123456789\n".repeat(lines))

        val capped = backend.diff(DiffTarget.Unstaged("big.txt"))
        assertTrue(capped.truncated, "diff above 2 MB must be truncated")
        assertTrue(capped.lines.isNotEmpty(), "truncated diff still shows the beginning")
        assertFalse(capped.binary)

        val full = backend.diff(DiffTarget.Unstaged("big.txt"), unlimited = true)
        assertFalse(full.truncated)
        assertFalse(full.binary)
        assertEquals(lines, full.lines.count { it.kind == DiffLineKind.ADD })
        assertEquals(1, full.lines.single { it.kind == DiffLineKind.DEL }.oldNo)
        assertEquals(lines, full.lines.last().newNo)
    }

    private fun assertBinaryIsSafe(binary: Boolean, kinds: List<DiffLineKind>, texts: List<String>) {
        assertTrue(binary, "binary flag")
        assertTrue(kinds.none { it == DiffLineKind.ADD || it == DiffLineKind.DEL || it == DiffLineKind.CONTEXT })
        assertTrue(texts.any { it.contains("Binary file") })
    }
}
