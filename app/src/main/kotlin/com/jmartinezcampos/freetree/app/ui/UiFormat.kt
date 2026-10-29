package com.jmartinezcampos.freetree.app.ui

import androidx.compose.ui.graphics.Color
import com.jmartinezcampos.freetree.core.model.RefKind
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern(GraphStyle.DATE_PATTERN)

fun formatDate(epochSeconds: Long): String =
    dateFormatter.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

fun abbreviateHash(hash: String): String =
    hash.take(GraphStyle.HASH_ABBREVIATION)

fun tabTitle(pathText: String): String = try {
    Path.of(pathText).fileName?.toString()?.ifBlank { pathText } ?: pathText
} catch (error: RuntimeException) {
    pathText
}

fun isStatusBlank(status: Char): Boolean = status == ' ' || status == '.'

fun statusLabel(status: Char): String = when (status) {
    'A' -> "A"
    'M' -> "M"
    'D' -> "D"
    'R' -> "R"
    'C' -> "C"
    'U' -> "U"
    '?' -> "U"
    ' ', '.' -> ""
    else -> "M"
}

fun statusColor(status: Char, colors: GraphColors) = when (status) {
    'A', '?' -> colors.statusAdded
    'D' -> colors.statusDeleted
    'R', 'C' -> colors.statusRenamed
    ' ', '.' -> colors.onSurfaceMuted
    else -> colors.statusModified
}

fun chipAppearance(kind: RefKind, isHead: Boolean, colors: GraphColors): Pair<Color, Color> =
    when {
        isHead -> colors.chipHeadBackground to colors.chipHeadForeground
        kind == RefKind.LOCAL_BRANCH -> colors.chipLocalBackground to colors.chipLocalForeground
        kind == RefKind.REMOTE_BRANCH -> colors.chipRemoteBackground to colors.chipRemoteForeground
        else -> colors.chipTagBackground to colors.chipTagForeground
    }
