package com.jmartinezcampos.freetree.core.filter

import com.jmartinezcampos.freetree.core.model.RawCommit
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

@Serializable
enum class FilterField { AUTHOR, MESSAGE, DATE }

@Serializable
enum class RuleMode { ANY, ALL }

@Serializable
data class FilterRule(
    val field: FilterField = FilterField.MESSAGE,
    val text: String = "",
    val after: String? = null,
    val before: String? = null,
)

@Serializable
data class BranchFilter(
    val includes: List<FilterRule> = emptyList(),
    val excludes: List<FilterRule> = emptyList(),
    val includeMode: RuleMode = RuleMode.ANY,
    val excludeMode: RuleMode = RuleMode.ANY,
    val commitMode: Boolean = false,
) {
    val isActive: Boolean get() = includes.isNotEmpty() || excludes.isNotEmpty()
    val activeRuleCount: Int get() = includes.size + excludes.size
}

enum class FilterRuleGroup { INCLUDE, EXCLUDE }

data class FilterRuleError(
    val group: FilterRuleGroup,
    val index: Int,
    val message: String,
)

/**
 * Dates are strict and use the local day boundaries:
 * - `after 2024-01-01` matches from 2024-01-02 00:00,
 * - `before 2024-01-01` matches before 2024-01-01 00:00.
 */
fun validateBranchFilter(filter: BranchFilter): List<FilterRuleError> = buildList {
    filter.includes.forEachIndexed { index, rule ->
        rule.validationError()?.let { add(FilterRuleError(FilterRuleGroup.INCLUDE, index, it)) }
    }
    filter.excludes.forEachIndexed { index, rule ->
        rule.validationError()?.let { add(FilterRuleError(FilterRuleGroup.EXCLUDE, index, it)) }
    }
}

fun FilterRule.validationError(): String? = when (field) {
    FilterField.AUTHOR, FilterField.MESSAGE ->
        if (text.isBlank()) "Enter a value." else null
    FilterField.DATE -> {
        val afterDate = parseDate(after)
        val beforeDate = parseDate(before)
        when {
            !after.isNullOrBlank() && afterDate == null -> "Use yyyy-MM-dd for After."
            !before.isNullOrBlank() && beforeDate == null -> "Use yyyy-MM-dd for Before."
            afterDate == null && beforeDate == null -> "Set After, Before, or both."
            afterDate != null && beforeDate != null && !afterDate.isBefore(beforeDate) ->
                "After must be before Before."
            else -> null
        }
    }
}

internal data class CompiledFilterRule(
    val field: FilterField,
    val text: String,
    val afterEpochSec: Long? = null,
    val beforeEpochSec: Long? = null,
) {
    fun matches(commit: RawCommit): Boolean = when (field) {
        FilterField.AUTHOR ->
            commit.authorName.contains(text, ignoreCase = true) ||
                commit.authorEmail.contains(text, ignoreCase = true)
        FilterField.MESSAGE -> commit.subject.contains(text, ignoreCase = true)
        FilterField.DATE ->
            (afterEpochSec == null || commit.authorTimeSec >= afterEpochSec) &&
                (beforeEpochSec == null || commit.authorTimeSec < beforeEpochSec)
    }
}

internal fun compileRule(rule: FilterRule, zoneId: ZoneId): CompiledFilterRule {
    val normalized = rule.text.trim()
    val afterDate = parseDate(rule.after)
    val beforeDate = parseDate(rule.before)
    return CompiledFilterRule(
        field = rule.field,
        text = normalized,
        afterEpochSec = afterDate?.plusDays(1)?.atStartOfDay(zoneId)?.toEpochSecond(),
        beforeEpochSec = beforeDate?.atStartOfDay(zoneId)?.toEpochSecond(),
    )
}

internal class CompiledRuleGroup(
    private val rules: List<CompiledFilterRule>,
    private val mode: RuleMode,
) {
    val isEmpty: Boolean get() = rules.isEmpty()

    fun matches(commit: RawCommit): Boolean = when (mode) {
        RuleMode.ANY -> rules.any { it.matches(commit) }
        RuleMode.ALL -> rules.all { it.matches(commit) }
    }

    companion object {
        fun compile(rules: List<FilterRule>, mode: RuleMode, zoneId: ZoneId): CompiledRuleGroup =
            CompiledRuleGroup(rules.map { compileRule(it, zoneId) }, mode)
    }
}

private fun parseDate(value: String?): LocalDate? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return try {
        LocalDate.parse(trimmed)
    } catch (e: DateTimeParseException) {
        null
    }
}
