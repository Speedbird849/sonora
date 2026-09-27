package dev.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.backend.Shelves
import dev.sonora.ytm.YtmCategory

/**
 * A row of YouTube Music's own categories, each in the colour it is painted there.
 *
 * Tinted rather than outlined because that is what the categories *are*: a grid of coloured
 * buttons on YouTube's own page, and a page of identical grey pills says "filter" where the
 * original says "here is somewhere to go". The stripe is the colour, the label is white on it.
 */
@Composable
internal fun CategoryRow(
    categories: List<YtmCategory>,
    chosen: YtmCategory?,
    onChoose: (YtmCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { category ->
            CategoryChip(
                category = category,
                selected = category.title == chosen?.title,
                onClick = { onChoose(category) },
            )
        }
    }
}

/**
 * One category: a circle in its own colour with the name inside it.
 *
 * The name is inside rather than under because the grid it came from puts it inside, and a name
 * under a circle is a caption on a swatch — which is what it looks like when the colours are the
 * same grey, and what it looks like deliberate when they are not.
 */
@Composable
private fun CategoryChip(
    category: YtmCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = Color(category.color)
    // A dark stripe would give a black circle and an invisible name, so it is graded up rather
    // than used as given.
    val readable = if (tint.perceivedLuminance() < 0.35f) {
        Brush.verticalGradient(listOf(tint.lighten(0.18f), tint.lighten(0.06f)))
    } else {
        Brush.verticalGradient(listOf(tint.lighten(0.10f), tint))
    }

    Box(
        modifier = Modifier
            .size(78.dp)
            .clip(CircleShape)
            .background(readable)
            // Ringed rather than scaled when chosen: a chip that grows pushes every chip after it
            // along, so picking one in the middle of a row rearranges the row.
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = category.title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/**
 * The playlists behind the chosen category.
 *
 * Real shelves with real covers, because that is what a page with nothing else on it has to be: a
 * grid of names is a list of things to read, and a wall of covers is a page of somewhere to start.
 */
@Composable
internal fun PlaylistShelf(
    title: String,
    subtitle: String,
    playlists: List<dev.sonora.ytm.YtmPlaylistRef>,
    onOpen: (dev.sonora.ytm.YtmPlaylistRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) return

    Column(modifier = modifier.padding(bottom = 12.dp)) {
        SectionHeader(title = title, subtitle = subtitle)

        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(SHELF_SPACING),
        ) {
            items(playlists, key = { it.browseId }) { playlist ->
                MediaCard(
                    artwork = rememberArtworkAt(playlist.artworkUrl, px = CARD_ART_PX),
                    title = playlist.title,
                    subtitle = playlist.subtitle ?: "Playlist",
                    shape = RoundedCornerShape(CARD_CORNER),
                    icon = Icons.Filled.LibraryMusic,
                    onClick = { onOpen(playlist) },
                )
            }
        }
    }
}

/**
 * The whole thing: a grid of categories above the playlists behind the chosen one.
 *
 * A placeholder rather than nothing while the categories are in flight, because a page that is
 * about to fill with a grid of coloured circles should say so before the circles arrive and not
 * after.
 */
@Composable
internal fun BrowseShelves(
    shelves: Shelves,
    onChoose: (YtmCategory) -> Unit,
    onOpen: (dev.sonora.ytm.YtmPlaylistRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (shelves.categories.isEmpty()) {
        if (shelves.loading) {
            Column(modifier = modifier.padding(bottom = 20.dp)) {
                ShelfSkeletonRow(count = 4)
                Spacer(Modifier.height(20.dp))
                ShelfSkeletonRow(count = 3)
            }
        }
        return
    }

    val chosen = shelves.chosen ?: shelves.categories.firstOrNull()
    val playlists = chosen?.let { shelves.playlists[it.title] }.orEmpty()

    Column(modifier = modifier) {
        ShelfHeader(
            title = "Something to listen to",
            subtitle = "YouTube Music's own shelves",
        )

        CategoryRow(
            categories = shelves.categories,
            chosen = chosen,
            onChoose = onChoose,
        )

        Spacer(Modifier.height(18.dp))

        when {
            playlists.isNotEmpty() -> PlaylistShelf(
                title = chosen?.title ?: "Playlists",
                subtitle = "Curated by YouTube Music",
                playlists = playlists,
                onOpen = onOpen,
            )

            chosen != null && shelves.playlists.containsKey(chosen.title) -> EmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = "Nothing in this one",
                message = "Pick another category, or search for what you want.",
            )

            else -> ShelfSkeletonRow(count = 3)
        }
    }
}

/** A row of stand-in cards at the real card metrics, so nothing jumps when the shelf lands. */
@Composable
internal fun ShelfSkeletonRow(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(SHELF_SPACING),
    ) {
        repeat(count) {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(140.dp)
                    .clip(RoundedCornerShape(CARD_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
}

private fun Color.lighten(by: Float): Color = Color(
    red = red + (1f - red) * by,
    green = green + (1f - green) * by,
    blue = blue + (1f - blue) * by,
    alpha = alpha,
)

private fun Color.perceivedLuminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
