package dev.sonora.backend

import dev.sonora.ytm.YtmCategory
import dev.sonora.ytm.YtmPlaylistRef

/**
 * YouTube Music's own shelves, as the app holds them.
 *
 * A separate value rather than two flows because the two halves are one idea — a grid of
 * categories, and what the one that was pressed contains — and a screen that drew the first while
 * the second was still in flight would show an empty shelf under a row of buttons, which reads as
 * that category being empty rather than as it not having answered yet.
 */
data class Shelves(
    val categories: List<YtmCategory> = emptyList(),
    val playlists: Map<String, List<YtmPlaylistRef>> = emptyMap(),
    val loading: Boolean = false,
    /** The category whose playlists are on screen, or null when none has been asked for. */
    val chosen: YtmCategory? = null,
) {
    val isEmpty: Boolean get() = categories.isEmpty() && playlists.isEmpty() && !loading
}
