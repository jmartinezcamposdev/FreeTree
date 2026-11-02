package com.jmartinezcampos.freetree.core.testing

import com.jmartinezcampos.freetree.core.model.RawCommit
import kotlin.random.Random

/**
 * Seeded generator of topologically ordered commits: every parent is either a later row (older
 * commit) or a deliberately missing hash.
 */
class RandomDag(
    val seed: Long,
    val commitCount: Int = 120,
    val rootCount: Int = 2,
    val mergeProbability: Double = 0.25,
    val octopusProbability: Double = 0.05,
    val missingParentProbability: Double = 0.03,
    val maxParentDistance: Int = 24,
    val authorCount: Int = 6,
) {
    init {
        require(commitCount >= 1) { "commitCount must be positive" }
        require(rootCount in 1..commitCount) { "rootCount must be in 1..commitCount" }
        require(mergeProbability in 0.0..1.0) { "mergeProbability must be in 0.0..1.0" }
        require(octopusProbability in 0.0..1.0) { "octopusProbability must be in 0.0..1.0" }
        require(missingParentProbability in 0.0..1.0) { "missingParentProbability must be in 0.0..1.0" }
        require(maxParentDistance >= 1) { "maxParentDistance must be positive" }
        require(authorCount >= 1) { "authorCount must be positive" }
    }

    private val authors: List<String> = List(authorCount) { index ->
        defaultAuthors.getOrElse(index) { "Author ${index + 1}" }
    }

    private val generatedHashes: List<String> = List(commitCount) { generatedHash(it) }

    init {
        require(generatedHashes.toSet().size == commitCount) { "generated hashes collided" }
    }

    fun generate(): List<RawCommit> {
        val random = Random(seed)
        val firstRoot = commitCount - rootCount
        val forceMerge = mergeProbability > 0.0 && commitCount >= 3 && firstRoot > 0
        val forceMissing = missingParentProbability > 0.0 && firstRoot > 0
        var missingSequence = 0

        return (0 until commitCount).map { index ->
            val author = authors[random.nextInt(authors.size)]
            val timeSec = BASE_TIME_SEC - index * 60L

            if (index >= firstRoot) {
                return@map commit(
                    index = index,
                    parents = emptyList(),
                    author = author,
                    timeSec = timeSec,
                    subject = "root $index",
                )
            }

            var parentCount = 1
            if (random.nextDouble() < octopusProbability) {
                parentCount = 3
            } else if (random.nextDouble() < mergeProbability) {
                parentCount = 2
            }
            if (forceMerge && index == 0) parentCount = maxOf(parentCount, 2)

            val parents = pickParents(index, parentCount, random)
            val addMissing = (forceMissing && index == 0) || random.nextDouble() < missingParentProbability
            val withMissing = if (addMissing) parents + missingHash(missingSequence++) else parents

            commit(
                index = index,
                parents = withMissing,
                author = author,
                timeSec = timeSec,
                subject = when {
                    parents.size > 1 -> "merge $index"
                    else -> "commit $index"
                },
            )
        }
    }

    private fun pickParents(index: Int, requested: Int, random: Random): List<String> {
        val olderCount = commitCount - index - 1
        val parentCount = requested.coerceAtMost(olderCount)
        if (parentCount == 0) return emptyList()

        val nearEnd = minOf(commitCount - 1, index + maxParentDistance)
        val nearSpan = nearEnd - index
        val first = index + 1 + random.nextInt(nearSpan)
        val chosen = mutableListOf(first)

        while (chosen.size < parentCount) {
            val candidate = index + 1 + random.nextInt(olderCount)
            if (candidate != first && candidate !in chosen) chosen += candidate
        }
        return chosen.map { generatedHashes[it] }
    }

    private fun commit(
        index: Int,
        parents: List<String>,
        author: String,
        timeSec: Long,
        subject: String,
    ) = RawCommit(
        hash = generatedHashes[index],
        parents = parents,
        authorName = author,
        authorTimeSec = timeSec,
        subject = subject,
        authorEmail = author.lowercase().replace(' ', '.') + "@example.com",
    )

    private fun generatedHash(index: Int): String =
        (
            hex(mix(seed + index * GOLDEN_GAMMA)) +
                hex(mix(seed xor (index.toLong() shl 32) xor MIX_A)) +
                hex(mix(seed * 31 + index))
            ).take(40)

    private fun missingHash(sequence: Int): String {
        var salt = sequence.toLong()
        while (true) {
            val candidate = "deadbeef" + hex(mix(seed xor (salt * MIX_B))) + hex(mix(seed + salt))
            if (candidate !in generatedHashes) return candidate
            salt++
        }
    }

    private fun mix(value: Long): Long {
        var z = value + GOLDEN_GAMMA
        z = (z xor (z ushr 30)) * MIX_A
        z = (z xor (z ushr 27)) * MIX_B
        return z xor (z ushr 31)
    }

    private fun hex(value: Long): String = java.lang.Long.toUnsignedString(value, 16).padStart(16, '0')

    companion object {
        private const val BASE_TIME_SEC = 1_700_000_000L
        private val GOLDEN_GAMMA = 0x9E3779B97F4A7C15UL.toLong()
        private val MIX_A = 0xBF58476D1CE4E5B9UL.toLong()
        private val MIX_B = 0x94D049BB133111EBUL.toLong()

        private val defaultAuthors = listOf("Ada", "Grace", "Linus", "Ken", "Barbara", "Alan")

        fun generate(seed: Long, commitCount: Int = 120, rootCount: Int = 2): List<RawCommit> =
            RandomDag(seed = seed, commitCount = commitCount, rootCount = rootCount).generate()
    }
}
