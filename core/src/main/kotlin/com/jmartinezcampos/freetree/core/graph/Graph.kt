package com.jmartinezcampos.freetree.core.graph

import com.jmartinezcampos.freetree.core.model.RawCommit

class LaneLayoutEngine(flat: Boolean = false, chunkSize: Int = 4096) {
    fun add(c: RawCommit): Unit = TODO("Implemented in Phase 1")

    fun snapshot(complete: Boolean): GraphSnapshot = TODO("Implemented in Phase 1")
}

class GraphSnapshot {
    val rowCount: Int
        get() = phaseOne()

    val maxLanes: Int
        get() = phaseOne()

    val complete: Boolean
        get() = phaseOne()

    val flat: Boolean
        get() = phaseOne()

    fun hash(row: Int): String = phaseOne()

    fun subject(row: Int): String = phaseOne()

    fun author(row: Int): String = phaseOne()

    fun timeSec(row: Int): Long = phaseOne()

    fun parentCount(row: Int): Int = phaseOne()

    fun nodeLane(row: Int): Int = phaseOne()

    fun nodeColor(row: Int): Int = phaseOne()

    fun segments(row: Int): SegmentsView = phaseOne()

    fun indexOfHash(hash: String): Int = phaseOne()
}

private fun phaseOne(): Nothing = TODO("Implemented in Phase 1")

enum class SegKind { PASS, IN, OUT }

data class GraphSegment(
    val from: Int,
    val to: Int,
    val color: Int,
    val kind: SegKind,
    val merge: Boolean,
)

interface SegmentsView {
    val size: Int
    operator fun get(index: Int): GraphSegment
}
