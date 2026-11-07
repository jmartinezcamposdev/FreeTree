package com.jmartinezcampos.freetree.core.filter

import com.jmartinezcampos.freetree.core.model.GitRef
import com.jmartinezcampos.freetree.core.model.RefKind

/**
 * Single-pass nearest-branch mapping for a `--topo-order` commit stream (children before parents).
 *
 * Every candidate tip starts at distance 0. Each commit receives the minimum-distance candidate
 * propagated from its children and passes it to its parents at `distance + 1`. Ties are resolved by
 * [CANDIDATE_ORDER], so every commit maps to exactly one branch.
 *
 * Propagating only the winner is sufficient for ancestors: priorities form a total order, so the
 * best candidate of a child's tie set can never lose to a dropped candidate at an ancestor.
 */
internal class NearestBranchTracker(candidates: List<GitRef>) {

    private data class Candidate(val ref: GitRef, val distance: Int)

    private val tipsByHash: Map<String, List<GitRef>> = candidates.groupBy { it.commit }
    private val pending = HashMap<String, Candidate>()

    /**
     * Returns the nearest branch for [hash], if any. The commit's parents are updated so their
     * nearest branch can be resolved when they are visited.
     */
    fun nearest(hash: String, parents: List<String>): GitRef? {
        val inherited = pending.remove(hash)
        val candidates = buildList {
            tipsByHash[hash].orEmpty().forEach { add(Candidate(it, 0)) }
            inherited?.let(::add)
        }
        if (candidates.isEmpty()) return null

        val minDistance = candidates.minOf { it.distance }
        val winner = candidates
            .filter { it.distance == minDistance }
            .distinctBy { it.ref.fullName }
            .minWith(CANDIDATE_ORDER)
        if (parents.isNotEmpty()) {
            val propagated = Candidate(winner.ref, minDistance + 1)
            parents.forEach { parent -> mergePending(parent, propagated) }
        }
        return winner.ref
    }

    private fun mergePending(hash: String, candidate: Candidate) {
        val existing = pending[hash]
        pending[hash] = when {
            existing == null -> candidate
            candidate.distance < existing.distance -> candidate
            candidate.distance > existing.distance -> existing
            CANDIDATE_ORDER.compare(candidate, existing) < 0 -> candidate
            else -> existing
        }
    }

    private companion object {
        val CANDIDATE_ORDER: Comparator<Candidate> = compareBy(
            { !it.ref.isCurrentBranch },
            { it.ref.kind != RefKind.LOCAL_BRANCH },
            { it.ref.shortName },
            { it.ref.fullName },
        )
    }
}
