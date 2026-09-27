package dev.sonora.ui

import dev.sonora.ytm.YtmCategory
import dev.sonora.ytm.YtmPlaylistRef

/**
 * A page reached off one of YouTube Music's own shelves.
 *
 * One type for both so a stack of them can be pushed and popped without the caller having to know
 * which is on top — a category can open a playlist, a playlist cannot open a category, and the only
 * thing that needs knowing is the order.
 */
sealed interface ShelfPage {
    /** A mood or a genre, and the playlists behind it. */
    data class Category(val category: YtmCategory) : ShelfPage

    /** Somebody's playlist, and its tracks. */
    data class Playlist(val playlist: YtmPlaylistRef) : ShelfPage
}
