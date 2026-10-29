package com.jmartinezcampos.freetree.core.graph

/**
 * Packs a segment into [from:16 | to:16 | color:8 | kind:2 | merge:1].
 */
internal object SegmentCodec {
    private const val LANE_BITS = 16
    private const val LANE_MASK = (1L shl LANE_BITS) - 1
    private const val TO_SHIFT = LANE_BITS
    private const val COLOR_BITS = 8
    private const val COLOR_MASK = (1L shl COLOR_BITS) - 1
    private const val COLOR_SHIFT = TO_SHIFT + LANE_BITS
    private const val KIND_BITS = 2
    private const val KIND_MASK = (1L shl KIND_BITS) - 1
    private const val KIND_SHIFT = COLOR_SHIFT + COLOR_BITS
    private const val MERGE_SHIFT = KIND_SHIFT + KIND_BITS
    private const val MERGE_MASK = 1L
    private const val PALETTE_SIZE = 256

    const val MAX_LANE = 0xFFFF

    fun encode(from: Int, to: Int, color: Int, kind: SegKind, merge: Boolean): Long {
        require(from in 0..MAX_LANE) { "from lane out of range: $from" }
        require(to in 0..MAX_LANE) { "to lane out of range: $to" }
        require(color in 0 until PALETTE_SIZE) { "color out of range: $color" }
        return (from.toLong() and LANE_MASK) or
            ((to.toLong() and LANE_MASK) shl TO_SHIFT) or
            ((color.toLong() and COLOR_MASK) shl COLOR_SHIFT) or
            ((kind.ordinal.toLong() and KIND_MASK) shl KIND_SHIFT) or
            ((if (merge) 1L else 0L) shl MERGE_SHIFT)
    }

    fun decode(packed: Long): GraphSegment = GraphSegment(
        from = (packed and LANE_MASK).toInt(),
        to = ((packed ushr TO_SHIFT) and LANE_MASK).toInt(),
        color = ((packed ushr COLOR_SHIFT) and COLOR_MASK).toInt(),
        kind = SegKind.entries[((packed ushr KIND_SHIFT) and KIND_MASK).toInt()],
        merge = ((packed ushr MERGE_SHIFT) and MERGE_MASK) != 0L,
    )
}
