package com.jmartinezcampos.freetree.core.graph

import com.jmartinezcampos.freetree.core.model.RawCommit

class LaneLayoutEngine(flat: Boolean = false, chunkSize: Int = 4096) {
    init {
        require(chunkSize > 0) { "chunkSize must be positive: $chunkSize" }
    }

    private val flatMode = flat
    private val chunkSize = chunkSize
    private val chunks = ArrayList<RowChunk>()
    private var open = RowChunk(chunkSize)
    private var rowCount = 0
    private val lanes = ArrayList<Lane?>()
    private var colorCounter = 0
    private var maxLanes = 0

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
        )
    }

    private fun addFlat(c: RawCommit) {
        maxLanes = maxOf(maxLanes, 1)
        appendRow(c, nodeLane = 0, nodeColor = 0, segments = emptyList())
    }

    private fun addGraph(c: RawCommit) {
        val incoming = lanes.indices.filter { lanes[it]?.awaits == c.hash }
        val node = if (incoming.isEmpty()) firstFreeSlot() else incoming.min()
        val color = if (incoming.isEmpty()) nextColor() else lanes[node]!!.color
        val segments = ArrayList<Seg>()

        lanes.forEachIndexed { laneIndex, lane ->
            if (lane == null) return@forEachIndexed
            segments += if (laneIndex in incoming) {
                Seg(laneIndex, node, lane.color, SegKind.IN, merge = false)
            } else {
                Seg(laneIndex, laneIndex, lane.color, SegKind.PASS, merge = false)
            }
        }
        incoming.forEach { lanes[it] = null }

        if (c.parents.isNotEmpty()) {
            lanes[node] = Lane(c.parents[0], color)
            segments += Seg(node, node, color, SegKind.OUT, merge = false)
        }
        for (parent in c.parents.drop(1)) {
            val existing = lanes.indexOfFirst { it?.awaits == parent }
            if (existing >= 0) {
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

    private data class Lane(val awaits: String, val color: Int)

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
