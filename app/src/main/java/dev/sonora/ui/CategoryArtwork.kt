package dev.sonora.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import dev.sonora.R

/**
 * The picture shipped with a category, if it has one.
 *
 * The ten moods have theirs in the app rather than fetched: a grid whose pictures arrive from the
 * network is a grid that starts as ten flat rectangles on every launch and fills in over a minute,
 * and a page that is *about* browsing should look like a page of pictures the moment it opens. The
 * file is a playlist cover, so it is the one thing on the tile that says what is in the category.
 *
 * Keyed by the category's own name because that is the only thing a category has that is stable —
 * the browse id and params beside it are YouTube's, and a tile that lost its picture because
 * somebody reordered a list upstream would be worse than a tile with no picture at all.
 */
@Composable
internal fun rememberCategoryArtwork(title: String): Painter? {
    val res = when (title) {
        "Chill" -> R.drawable.category_chill
        "Commute" -> R.drawable.category_commute
        "Energize" -> R.drawable.category_energize
        "Focus" -> R.drawable.category_focus
        "Gaming" -> R.drawable.category_gaming
        "Party" -> R.drawable.category_party
        "Romance" -> R.drawable.category_romance
        "Sad" -> R.drawable.category_sad
        "Sleep" -> R.drawable.category_sleep
        "Workout" -> R.drawable.category_workout
        else -> null
    }
    return res?.let { painterResource(it) }
}
