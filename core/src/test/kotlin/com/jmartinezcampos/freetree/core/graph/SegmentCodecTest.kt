package com.jmartinezcampos.freetree.core.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SegmentCodecTest {

    @Test
    fun roundTripsEveryField() {
        val cases = listOf(
            GraphSegment(0, 0, 0, SegKind.PASS, false),
            GraphSegment(1, 0, 7, SegKind.IN, false),
            GraphSegment(0, 1, 255, SegKind.OUT, true),
            GraphSegment(65_535, 65_535, 128, SegKind.OUT, true),
            GraphSegment(32_768, 4_096, 42, SegKind.IN, false),
        )

        cases.forEach { segment ->
            val packed = SegmentCodec.encode(
                segment.from,
                segment.to,
                segment.color,
                segment.kind,
                segment.merge,
            )
            assertEquals(segment, SegmentCodec.decode(packed), "packed=$packed")
        }
    }

    @Test
    fun packsFieldsAtDefinedBitOffsets() {
        val packed = SegmentCodec.encode(
            from = 1,
            to = 2,
            color = 3,
            kind = SegKind.OUT,
            merge = true,
        )

        val expected = 1L + (2L shl 16) + (3L shl 32) + (2L shl 40) + (1L shl 42)
        assertEquals(expected, packed)
        assertEquals(1, (packed and 0xFFFF).toInt())
        assertEquals(2, ((packed ushr 16) and 0xFFFF).toInt())
        assertEquals(3, ((packed ushr 32) and 0xFF).toInt())
        assertEquals(SegKind.OUT.ordinal, ((packed ushr 40) and 0x3).toInt())
        assertEquals(1, ((packed ushr 42) and 0x1).toInt())
    }

    @Test
    fun decodesAllKindsAndMergeFlag() {
        SegKind.entries.forEach { kind ->
            listOf(false, true).forEach { merge ->
                val segment = SegmentCodec.decode(SegmentCodec.encode(5, 6, 9, kind, merge))
                assertEquals(kind, segment.kind)
                assertEquals(merge, segment.merge)
            }
        }
    }

    @Test
    fun boundaryLaneAndColorValuesFit() {
        val packed = SegmentCodec.encode(
            from = SegmentCodec.MAX_LANE,
            to = SegmentCodec.MAX_LANE,
            color = 255,
            kind = SegKind.OUT,
            merge = true,
        )
        val decoded = SegmentCodec.decode(packed)
        assertEquals(SegmentCodec.MAX_LANE, decoded.from)
        assertEquals(SegmentCodec.MAX_LANE, decoded.to)
        assertEquals(255, decoded.color)
        assertTrue(decoded.merge)
        assertFalse(packed < 0, "packed segment must remain positive")
    }

    @Test
    fun rejectsValuesOutsidePackedRanges() {
        assertFailsWith<IllegalArgumentException> {
            SegmentCodec.encode(-1, 0, 0, SegKind.PASS, false)
        }
        assertFailsWith<IllegalArgumentException> {
            SegmentCodec.encode(0, -1, 0, SegKind.PASS, false)
        }
        assertFailsWith<IllegalArgumentException> {
            SegmentCodec.encode(SegmentCodec.MAX_LANE + 1, 0, 0, SegKind.PASS, false)
        }
        assertFailsWith<IllegalArgumentException> {
            SegmentCodec.encode(0, SegmentCodec.MAX_LANE + 1, 0, SegKind.PASS, false)
        }
        assertFailsWith<IllegalArgumentException> {
            SegmentCodec.encode(0, 0, -1, SegKind.PASS, false)
        }
        assertFailsWith<IllegalArgumentException> {
            SegmentCodec.encode(0, 0, 256, SegKind.PASS, false)
        }
    }
}
