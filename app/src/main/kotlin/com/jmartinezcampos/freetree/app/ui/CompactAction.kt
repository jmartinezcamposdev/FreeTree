package com.jmartinezcampos.freetree.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Small rounded action used by compact rows. Only the action itself is clickable, leaving the
 * surrounding row free for text selection and other interactions.
 */
@Composable
internal fun CompactAction(
    description: String,
    testTag: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(GraphStyle.COMPACT_ACTION_SIZE)
            .clip(RoundedCornerShape(GraphStyle.COMPACT_ACTION_CORNER_RADIUS))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun CompactToggleAction(
    expanded: Boolean,
    collapsedDescription: String,
    expandedDescription: String,
    testTag: String,
    onClick: () -> Unit,
) {
    CompactAction(
        description = if (expanded) expandedDescription else collapsedDescription,
        testTag = testTag,
        onClick = onClick,
    ) {
        Text(
            text = if (expanded) "▼" else "▲",
            style = MaterialTheme.typography.labelSmall,
            color = LocalGraphColors.current.onSurfaceMuted,
        )
    }
}
