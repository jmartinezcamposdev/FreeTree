package com.jmartinezcampos.freetree.core.graph

import com.jmartinezcampos.freetree.core.model.RawCommit

class LaneLayoutEngine(
    flat: Boolean = false,
    chunkSize: Int = 4096,
    workingTreeHash: String? = null,
) {
    init {
        require(chunkSize > 0) { "chunkSize must be positive: $chunkSize" }
    }

    private val flatMode = flat
    private val chunkSize = chunkSize
    private val workingTreeHashValue = workingTreeHash.takeIf { !flatMode }
    private val chunks = ArrayList<RowChunk>()
    private var open = RowChunk(chunkSize)
    private var rowCount = 0
    private val lanes = ArrayList<Lane?>()
    private var colorCounter = 0
    private var maxLanes = 0
    private var workingTreeLane: Int? = null
    private var workingTreeMerged = false
    private var headBranchLane: Int? = null
    private var headBranchHasChild = false

    init {
        if (workingTreeHashValue != null) {
            lanes += Lane(workingTreeHashValue, nextColor(), hidden = true)
            workingTreeLane = 0
        }
    }

    internal val flat: Boolean
        get() = flatMode

    fun add(c: RawCommit) {
        if (flatMode) addFlat(c) else addGraph(c)
        rowCount++
    }

    fun snapshot(complete: Boolean): GraphSnapshot {
        val views = ArrayList<ChunkView>(chunks.size + 1)
        chunks.forEach { views += it.view() }
        if (open.size > 0) views += open.view()
        return GraphSnapshot(
            chunks = views,
            chunkSize = chunkSize,
            rowCount = rowCount,
            maxLanes = maxLanes,
            complete = complete,
            flat = flatMode,
            workingTreeLane = workingTreeLane.takeIf { workingTreeMerged },
        )
    }

    private fun addFlat(c: RawCommit) {
        maxLanes = maxOf(maxLanes, 1)
        appendRow(c, nodeLane = 0, nodeColor = 0, segments = emptyList())
    }

    private fun addGraph(c: RawCommit) {
        if (workingTreeHashValue != null &&
            rowCount == 0 &&
            headBranchLane == null &&
            c.hash != workingTreeHashValue
        ) {
            val workingColor = lanes[0]?.color ?: 0
            lanes.add(1, Lane(workingTreeHashValue, workingColor, hidden = true))
            headBranchLane = 1
        }

        val isHeadRow = workingTreeHashValue != null && c.hash == workingTreeHashValue
        val separateHeadLane = isHeadRow && headBranchLane != null
        if (isHeadRow && headBranchHasChild) {
            val headLane = headBranchLane ?: 0
            lanes[headLane] = lanes[headLane]?.copy(hidden = false)
        }

        val rawIncoming = lanes.indices.filter { lanes[it]?.awaits == c.hash }
        val workingIncoming = if (isHeadRow && workingTreeLane != null && workingTreeLane in rawIncoming) {
            workingTreeLane
        } else {
            null
        }
        val incoming = if (separateHeadLane && workingIncoming != null) {
            rawIncoming.filter { it != workingIncoming }
        } else {
            rawIncoming
        }
        val node = if (incoming.isEmpty()) firstFreeSlot() else incoming.min()
        val color = if (incoming.isEmpty()) nextColor() else lanes[node]!!.color
        val segments = ArrayList<Seg>()

        lanes.forEachIndexed { laneIndex, lane ->
            if (lane == null || lane.hidden) return@forEachIndexed
            segments += if (laneIndex in incoming) {
                Seg(laneIndex, node, lane.color, SegKind.IN, merge = false)
            } else {
                Seg(laneIndex, laneIndex, lane.color, SegKind.PASS, merge = false)
            }
        }
        incoming.forEach { lanes[it] = null }

        if (isHeadRow && separateHeadLane && workingIncoming != null) {
            segments += Seg(
                from = workingIncoming,
                to = node,
                color = lanes[workingIncoming]?.color ?: 0,
                kind = SegKind.IN,
                merge = false,
            )
            lanes[workingIncoming] = null
            workingTreeMerged = true
        } else if (isHeadRow && workingIncoming != null) {
            workingTreeMerged = true
        }

        if (c.parents.isNotEmpty()) {
            lanes[node] = Lane(c.parents[0], color)
            segments += Seg(node, node, color, SegKind.OUT, merge = false)
        }
        for (parent in c.parents.drop(1)) {
            val existing = lanes.indices.firstOrNull { index ->
                lanes[index]?.awaits == parent && index != workingTreeLane
            } ?: -1
            if (existing >= 0) {
                val lane = lanes[existing]!!
                if (parent == workingTreeHashValue && lane.hidden) {
                    lanes[existing] = lane.copy(color = color, hidden = false)
                    headBranchHasChild = true
                }
                segments += Seg(node, existing, lanes[existing]!!.color, SegKind.OUT, merge = true)
            } else {
                val free = firstFreeSlot()
                lanes[free] = Lane(parent, nextColor())
                segments += Seg(node, free, lanes[free]!!.color, SegKind.OUT, merge = true)
            }
        }

        trimTrailingNulls(lanes)
        maxLanes = maxOf(maxLanes, lanes.size, node + 1)
        appendRow(c, node, color, segments)
    }

    private fun appendRow(c: RawCommit, nodeLane: Int, nodeColor: Int, segments: List<Seg>) {
        if (open.size == chunkSize) {
            chunks += open
            open = RowChunk(chunkSize)
        }
        open.append(c, nodeLane, nodeColor, segments)
    }

    private fun firstFreeSlot(): Int {
        val free = lanes.indexOfFirst { it == null }
        if (free >= 0) return free
        lanes += null
        return lanes.lastIndex
    }

    private fun nextColor(): Int = colorCounter++ and COLOR_MASK

    private fun trimTrailingNulls(lanes: ArrayList<Lane?>) {
        while (lanes.isNotEmpty() && lanes.last() == null) lanes.removeAt(lanes.lastIndex)
    }

    private data class Lane(val awaits: String, val color: Int, val hidden: Boolean = false)

    private companion object {
        const val COLOR_MASK = 0xFF
    }
}

class GraphSnapshot internal constructor(
    private val chunks: List<ChunkView>,
    private val chunkSize: Int,
    val rowCount: Int,
    val maxLanes: Int,
    val complete: Boolean,
    val flat: Boolean,
    val workingTreeLane: Int?,
) {
    private val rowByHash: Map<String, Int> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val index = HashMap<String, Int>(rowCount)
        chunks.forEachIndexed { chunkIndex, chunk ->
            val firstRow = chunkIndex * chunkSize
            for (local in 0 until chunk.rowLimit) {
                index[chunk.hash(local)] = firstRow + local
            }
        }
        index
    }

    fun hash(row: Int): String = viewFor(row).hash(localRow(row))

    fun subject(row: Int): String = viewFor(row).subject(localRow(row))

    fun author(row: Int): String = viewFor(row).author(localRow(row))

    fun timeSec(row: Int): Long = viewFor(row).timeSec(localRow(row))

    fun parentCount(row: Int): Int = viewFor(row).parentCount(localRow(row))

    fun nodeLane(row: Int): Int = viewFor(row).nodeLane(localRow(row))

    fun nodeColor(row: Int): Int = viewFor(row).nodeColor(localRow(row))

    fun segments(row: Int): SegmentsView = viewFor(row).segments(localRow(row))

    fun indexOfHash(hash: String): Int = rowByHash[hash] ?: -1

    private fun viewFor(row: Int): ChunkView {
        require(row in 0 until rowCount) { "row out of range: $row (rowCount=$rowCount)" }
        return chunks[row / chunkSize]
    }

    private fun localRow(row: Int): Int = row % chunkSize
}

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
