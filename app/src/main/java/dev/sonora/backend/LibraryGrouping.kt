package dev.sonora.backend

/**
 * Albums and artists, derived from the flat track list.
 *
 * Pure, so the awkward cases — a track with no album tag, an album whose tracks have different
 * artists — are decided once here and can be tested without a device.
 */
object LibraryGrouping {

    const val UNKNOWN_ALBUM = "Unknown album"
    const val UNKNOWN_ARTIST = "Unknown artist"

    /** Reported for an album whose tracks disagree on artist, which is what a compilation looks like. */
    const val VARIOUS_ARTISTS = "Various artists"

    data class Album(val name: String, val artist: String, val tracks: List<LibraryTrack>)

    data class Artist(val name: String, val tracks: List<LibraryTrack>)

    /**
     * Grouped by album name alone, not by album *and* artist: a compilation has a different artist
     * on every track, so including the artist in the key would shatter it into one album per track.
     */
    fun albums(tracks: List<LibraryTrack>): List<Album> =
        tracks.groupBy { it.album.orUnknown(UNKNOWN_ALBUM) }
            .map { (name, group) ->
                Album(
                    name = name,
                    artist = sharedArtist(group),
                    // Track numbers are not in the tags being read, so filename order is the closest
                    // available approximation of the album's own order.
                    tracks = group.sortedBy { sortKey(it) },
                )
            }
            .sortedBy { it.name.lowercase() }

    fun artists(tracks: List<LibraryTrack>): List<Artist> =
        tracks.groupBy { it.artist.orUnknown(UNKNOWN_ARTIST) }
            .map { (name, group) -> Artist(name, group.sortedBy { it.title.lowercase() }) }
            .sortedBy { it.name.lowercase() }

    /**
     * Albums ordered by when their newest track arrived.
     *
     * The library sorts by title, which is right for browsing and useless for "what did I just
     * get". This is the ordering Home needs, and it is why it is derived from the files rather
     * than stored: a download's arrival time is already on disk.
     */
    fun recentAlbums(tracks: List<LibraryTrack>, limit: Int): List<Album> =
        albums(tracks)
            .sortedByDescending { album -> album.tracks.maxOf { arrivedAt(it) } }
            .take(limit)

    /**
     * What an album's tracks are ordered by.
     *
     * Filename order for a downloaded track, and title for a streaming one, which has no file to
     * read. Mixed albums are then ordered by whichever each row happens to have, which is the same
     * approximation the file-only version was already making from untagged rips.
     */
    private fun sortKey(track: LibraryTrack): String =
        track.file?.name?.lowercase() ?: track.title.lowercase()

    /**
     * When a track arrived, for "recently added" ordering.
     *
     * A file's own modification time, and for a streaming track the moment it was kept — so a shelf
     * of both orders by when each thing turned up rather than putting every stream, which has no
     * file, at the top because the clock was read while the list was being built.
     */
    private fun arrivedAt(track: LibraryTrack): Long =
        track.file?.lastModified() ?: track.arrivedAt ?: 0L

    private fun sharedArtist(tracks: List<LibraryTrack>): String {
        val artists = tracks.map { it.artist.orUnknown(UNKNOWN_ARTIST) }.distinct()
        return if (artists.size == 1) artists.single() else VARIOUS_ARTISTS
    }

    private fun String?.orUnknown(fallback: String): String =
        this?.trim().orEmpty().ifEmpty { fallback }
}
