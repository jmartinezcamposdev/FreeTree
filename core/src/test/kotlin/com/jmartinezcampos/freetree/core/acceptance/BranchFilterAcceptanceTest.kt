package com.jmartinezcampos.freetree.core.acceptance

import com.jmartinezcampos.freetree.core.filter.BranchFilter
import com.jmartinezcampos.freetree.core.filter.BranchFilterEngine
import com.jmartinezcampos.freetree.core.filter.BranchFilterRequest
import com.jmartinezcampos.freetree.core.filter.FilterField
import com.jmartinezcampos.freetree.core.filter.FilterRule
import com.jmartinezcampos.freetree.core.git.CliGitBackend
import com.jmartinezcampos.freetree.core.model.LogSpec
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.testing.GitTestRepo
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class BranchFilterAcceptanceTest {
    private lateinit var repo: GitTestRepo
    private lateinit var backend: CliGitBackend
    private lateinit var baseHash: String
    private lateinit var featureWorkHash: String
    private lateinit var featureTipHash: String
    private lateinit var mainHash: String

    @BeforeTest
    fun setUp() {
        repo = GitTestRepo.create()
        backend = runBlocking { repo.openBackend() }
        buildHistory()
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    @Test
    fun includeMessageShowsOnlyTheBranchWithTheMatchingCommit() = runBlocking {
        val result = resolve(
            BranchFilter(includes = listOf(FilterRule(field = FilterField.MESSAGE, text = "feature work"))),
        )

        assertEquals(listOf("feature"), result.visibleRefs.map { it.shortName })
        assertEquals(
            listOf(featureTipHash, featureWorkHash, baseHash),
            graphCommits(result).map { it.hash },
        )
        assertTrue(graphCommits(result).none { it.hash == mainHash })
    }

    @Test
    fun excludeMessageHidesTheNearestBranchAndKeepsTheRest() = runBlocking {
        val result = resolve(
            BranchFilter(excludes = listOf(FilterRule(field = FilterField.MESSAGE, text = "feature work"))),
        )

        assertEquals(listOf("main"), result.visibleRefs.map { it.shortName })
        val hashes = graphCommits(result).map { it.hash }
        assertEquals(listOf(mainHash, baseHash), hashes)
        assertTrue(featureTipHash !in hashes)
        assertTrue(featureWorkHash !in hashes)
    }

    @Test
    fun includeAuthorEmailUsesTheCommitEmail() = runBlocking {
        val result = resolve(
            BranchFilter(
                includes = listOf(FilterRule(field = FilterField.AUTHOR, text = "bob@example.com")),
            ),
        )

        assertEquals(listOf("feature"), result.visibleRefs.map { it.shortName })
    }

    @Test
    fun strictDateRulesMatchTheExpectedBranches() = runBlocking {
        val after = resolve(
            BranchFilter(
                includes = listOf(FilterRule(field = FilterField.DATE, after = "2024-02-01")),
            ),
        )
        // Feature tip (2024-02-02) and main work (2024-03-01) are both strictly after 2024-02-01.
        assertEquals(listOf("feature", "main"), after.visibleRefs.map { it.shortName })

        val beforeBase = resolve(
            BranchFilter(
                includes = listOf(FilterRule(field = FilterField.DATE, before = "2024-01-01")),
            ),
        )
        assertTrue(beforeBase.visibleRefs.isEmpty())

        val afterLatest = resolve(
            BranchFilter(
                includes = listOf(FilterRule(field = FilterField.DATE, after = "2024-03-01")),
            ),
        )
        assertTrue(afterLatest.visibleRefs.isEmpty())
    }

    @Test
    fun excludeRevsLimitsTheLogToUniqueCommits() = runBlocking {
        val feature = backend.refs().single { it.fullName == "refs/heads/feature" }
        val main = backend.refs().single { it.fullName == "refs/heads/main" }
        val spec = LogSpec(
            revs = listOf(feature.fullName),
            includeHead = false,
            excludeRevs = listOf(main.fullName),
        )

        assertEquals(
            listOf(featureTipHash, featureWorkHash),
            backend.logStream(spec).toList().map { it.hash },
        )
    }

    private suspend fun resolve(filter: BranchFilter): com.jmartinezcampos.freetree.core.filter.BranchFilterResult {
        val refs = backend.refs()
        val head = backend.head()
        val request = BranchFilterRequest(
            filter = filter,
            refs = refs,
            showRemotes = true,
        )
        val engine = BranchFilterEngine(zoneId = ZoneId.of("UTC"))
        val plan = engine.plan(request)
        val scan = LogSpec(revs = plan.scanRevs, includeHead = false)
        return engine.resolve(request, backend.logStream(scan))
    }

    private suspend fun graphCommits(
        result: com.jmartinezcampos.freetree.core.filter.BranchFilterResult,
    ): List<RawCommit> {
        val spec = LogSpec(
            revs = result.visibleRefs.map { it.fullName },
            includeHead = false,
        )
        return backend.logStream(spec).toList()
    }

    private fun buildHistory() {
        repo.writeFile("src/shared.txt", "shared\n")
        baseHash = repo.commitAll(
            message = "shared base",
            authorName = "Alice",
            authorEmail = "alice@example.com",
            date = "2024-01-01T00:00:00+0000",
        )

        repo.checkoutNewBranch("feature").requireSuccess()
        featureWorkHash = repo.commitFile(
            relative = "src/feature.txt",
            content = "feature\n",
            message = "feature work",
            authorName = "Bob",
            authorEmail = "bob@example.com",
            date = "2024-02-01T00:00:00+0000",
        )
        featureTipHash = repo.commitFile(
            relative = "src/feature.txt",
            content = "feature tip\n",
            message = "feature tip",
            authorName = "Bob",
            authorEmail = "bob@example.com",
            date = "2024-02-02T00:00:00+0000",
        )

        repo.checkout("main").requireSuccess()
        mainHash = repo.commitFile(
            relative = "src/main.txt",
            content = "main\n",
            message = "main work",
            authorName = "Alice",
            authorEmail = "alice@example.com",
            date = "2024-03-01T00:00:00+0000",
        )
    }
}
