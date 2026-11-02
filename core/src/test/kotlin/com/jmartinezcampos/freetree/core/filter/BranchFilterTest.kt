package com.jmartinezcampos.freetree.core.filter

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BranchFilterTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun authorRulesMatchNameOrEmail() = runTest {
        val engine = engine()
        val commits = diffs()
        val refs = listOf(main(), topic())

        val byName = engine.resolve(
            request(refs, includes = listOf(rule(field = FilterField.AUTHOR, text = "grace"))),
            flowOf(*commits),
        )
        assertEquals(listOf("topic"), byName.visibleRefs.map { it.shortName })

        val byEmail = engine.resolve(
            request(refs, includes = listOf(rule(field = FilterField.AUTHOR, text = "ada@example"))),
            flowOf(*commits),
        )
        assertEquals(listOf("main", "topic"), byEmail.visibleRefs.map { it.shortName })
    }

    @Test
    fun messageRulesUseTheSubjectOnly() = runTest {
        val result = engine().resolve(
            request(listOf(main(), topic()), includes = listOf(rule(text = "TOPIC WORK"))),
            flowOf(*diffs()),
        )
        assertEquals(listOf("topic"), result.visibleRefs.map { it.shortName })
    }

    @Test
    fun nearestBranchPrefersTheShortestDescendantPath() = runTest {
        val result = engine().resolve(
            request(listOf(main(), topic()), includes = listOf(rule(text = "topic work"))),
            flowOf(*diffs()),
        )
        assertEquals(listOf("topic"), result.visibleRefs.map { it.shortName })
    }

    @Test
    fun nearestBranchTiesPreferCurrentThenLocalThenName() = runTest {
        val tied = diffs()
        val shared = request(
            listOf(
                branch("main", "h1", current = true),
                branch("release", "h1"),
            ),
            includes = listOf(rule(text = "main work")),
        )
        val current = engine().resolve(shared, flowOf(*tied))
        assertEquals(listOf("main"), current.visibleRefs.map { it.shortName })

        val localWin = engine().resolve(
            request(
                listOf(
                    branch("main", "h1", current = true),
                    branch("topic", "h4"),
                    branch("origin/topic", "h4", kind = RefKind.REMOTE_BRANCH),
                ),
                includes = listOf(rule(text = "topic work")),
            ),
            flowOf(*tied),
        )
        assertEquals(listOf("topic"), localWin.visibleRefs.map { it.shortName })

        val lexicographic = engine().resolve(
            request(
                listOf(
                    branch("main", "h1", current = true),
                    branch("topic", "h4"),
                    branch("other", "h4"),
                ),
                includes = listOf(rule(text = "topic work")),
            ),
            flowOf(*tied),
        )
        assertEquals(listOf("other"), lexicographic.visibleRefs.map { it.shortName })
    }

    @Test
    fun sharedCommitsUseTheTieBreak() = runTest {
        val result = engine().resolve(
            request(listOf(main(), topic()), includes = listOf(rule(text = "shared base"))),
            flowOf(*diffs()),
        )
        assertEquals(listOf("main"), result.visibleRefs.map { it.shortName })
    }

    @Test
    fun excludesWinAndEmptyIncludesShowEverythingElse() = runTest {
        val result = engine().resolve(
            request(listOf(main(), topic()), excludes = listOf(rule(text = "topic work"))),
            flowOf(*diffs()),
        )
        assertEquals(listOf("main"), result.visibleRefs.map { it.shortName })

        val both = engine().resolve(
            request(
                listOf(main(), topic()),
                includes = listOf(rule(text = "topic work"), rule(text = "main tip")),
                excludes = listOf(rule(text = "topic work")),
            ),
            flowOf(*diffs()),
        )
        assertEquals(listOf("main"), both.visibleRefs.map { it.shortName })
    }

    @Test
    fun groupModesCombineRulesWithAnyOrAll() = runTest {
        val any = engine().resolve(
            request(
                listOf(main(), topic()),
                includes = listOf(rule(text = "main work"), rule(text = "topic work")),
                includeMode = RuleMode.ANY,
            ),
            flowOf(*diffs()),
        )
        assertEquals(listOf("main", "topic"), any.visibleRefs.map { it.shortName })

        val all = engine().resolve(
            request(
                listOf(main(), topic()),
                includes = listOf(
                    rule(field = FilterField.AUTHOR, text = "ada"),
                    rule(text = "main work"),
                ),
                includeMode = RuleMode.ALL,
            ),
            flowOf(*diffs()),
        )
        assertTrue(all.visibleRefs.isEmpty())
    }

    @Test
    fun datesAreStrictDayBoundaries() = runTest {
        val commits = diffs()
        val refs = listOf(main(), topic())

        suspend fun matched(after: String? = null, before: String? = null): Set<String> =
            engine().resolve(
                request(
                    refs,
                    includes = listOf(dateRule(after = after, before = before)),
                    commitMode = true,
                ),
                flowOf(*commits),
            ).matchedCommits

        // h5 is 2024-01-05T12:00Z; every other commit is 2023-11-14.
        assertEquals(setOf("h5"), matched(after = "2024-01-04"))
        assertEquals(emptySet(), matched(after = "2024-01-05"))
        assertEquals(setOf("h1", "h2", "h3", "h4", "h6"), matched(before = "2024-01-05"))
        assertEquals(setOf("h1", "h2", "h3", "h4", "h5", "h6"), matched(before = "2024-01-06"))
        assertEquals(setOf("h5"), matched(after = "2024-01-04", before = "2024-01-06"))
    }

    @Test
    fun remotesCanBeExcludedFromCandidates() = runTest {
        val remote = branch("origin/topic", "h4", kind = RefKind.REMOTE_BRANCH)
        val withoutRemotes = engine().resolve(
            request(listOf(main(), remote), showRemotes = false, includes = listOf(rule(text = "topic work"))),
            flowOf(*diffs()),
        )
        assertTrue(withoutRemotes.visibleRefs.isEmpty())

        val withRemotes = engine().resolve(
            request(listOf(main(), remote), showRemotes = true, includes = listOf(rule(text = "topic work"))),
            flowOf(*diffs()),
        )
        assertEquals(listOf("origin/topic"), withRemotes.visibleRefs.map { it.shortName })
    }

    @Test
    fun hiddenRefsAreNotCandidates() = runTest {
        val result = engine().resolve(
            request(
                listOf(main(), topic()),
                hiddenRefs = setOf("refs/heads/topic"),
                includes = listOf(rule(text = "topic work")),
            ),
            flowOf(*diffs()),
        )
        assertTrue(result.visibleRefs.isEmpty())
    }

    @Test
    fun emptyFilterReturnsAllCandidatesAndStats() = runTest {
        val result = engine().resolve(
            request(listOf(main(), topic()), includes = emptyList()),
            flowOf(*diffs()),
        )
        assertEquals(listOf("main", "topic"), result.visibleRefs.map { it.shortName })
        assertEquals(0, result.stats.scannedCommits)
        assertEquals(2, result.stats.candidateBranches)
        assertEquals(0, result.stats.includeBranches)
        assertFalse(result.stats.durationMillis < 0)
    }

    @Test
    fun commitModeCollectsMatchingHashes() = runTest {
        val result = engine().resolve(
            request(
                listOf(main(), topic()),
                includes = listOf(rule(text = "topic work")),
                commitMode = true,
            ),
            flowOf(*diffs()),
        )
        assertEquals(setOf("h5"), result.matchedCommits)
        assertEquals(6, result.stats.scannedCommits)
    }

    @Test
    fun validationRejectsBlankAndBadDates() {
        assertEquals(
            "Enter a value.",
            FilterRule(field = FilterField.AUTHOR, text = "  ").validationError(),
        )
        assertEquals(
            "Use yyyy-MM-dd for After.",
            FilterRule(field = FilterField.DATE, after = "2024-13-01").validationError(),
        )
        assertEquals(
            "Set After, Before, or both.",
            FilterRule(field = FilterField.DATE).validationError(),
        )
        assertEquals(
            "After must be before Before.",
            FilterRule(field = FilterField.DATE, after = "2024-01-05", before = "2024-01-05")
                .validationError(),
        )
        assertEquals(
            "After must be before Before.",
            FilterRule(field = FilterField.DATE, after = "2024-01-06", before = "2024-01-05")
                .validationError(),
        )
        assertNotNull(
            validateBranchFilter(
                BranchFilter(includes = listOf(FilterRule(field = FilterField.DATE))),
            ).single().message,
        )
        assertEquals(
            emptyList(),
            validateBranchFilter(
                BranchFilter(
                    includes = listOf(
                        FilterRule(field = FilterField.AUTHOR, text = "Ada"),
                        FilterRule(field = FilterField.DATE, after = "2024-01-01"),
                    ),
                ),
            ),
        )
    }

    private fun engine(): BranchFilterEngine = BranchFilterEngine(
        scan = FullScan(),
        zoneId = utc,
        nanoTime = { 1_000_000L },
    )

    private fun request(
        refs: List<GitRef>,
        includes: List<FilterRule> = emptyList(),
        excludes: List<FilterRule> = emptyList(),
        includeMode: RuleMode = RuleMode.ANY,
        excludeMode: RuleMode = RuleMode.ANY,
        commitMode: Boolean = false,
        showRemotes: Boolean = true,
        hiddenRefs: Set<String> = emptySet(),
    ) = BranchFilterRequest(
        filter = BranchFilter(
            includes = includes,
            excludes = excludes,
            includeMode = includeMode,
            excludeMode = excludeMode,
            commitMode = commitMode,
        ),
        refs = refs,
        hiddenRefs = hiddenRefs,
        showRemotes = showRemotes,
    )

    private fun rule(
        field: FilterField = FilterField.MESSAGE,
        text: String,
    ) = FilterRule(field = field, text = text)

    private fun dateRule(after: String? = null, before: String? = null) =
        FilterRule(field = FilterField.DATE, after = after, before = before)

    private fun branch(
        name: String,
        commit: String,
        current: Boolean = false,
        kind: RefKind = RefKind.LOCAL_BRANCH,
    ) = GitRef(
        fullName = if (kind == RefKind.LOCAL_BRANCH) "refs/heads/$name" else "refs/remotes/$name",
        shortName = name,
        kind = kind,
        commit = commit,
        isCurrentBranch = current,
        upstream = null,
    )

    private fun main() = branch("main", "h1", current = true)

    private fun topic() = branch("topic", "h4")

    /**
     * h1 -> h2 -> h3 -> h6
     *       h4 -> h5 -^
     * Topological order: h1, h2, h4, h5, h3, h6.
     */
    private fun diffs(): Array<RawCommit> = arrayOf(
        RawCommit("h1", listOf("h2"), "Ada Lovelace", 1_700_003_500L, "main tip", "ada@example.com"),
        RawCommit("h2", listOf("h3"), "Linus Torvalds", 1_700_002_500L, "main work", "linus@example.com"),
        RawCommit("h4", listOf("h5"), "Ada Lovelace", 1_700_003_000L, "start topic", "ada@example.com"),
        // 2024-01-05T12:00:00Z
        RawCommit("h5", listOf("h3"), "Grace Hopper", 1_704_456_000L, "topic work", "grace@example.com"),
        RawCommit("h3", listOf("h6"), "Ada Lovelace", 1_700_001_500L, "shared base", "ada@example.com"),
        RawCommit("h6", emptyList(), "Linus Torvalds", 1_700_001_000L, "root", "linus@example.com"),
    )
}
