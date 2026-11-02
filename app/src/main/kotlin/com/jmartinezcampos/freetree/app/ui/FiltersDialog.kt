package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jmartinezcampos.freetree.core.filter.BranchFilter
import com.jmartinezcampos.freetree.core.filter.FilterField
import com.jmartinezcampos.freetree.core.filter.FilterRule
import com.jmartinezcampos.freetree.core.filter.FilterRuleGroup
import com.jmartinezcampos.freetree.core.filter.RuleMode
import com.jmartinezcampos.freetree.core.filter.validateBranchFilter

@Composable
internal fun ColumnScope.FiltersContent(
    dialog: AppDialog.Filters,
    controller: AppDialogController,
) {
    val colors = LocalGraphColors.current
    var draft by remember(dialog.filter) { mutableStateOf(dialog.filter) }
    val errors = validateBranchFilter(draft)
    val includeErrors = errors.filter { it.group == FilterRuleGroup.INCLUDE }.map { it.index }.toSet()
    val excludeErrors = errors.filter { it.group == FilterRuleGroup.EXCLUDE }.map { it.index }.toSet()
    val message = dialog.error ?: errors.firstOrNull()?.message

    DialogHeader(title = "Filters", onDismiss = controller::dismiss)
    Spacer(Modifier.height(GraphStyle.SPACE_SM))
    Text(
        text = "Rules match commits; the nearest branch ahead of those commits is shown or hidden.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceMuted,
    )
    Spacer(Modifier.height(GraphStyle.DIALOG_SECTION_GAP))

    val includeCard: @Composable (Modifier) -> Unit = { modifier ->
        FilterGroupCard(
            modifier = modifier,
            title = "Include branches",
            subtitle = "Branches with matching commits are shown.",
            emptyText = "No includes — all branches are visible.",
            group = FilterRuleGroup.INCLUDE,
            rules = draft.includes,
            mode = draft.includeMode,
            invalidIndices = includeErrors,
            accent = colors.primary,
            onRulesChange = { draft = draft.copy(includes = it) },
            onModeChange = { draft = draft.copy(includeMode = it) },
        )
    }
    val excludeCard: @Composable (Modifier) -> Unit = { modifier ->
        FilterGroupCard(
            modifier = modifier,
            title = "Exclude branches",
            subtitle = "Branches with matching commits are hidden.",
            emptyText = "No excludes — nothing is hidden.",
            group = FilterRuleGroup.EXCLUDE,
            rules = draft.excludes,
            mode = draft.excludeMode,
            invalidIndices = excludeErrors,
            accent = colors.error,
            onRulesChange = { draft = draft.copy(excludes = it) },
            onModeChange = { draft = draft.copy(excludeMode = it) },
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .weight(GraphStyle.FILL_WEIGHT)
            .fillMaxWidth(),
    ) {
        if (maxWidth < GraphStyle.FILTERS_STACK_BREAKPOINT) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(GraphStyle.FILTERS_CARD_GAP),
            ) {
                includeCard(
                    Modifier
                        .weight(GraphStyle.FILL_WEIGHT)
                        .fillMaxWidth(),
                )
                excludeCard(
                    Modifier
                        .weight(GraphStyle.FILL_WEIGHT)
                        .fillMaxWidth(),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(GraphStyle.FILTERS_CARD_GAP),
            ) {
                includeCard(
                    Modifier
                        .weight(GraphStyle.FILL_WEIGHT)
                        .fillMaxHeight(),
                )
                excludeCard(
                    Modifier
                        .weight(GraphStyle.FILL_WEIGHT)
                        .fillMaxHeight(),
                )
            }
        }
    }

    if (message != null) {
        Spacer(Modifier.height(GraphStyle.SPACE_SM))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.error,
            modifier = Modifier.testTag(UiTestTags.FILTERS_ERROR),
        )
    }

    Spacer(Modifier.height(GraphStyle.DIALOG_ACTIONS_TOP_SPACE))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { draft = BranchFilter() },
            enabled = draft != BranchFilter(),
            modifier = Modifier.testTag(UiTestTags.FILTERS_CLEAR_ALL),
        ) {
            Text("Clear all", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.weight(GraphStyle.FILL_WEIGHT))
        if (dialog.busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(GraphStyle.BUSY_INDICATOR_SIZE),
                color = colors.primary,
                strokeWidth = GraphStyle.STROKE_WIDTH,
            )
            Spacer(Modifier.width(GraphStyle.SPACE_SM))
            Text(
                text = "Applying…",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
            Spacer(Modifier.width(GraphStyle.SPACE_MD))
        }
        DialogButton(
            label = "Cancel",
            onClick = controller::dismiss,
            kind = DialogButtonKind.Secondary,
            testTag = UiTestTags.FILTERS_CANCEL,
        )
        Spacer(Modifier.width(GraphStyle.SPACE_MD))
        DialogButton(
            label = "Apply",
            onClick = {
                dialog.onApply(draft)
                controller.dismiss()
            },
            kind = DialogButtonKind.Primary,
            testTag = UiTestTags.FILTERS_APPLY,
            enabled = errors.isEmpty(),
        )
    }
}

@Composable
private fun FilterGroupCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    emptyText: String,
    group: FilterRuleGroup,
    rules: List<FilterRule>,
    mode: RuleMode,
    invalidIndices: Set<Int>,
    accent: Color,
    onRulesChange: (List<FilterRule>) -> Unit,
    onModeChange: (RuleMode) -> Unit,
) {
    val colors = LocalGraphColors.current
    val shape = RoundedCornerShape(GraphStyle.FILTERS_CARD_CORNER_RADIUS)
    val scrollState = rememberScrollState()
    val groupName = group.tag
    val sectionTag = if (group == FilterRuleGroup.INCLUDE) {
        UiTestTags.FILTERS_INCLUDE_SECTION
    } else {
        UiTestTags.FILTERS_EXCLUDE_SECTION
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(GraphStyle.DIVIDER_THICKNESS, colors.divider, shape)
            .testTag(sectionTag),
    ) {
        Box(
            modifier = Modifier
                .width(GraphStyle.FILTERS_ACCENT_WIDTH)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            modifier = Modifier
                .weight(GraphStyle.FILL_WEIGHT)
                .fillMaxHeight()
                .padding(horizontal = GraphStyle.SPACE_MD, vertical = GraphStyle.SPACE_SM),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(GraphStyle.FILL_WEIGHT),
                )
                RuleModeToggle(
                    groupName = groupName,
                    mode = mode,
                    onModeChange = onModeChange,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
            Spacer(Modifier.height(GraphStyle.SPACE_SM))

            Box(
                modifier = Modifier
                    .weight(GraphStyle.FILL_WEIGHT)
                    .fillMaxWidth(),
            ) {
                if (rules.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = emptyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = GraphStyle.SCROLLBAR_GUTTER)
                            .verticalScroll(scrollState),
                    ) {
                        rules.forEachIndexed { index, rule ->
                            FilterRuleRow(
                                group = group,
                                index = index,
                                rule = rule,
                                invalid = index in invalidIndices,
                                onRuleChange = { updated ->
                                    onRulesChange(rules.toMutableList().also { it[index] = updated })
                                },
                                onRemove = {
                                    onRulesChange(rules.filterIndexed { i, _ -> i != index })
                                },
                            )
                            Spacer(Modifier.height(GraphStyle.FILTERS_RULE_GAP))
                        }
                    }
                }
                if (rules.isNotEmpty()) {
                    ModernScrollbar(
                        scrollState = scrollState,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        alwaysVisible = false,
                    )
                }
            }

            Spacer(Modifier.height(GraphStyle.SPACE_XS))
            TextButton(
                onClick = { onRulesChange(rules + FilterRule(field = FilterField.MESSAGE)) },
                contentPadding = PaddingValues(horizontal = GraphStyle.SPACE_XS, vertical = 0.dp),
                modifier = Modifier
                    .height(GraphStyle.DIALOG_BUTTON_HEIGHT)
                    .testTag(
                        if (group == FilterRuleGroup.INCLUDE) {
                            UiTestTags.FILTERS_ADD_INCLUDE
                        } else {
                            UiTestTags.FILTERS_ADD_EXCLUDE
                        },
                    ),
            ) {
                Text(
                    text = if (group == FilterRuleGroup.INCLUDE) "+ Add include" else "+ Add exclude",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun RuleModeToggle(
    groupName: String,
    mode: RuleMode,
    onModeChange: (RuleMode) -> Unit,
) {
    val colors = LocalGraphColors.current
    val shape = RoundedCornerShape(GraphStyle.DIALOG_CONTROL_CORNER_RADIUS)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.surface)
            .border(GraphStyle.DIVIDER_THICKNESS, colors.divider, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RuleMode.entries.forEach { option ->
            val selected = option == mode
            Box(
                modifier = Modifier
                    .background(if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onModeChange(option) }
                    .padding(horizontal = GraphStyle.SPACE_SM, vertical = GraphStyle.SPACE_XXS)
                    .testTag(UiTestTags.filtersModeOption(groupName, option.name)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (option == RuleMode.ANY) "Any" else "All",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.primary else colors.onSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun FilterRuleRow(
    group: FilterRuleGroup,
    index: Int,
    rule: FilterRule,
    invalid: Boolean,
    onRuleChange: (FilterRule) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalGraphColors.current
    val shape = RoundedCornerShape(GraphStyle.DIALOG_CONTROL_CORNER_RADIUS)
    val groupName = group.tag
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GraphStyle.FILTERS_RULE_MIN_HEIGHT)
            .clip(shape)
            .background(colors.surface)
            .border(
                GraphStyle.DIVIDER_THICKNESS,
                if (invalid) colors.error else colors.divider,
                shape,
            )
            .padding(horizontal = GraphStyle.SPACE_SM, vertical = GraphStyle.SPACE_XXS)
            .testTag(UiTestTags.filtersRule(groupName, index)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraphStyle.SPACE_SM),
    ) {
        FilterFieldSelector(
            groupName = groupName,
            index = index,
            field = rule.field,
            onFieldChange = { onRuleChange(rule.copy(field = it)) },
        )
        if (rule.field == FilterField.DATE) {
            FilterTextField(
                value = rule.after.orEmpty(),
                onValueChange = { onRuleChange(rule.copy(after = it.ifBlank { null })) },
                label = "After",
                placeholder = "yyyy-MM-dd",
                tag = UiTestTags.filtersRuleAfter(groupName, index),
                modifier = Modifier.weight(GraphStyle.FILL_WEIGHT),
            )
            FilterTextField(
                value = rule.before.orEmpty(),
                onValueChange = { onRuleChange(rule.copy(before = it.ifBlank { null })) },
                label = "Before",
                placeholder = "yyyy-MM-dd",
                tag = UiTestTags.filtersRuleBefore(groupName, index),
                modifier = Modifier.weight(GraphStyle.FILL_WEIGHT),
            )
        } else {
            FilterTextField(
                value = rule.text,
                onValueChange = { onRuleChange(rule.copy(text = it)) },
                placeholder = if (rule.field == FilterField.AUTHOR) "Name or email" else "Message text",
                tag = UiTestTags.filtersRuleValue(groupName, index),
                modifier = Modifier.weight(GraphStyle.FILL_WEIGHT),
            )
        }
        Box(
            modifier = Modifier
                .size(GraphStyle.DIALOG_CLOSE_SIZE)
                .clip(shape)
                .clickable(onClick = onRemove)
                .testTag(UiTestTags.filtersRuleRemove(groupName, index)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "×",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun FilterFieldSelector(
    groupName: String,
    index: Int,
    field: FilterField,
    onFieldChange: (FilterField) -> Unit,
) {
    val colors = LocalGraphColors.current
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(GraphStyle.DIALOG_CONTROL_CORNER_RADIUS)
    Box {
        Row(
            modifier = Modifier
                .width(GraphStyle.FILTERS_FIELD_WIDTH)
                .height(GraphStyle.FILTERS_INPUT_HEIGHT)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(GraphStyle.DIVIDER_THICKNESS, colors.divider, shape)
                .clickable { expanded = true }
                .padding(horizontal = GraphStyle.SPACE_SM)
                .testTag(UiTestTags.filtersRuleField(groupName, index)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                modifier = Modifier.weight(GraphStyle.FILL_WEIGHT),
            )
            Text(
                text = "▾",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FilterField.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    onClick = {
                        expanded = false
                        onFieldChange(option)
                    },
                    modifier = Modifier.testTag(UiTestTags.filtersFieldOption(option.name)),
                )
            }
        }
    }
}

@Composable
private fun FilterTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = LocalGraphColors.current
    val shape = RoundedCornerShape(GraphStyle.DIALOG_CONTROL_CORNER_RADIUS)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontSize = GraphStyle.DIALOG_BODY_SIZE,
            lineHeight = GraphStyle.DIALOG_BODY_LINE_HEIGHT,
            color = colors.onSurface,
        ),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier
            .height(GraphStyle.FILTERS_INPUT_HEIGHT)
            .testTag(tag),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(colors.surfaceRaised)
                    .border(GraphStyle.DIVIDER_THICKNESS, colors.divider, shape)
                    .padding(horizontal = GraphStyle.SPACE_SM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceMuted,
                        modifier = Modifier.padding(end = GraphStyle.SPACE_XS),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(GraphStyle.FILL_WEIGHT)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceMuted,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

private val FilterRuleGroup.tag: String
    get() = if (this == FilterRuleGroup.INCLUDE) "include" else "exclude"

private val FilterField.label: String
    get() = when (this) {
        FilterField.AUTHOR -> "Author"
        FilterField.MESSAGE -> "Message"
        FilterField.DATE -> "Date"
    }
