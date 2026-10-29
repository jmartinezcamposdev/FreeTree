package com.jmartinezcampos.freetree.core.bench

import java.util.Locale

internal data class Stats(val samples: List<Double>) {
    val count: Int get() = samples.size
    val min: Double get() = samples.min()
    val max: Double get() = samples.max()
    val median: Double get() = percentile(50.0)
    val p99: Double get() = percentile(99.0)
    val mean: Double get() = samples.average()

    fun percentile(value: Double): Double {
        require(samples.isNotEmpty()) { "no samples" }
        val sorted = samples.sorted()
        if (sorted.size == 1) return sorted[0]
        val position = (value / 100.0) * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val fraction = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    fun format(): String = formatMillis(median)
}

internal fun stats(samples: List<Double>): Stats {
    require(samples.isNotEmpty()) { "no samples" }
    return Stats(samples.toList())
}

internal fun formatMillis(value: Double): String = when {
    value < 10.0 -> "%.2f ms".format(Locale.ROOT, value)
    value < 100.0 -> "%.1f ms".format(Locale.ROOT, value)
    else -> "%.0f ms".format(Locale.ROOT, value)
}

internal fun formatRate(value: Double): String = when {
    value >= 1_000_000.0 -> "%.2f M rows/s".format(Locale.ROOT, value / 1_000_000.0)
    value >= 1_000.0 -> "%.1f k rows/s".format(Locale.ROOT, value / 1_000.0)
    else -> "%.0f rows/s".format(Locale.ROOT, value)
}

internal inline fun measureMillis(block: () -> Unit): Double {
    val started = System.nanoTime()
    block()
    return (System.nanoTime() - started) / 1_000_000.0
}

internal fun elapsedMillis(startedNanos: Long): Double =
    (System.nanoTime() - startedNanos) / 1_000_000.0
