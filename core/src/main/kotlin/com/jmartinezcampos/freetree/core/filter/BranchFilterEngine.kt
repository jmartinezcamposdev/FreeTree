package com.jmartinezcampos.freetree.core.filter

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.RawCommit
import com.jmartinezcampos.freetree.core.model.RefKind
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** Extension point for future scan strategies (unmerged work, cached indexes, ...). */
internal enum class ScanMode {
    /** Scan every candidate branch and every reachable commit. */
    FULL,
}

internal data class BranchFilterRequest(
    val filter: BranchFilter,
    val refs: List<GitRef>,
    val hiddenRefs: Set<String> = emptySet(),
    val showRemotes: Boolean = true,
    val importantBranches: Set<String> = emptySet(),
    val scanMode: ScanMode = ScanMode.FULL,
)

internal data class ScanPlan(
    val candidates: List<GitRef>,
    val seeds: List<GitRef>,
    val alwaysVisible: List<GitRef>,
    val scanRevs: List<String>,
    val excludeRevs: List<String> = emptyList(),
)

internal data class FilterStats(
    val scannedCommits: Int,
    val candidateBranches: Int,
    val includeBranches: Int,
    val excludeBranches: Int,
    val durationMillis: Long,
)

internal data class BranchFilterResult(
    val visibleRefs: List<GitRef>,
    val matchedCommits: Set<String>,
    val stats: FilterStats,
)

/**
 * Pure resolution pipeline. The scan decides which branches and commits participate; this engine
 * evaluates rules and maps matches to branches.
 */
internal interface BranchScan {
    fun plan(request: BranchFilterRequest): ScanPlan
}

internal class FullScan : BranchScan {
    override fun plan(request: BranchFilterRequest): ScanPlan {
        val candidates = candidateBranches(request)
        return ScanPlan(
            candidates = candidates,
            seeds = candidates,
            alwaysVisible = emptyList(),
            scanRevs = candidates.map { it.fullName },
        )
    }
}

internal class BranchFilterEngine(
    private val scan: BranchScan = FullScan(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun plan(request: BranchFilterRequest): ScanPlan = scan.plan(request)

    suspend fun resolve(
        request: BranchFilterRequest,
        commits: Flow<RawCommit>,
    ): BranchFilterResult {
        val started = nanoTime()
        val plan = scan.plan(request)
        val includeRules = CompiledRuleGroup.compile(
            request.filter.includes,
            request.filter.includeMode,
            zoneId,
        )
        val excludeRules = CompiledRuleGroup.compile(
            request.filter.excludes,
            request.filter.excludeMode,
            zoneId,
        )

        if (!request.filter.isActive) {
            return BranchFilterResult(
                visibleRefs = plan.candidates,
                matchedCommits = emptySet(),
                stats = stats(plan, 0, 0, 0, started),
            )
        }

        val tracker = NearestBranchTracker(plan.seeds)
        val includeBranches = LinkedHashSet<String>()
        val excludeBranches = LinkedHashSet<String>()
        val matchedCommits = LinkedHashSet<String>()
        var scanned = 0

        commits.collect { commit ->
            scanned++
            val nearest = tracker.nearest(commit.hash, commit.parents)
            if (!includeRules.isEmpty && includeRules.matches(commit)) {
                if (request.filter.commitMode) matchedCommits += commit.hash
                nearest?.let { includeBranches += it.fullName }
            }
            if (!excludeRules.isEmpty && excludeRules.matches(commit)) {
                nearest?.let { excludeBranches += it.fullName }
            }
        }

        val included = if (includeRules.isEmpty) {
            plan.candidates
        } else {
            plan.candidates.filter { it.fullName in includeBranches }
        }
        val visible = LinkedHashMap<String, GitRef>()
        plan.alwaysVisible.forEach { visible[it.fullName] = it }
        included.forEach { ref ->
            if (ref.fullName !in excludeBranches) visible[ref.fullName] = ref
        }

        return BranchFilterResult(
            visibleRefs = visible.values.toList(),
            matchedCommits = matchedCommits,
            stats = stats(
                plan = plan,
                scanned = scanned,
                includeCount = includeBranches.size,
                excludeCount = excludeBranches.size,
                started = started,
            ),
        )
    }

    private fun stats(
        plan: ScanPlan,
        scanned: Int,
        includeCount: Int,
        excludeCount: Int,
        started: Long,
    ): FilterStats = FilterStats(
        scannedCommits = scanned,
        candidateBranches = plan.candidates.size,
        includeBranches = includeCount,
        excludeBranches = excludeCount,
        durationMillis = (nanoTime() - started) / 1_000_000,
    )
}

private fun candidateBranches(request: BranchFilterRequest): List<GitRef> =
    request.refs.filter { ref ->
        ref.kind != RefKind.TAG &&
            ref.fullName !in request.hiddenRefs &&
            when (ref.kind) {
                RefKind.LOCAL_BRANCH -> true
                RefKind.REMOTE_BRANCH -> request.showRemotes
                RefKind.TAG -> false
            }
    }
