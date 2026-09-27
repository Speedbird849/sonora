package dev.sonora.backend

import dev.sonora.ytm.YtmBrowse

/** What a browse id turned out to be, which is decided by whoever asked for it. */
enum class PageKind { ARTIST, ALBUM }

/**
 * One artist or album as YouTube Music describes it.
 *
 * The kind is carried alongside rather than read off the page because the caller is the only one
 * that knows: an artist's page lists singles and albums, an album's page is its own tracks, and
 * the response does not say which of the two it is.
 */
data class BrowsedPage(
    val kind: PageKind = PageKind.ARTIST,
    val page: YtmBrowse.Page = YtmBrowse.Page(),
) {
    /** True once there is something to show, so a screen can tell "loading" from "empty". */
    val loaded: Boolean get() = !page.isEmpty
}

/**
 * An album or an artist somebody wants to look at, by name and by browse id.
 *
 * The id is what makes the page real and the name is what makes it findable, and they arrive from
 * different places: a search result has both, the player's artist line has both, and a library row
 * for a downloaded file has only a name. So the id is optional and a name alone still opens
 * something — the tracks already on the device, or a search for the rest.
 */
data class PageRequest(
    val name: String,
    val browseId: String? = null,
    val kind: PageKind,
)
