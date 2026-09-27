package dev.sonora.backend

/**
 * What is playing and what follows it.
 *
 * Held as one value rather than a list and an index, because the two are always wrong together:
 * a list on its own does not say which of its entries is the one sounding, and an index on its own
 * means nothing without the list. Publishing them together makes it impossible for a reader to
 * pair one with the other's value from a moment ago.
 */
data class UpNext(
    val queue: List<LibraryTrack> = emptyList(),
    /** Where in [queue] the sounding track is. -1 when nothing is playing. */
    val index: Int = -1,
) {
    /** The one sounding, or null before playback starts. */
    val current: LibraryTrack? get() = queue.getOrNull(index)

    /** Everything after it, in the order it will be played. */
    val following: List<LibraryTrack> get() = if (index < 0) emptyList() else queue.drop(index + 1)

    companion object {
        val Empty = UpNext()
    }
}
