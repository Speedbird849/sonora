package dev.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Rounded, but well short of a capsule.
 *
 * A pill at this height is a lozenge, and a lozenge reads as a toggle you are throwing rather than
 * as a label you are choosing. Twelve is far enough to soften the corners of a rectangle and short
 * enough that the straight edge is still visible in it.
 */
private val FILTER_PILL_SHAPE = RoundedCornerShape(12.dp)

/**
 * One of a row of choices, filled when it is the current one.
 *
 * A filled pill rather than an outlined one because the row is a set of mutually exclusive options
 * and exactly one of them is true at a time — which is a fact about weight, not about a tick. The
 * fill inverts to the page's own colour, so the selected pill reads as a shape cut *out* of the row
 * rather than as a chip sitting on it.
 */
@Composable
internal fun ChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(FILTER_PILL_SHAPE)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.background
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A row of choices that scrolls sideways when there are more than fit across.
 *
 * Padded at the page gutter rather than inside each pill, so the row's own edge lines up with the
 * rows beneath it and the first pill is not the one thing on the line that is out of step.
 */
@Composable
internal fun ChoicePillRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * A shelf of choices laid out as a carousel instead of a scrolling row.
 *
 * The same pills, but paging sideways and holding several at once — which is what a set of
 * *sections* is, as against a set of *filters*, which is a control and belongs in one line above
 * the results.
 */
@Composable
internal fun ChoicePillShelf(content: LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
