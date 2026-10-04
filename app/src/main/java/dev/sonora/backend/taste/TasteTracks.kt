package dev.sonora.backend.taste

import dev.sonora.backend.LibraryTrack
import dev.sonora.ytm.YtmTrack
import java.io.File

/**
 * Bridges the player's [LibraryTrack] to the engine's [TrackRef] and back.
 *
 * The outbound direction collapses the source (a file path or a video id) into the normalized
 * identity, keeping both handles so Autoplay can prefer a local lossless copy. The inbound direction
 * turns a pick back into something the player can open.
 *
 * The album is carried as the track's tag set. The catalogue the app reads carries no genre, and an
 * album is the one grouping it does carry — enough for the tag-affinity term to mean something
 * without inventing data or making a second request.
 */
fun LibraryTrack.toTrackRef(): TrackRef = TrackRef.of(
    title = title,
    artist = artist,
    tags = listOfNotNull(album?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }),
    durationMs = remote?.durationSec?.let { it * 1000L },
    ytmId = remote?.videoId,
    localPath = file?.absolutePath,
)

fun YtmTrack.toTrackRef(): TrackRef = TrackRef.of(
    title = title,
    artist = artist,
    tags = listOfNotNull(album?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }),
    durationMs = durationSec?.let { it * 1000L },
    ytmId = videoId,
)

/**
 * A pick as something playable, or null when nothing can open it.
 *
 * A local file wins over the stream: the lossless copy is the reason the peer network is here, and
 * playing it costs no network at all. The stream is the fallback for a pick the model knows only
 * from YouTube Music.
 */
fun TrackRef.toLibraryTrack(): LibraryTrack? {
    val local = localPath?.let(::File)?.takeIf { it.isFile }
    if (local != null) {
        return LibraryTrack(
            file = local,
            title = title,
            artist = artist,
            album = tags.firstOrNull(),
            size = local.length(),
        )
    }

    val videoId = ytmId ?: return null
    return LibraryTrack.fromRemote(
        YtmTrack(
            videoId = videoId,
            title = title,
            artist = artist,
            album = null,
            durationSec = durationMs?.let { (it / 1000L).toInt() },
        ),
    )
}
