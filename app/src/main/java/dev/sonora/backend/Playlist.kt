package dev.sonora.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A user-made playlist.
 *
 * Tracks are held as [LibraryTrack.key] values rather than positions or generated ids. A key is
 * resolved against the library to find the track, so one that no longer resolves — a file deleted
 * outside the app, say — can be shown as missing and dropped, instead of being a dangling index into
 * a list that has since shifted.
 *
 * Order is the list order, so it carries meaning — a playlist is a sequence, not a set.
 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    @SerialName("trackPaths") val trackKeys: List<String> = emptyList(),
)
