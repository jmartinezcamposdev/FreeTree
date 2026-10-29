package com.jmartinezcampos.freetree.core.graph

import com.jmartinezcampos.freetree.core.model.RawCommit

/**
 * Append-only storage for one chunk of rows. A chunk is never rewritten after a snapshot captures
 * it; later adds only touch indexes at or beyond the captured row limit.
 */
internal class RowChunk(private val capacity: Int) {
    var size = 0
        private set

    private val hashes: Array<String?> = arrayOfNulls(capacity)
    private val authors: Array<String?> = arrayOfNulls(capacity)
    private val subjects: Array<String?> = arrayOfNulls(capacity)
    private val times = LongArray(capacity)
    private val parentCounts = IntArray(capacity)
    private val nodeLanes = IntArray(capacity)
    private val nodeColors = IntArray(capacity)
    private val segStarts = IntArray(capacity + 1)
    private var segments = LongArray(INITIAL_SEGMENTS)
    private var segmentCount = 0

    fun append(c: RawCommit, nodeLane: Int, nodeColor: Int, rowSegments: List<Seg>) {
        check(size < capacity) { "chunk is full" }
        segStarts[size] = segmentCount
        hashes[size] = c.hash
        authors[size] = c.authorName
        subjects[size] = c.subject
        times[size] = c.authorTimeSec
        parentCounts[size] = c.parents.size
        nodeLanes[size] = nodeLane
        nodeColors[size] = nodeColor
        ensureSegmentCapacity(rowSegments.size)
        rowSegments.forEach { seg ->
            segments[segmentCount++] =
                SegmentCodec.encode(seg.from, seg.to, seg.color, seg.kind, seg.merge)
        }
        size++
        segStarts[size] = segmentCount
    }

    fun view(rowLimit: Int = size): ChunkView = ChunkView(
        hashes = hashes,
        authors = authors,
        subjects = subjects,
        times = times,
        parentCounts = parentCounts,
        nodeLanes = nodeLanes,
        nodeColors = nodeColors,
        segStarts = segStarts,
        segments = segments,
        rowLimit = rowLimit,
    )

    private fun ensureSegmentCapacity(additional: Int) {
        val required = segmentCount + additional
        if (required <= segments.size) return
        var newCapacity = maxOf(segments.size * 2, required)
        segments = segments.copyOf(newCapacity)
    }

    private companion object {
        const val INITIAL_SEGMENTS = 1024
    }
}

/** Immutable reader over a chunk's arrays, bounded by [rowLimit]. */
internal class ChunkView(
    private val hashes: Array<String?>,
    private val authors: Array<String?>,
    private val subjects: Array<String?>,
    private val times: LongArray,
    private val parentCounts: IntArray,
    private val nodeLanes: IntArray,
    private val nodeColors: IntArray,
    private val segStarts: IntArray,
    private val segments: LongArray,
    val rowLimit: Int,
) {
    fun hash(row: Int): String = hashes[row]!!

    fun author(row: Int): String = authors[row]!!

    fun subject(row: Int): String = subjects[row]!!

    fun timeSec(row: Int): Long = times[row]

    fun parentCount(row: Int): Int = parentCounts[row]

    fun nodeLane(row: Int): Int = nodeLanes[row]

    fun nodeColor(row: Int): Int = nodeColors[row]

    fun segments(row: Int): SegmentsView =
        RangeSegmentsView(segments, segStarts[row], segStarts[row + 1])
}

internal class RangeSegmentsView(
    private val data: LongArray,
    private val start: Int,
    private val end: Int,
) : SegmentsView {
    override val size: Int
        get() = end - start

    override fun get(index: Int): GraphSegment {
        require(index in 0 until size) { "segment index out of range: $index" }
        return SegmentCodec.decode(data[start + index])
    }
}

internal data class Seg(
    val from: Int,
    val to: Int,
    val color: Int,
    val kind: SegKind,
    val merge: Boolean,
)
