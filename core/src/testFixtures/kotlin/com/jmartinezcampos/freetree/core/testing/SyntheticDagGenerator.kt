package com.jmartinezcampos.freetree.core.testing

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import kotlin.random.Random

data class SyntheticDag(
    val commits: List<RawCommit>,
    val refs: List<GitRef>,
    val authors: List<String>,
    val branchNames: List<String>,
    val tagNames: List<String>,
)

/**
 * Deterministic, in-memory DAG for graph and query benchmarks. Commits are newest-first; side
 * branches and merges are interleaved so the graph has real merge rows and multiple lanes.
 */
class SyntheticDagGenerator(
    val seed: Long = 20_261_029L,
    val commitCount: Int = 50_000,
    val authorCount: Int = 20,
    val branchCount: Int = 30,
    val tagCount: Int = 100,
    val mergeEvery: Int = 10,
    val branchLength: Int = 3,
) {
    init {
        require(commitCount >= 1) { "commitCount must be positive" }
        require(authorCount >= 1) { "authorCount must be positive" }
        require(branchCount in 1..commitCount) { "branchCount must be in 1..commitCount" }
        require(tagCount >= 0) { "tagCount must not be negative" }
        require(mergeEvery >= 1) { "mergeEvery must be positive" }
        require(branchLength >= 1) { "branchLength must be positive" }
    }

    private val authors: List<String> = List(authorCount) { index ->
        defaultAuthors.getOrElse(index) { "Author ${index + 1}" }
    }

    private val hashes: List<String> = List(commitCount) { generatedHash(it) }

    init {
        require(hashes.toSet().size == commitCount) { "generated hashes collided" }
    }

    fun generate(): SyntheticDag {
        val random = Random(seed)
        val commitsOldestFirst = ArrayList<RawCommit>(commitCount)
        val branchTips = ArrayList<Pair<String, Int>>()
        val branchNames = buildList {
            add("main")
            for (index in 1 until branchCount) add("feature/$index")
        }

        fun addCommit(parentIndices: List<Int>, subject: String): Int {
            val index = commitsOldestFirst.size
            val author = authors[random.nextInt(authors.size)]
            commitsOldestFirst += RawCommit(
                hash = hashes[index],
                parents = parentIndices.map { hashes[it] },
                authorName = author,
                authorTimeSec = BASE_TIME_SEC + index * 60L,
                subject = subject,
                authorEmail = author.lowercase().replace(' ', '.') + "@example.com",
            )
            return index
        }

        var mainTip = addCommit(emptyList(), "root")
        var produced = 1
        var sinceMerge = 0
        var nextBranch = 1

        while (produced < commitCount) {
            if (sinceMerge >= mergeEvery && produced + branchLength + 1 <= commitCount) {
                var sideTip = mainTip
                repeat(branchLength) { side ->
                    sideTip = addCommit(listOf(sideTip), "side $produced.$side")
                    produced++
                }
                if (nextBranch < branchNames.size) {
                    branchTips += branchNames[nextBranch] to sideTip
                    nextBranch++
                }
                mainTip = addCommit(listOf(mainTip, sideTip), "merge $produced")
                produced++
                sinceMerge = 0
            } else {
                mainTip = addCommit(listOf(mainTip), "commit $produced")
                produced++
                sinceMerge++
            }
        }

        while (branchTips.size < branchNames.size - 1) {
            val branchIndex = branchTips.size + 1
            val target = (branchIndex * 37) % commitsOldestFirst.size
            branchTips += branchNames[branchIndex] to target
        }

        val tagNames = List(tagCount) { "v${it + 1}.0" }
        val tagTargets = if (tagCount == 0) {
            emptyList()
        } else {
            List(tagCount) { index -> (index * commitsOldestFirst.size) / tagCount }
        }

        val refs = ArrayList<GitRef>(1 + branchTips.size + tagNames.size)
        refs += GitRef(
            fullName = "refs/heads/main",
            shortName = "main",
            kind = RefKind.LOCAL_BRANCH,
            commit = hashes[mainTip],
            isCurrentBranch = true,
            upstream = null,
        )
        branchTips.forEach { (name, target) ->
            refs += GitRef(
                fullName = "refs/heads/$name",
                shortName = name,
                kind = RefKind.LOCAL_BRANCH,
                commit = hashes[target],
                isCurrentBranch = false,
                upstream = null,
            )
        }
        tagNames.forEachIndexed { index, name ->
            refs += GitRef(
                fullName = "refs/tags/$name",
                shortName = name,
                kind = RefKind.TAG,
                commit = hashes[tagTargets[index]],
                isCurrentBranch = false,
                upstream = null,
            )
        }

        return SyntheticDag(
            commits = commitsOldestFirst.asReversed(),
            refs = refs,
            authors = authors,
            branchNames = branchNames,
            tagNames = tagNames,
        )
    }

    private fun generatedHash(index: Int): String =
        (
            hex(mix(seed + index * GOLDEN_GAMMA)) +
                hex(mix(seed xor (index.toLong() shl 32) xor MIX_A)) +
                hex(mix(seed * 31 + index))
            ).take(40)

    private fun mix(value: Long): Long {
        var z = value + GOLDEN_GAMMA
        z = (z xor (z ushr 30)) * MIX_A
        z = (z xor (z ushr 27)) * MIX_B
        return z xor (z ushr 31)
    }

    private fun hex(value: Long): String = java.lang.Long.toUnsignedString(value, 16).padStart(16, '0')

    private companion object {
        const val BASE_TIME_SEC = 1_700_000_000L
        val GOLDEN_GAMMA = 0x9E3779B97F4A7C15UL.toLong()
        val MIX_A = 0xBF58476D1CE4E5B9UL.toLong()
        val MIX_B = 0x94D049BB133111EBUL.toLong()
        val defaultAuthors = listOf(
            "Ada", "Grace", "Linus", "Ken", "Barbara", "Alan", "Margaret", "Donald",
            "Edsger", "Frances", "John", "Anita", "Niklaus", "Radia", "Leslie", "Shafi",
            "Karen", "Bjarne", "Sophie", "Guido",
        )
    }
}
