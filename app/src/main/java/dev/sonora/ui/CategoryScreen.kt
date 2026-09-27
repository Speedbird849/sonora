package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.ytm.YtmCategory
import dev.sonora.ytm.YtmPlaylistRef

/**
 * Everything in one mood or genre, as a page.
 *
 * A page and not a swap, because a category is forty playlists and a grid on the page behind it
 * would have to be read from the top every time to find out what you had picked. Opened, it has a
 * title, a way back, and room to show what is in it.
 *
 * Two to a row: one column of forty would be forty screens of scrolling, and the covers are square
 * so they sit as well in a grid as they do in a row.
 */
@Composable
internal fun CategoryScreen(
    category: YtmCategory,
    playlists: List<YtmPlaylistRef>,
    onBack: () -> Unit,
    onOpen: (YtmPlaylistRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
            Text(
                text = category.group,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        CategoryBanner(category = category, playlists = playlists)

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(strokeWidth = 2.5.dp)
            }
            return
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PAGE_GUTTER,
                end = PAGE_GUTTER,
                top = 8.dp,
                bottom = listBottomPadding(withMiniPlayer = true),
            ),
            horizontalArrangement = Arrangement.spacedBy(SHELF_SPACING),
            verticalArrangement = Arrangement.spacedBy(SHELF_SPACING),
        ) {
            items(playlists, key = { it.browseId }) { playlist ->
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(CARD_CORNER))
                        .clickable { onOpen(playlist) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(CARD_CORNER))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        val artwork = rememberArtworkAt(playlist.artworkUrl, px = CARD_ART_PX)
                        if (artwork != null) {
                            Image(
                                bitmap = artwork,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.LibraryMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = playlist.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The page's own header: the category's colour, its name, and the first cover in it.
 *
 * The cover is what the grid below is made of, blurred back behind the title — so the page looks
 * like it is made of the same forty things it is listing, rather than a coloured banner above a
 * wall of squares.
 */
@Composable
private fun CategoryBanner(
    category: YtmCategory,
    playlists: List<YtmPlaylistRef>,
) {
    val tint = Color(category.color)
    // The shipped picture where the category has one, so the page's banner is there the moment it
    // opens rather than after a request; the first playlist's cover otherwise.
    val shipped = rememberCategoryArtwork(category.title)
    val fetched = rememberArtworkAt(
        playlists.firstNotNullOfOrNull { it.artworkUrl },
        px = CARD_ART_PX,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        if (shipped != null) {
            Image(
                painter = shipped,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp, BlurredEdgeTreatment.Unbounded),
            )
        } else if (fetched != null) {
            Image(
                bitmap = fetched,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp, BlurredEdgeTreatment.Unbounded),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to tint.copy(alpha = 0.35f),
                        1f to tint.copy(alpha = 0.88f),
                    ),
                ),
        )

        Text(
            text = category.title,
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = PAGE_GUTTER + 6.dp, end = PAGE_GUTTER, bottom = 16.dp),
        )
    }
}
