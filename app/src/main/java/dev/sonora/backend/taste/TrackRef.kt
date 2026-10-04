package dev.sonora.backend.taste

import java.text.Normalizer
import kotlinx.serialization.Serializable

/**
 * One song, whatever it came from.
 *
 * The engine keys on [key], a normalized `artist|title`, rather than on [LibraryTrack.key] — which
 * is a file path for a download and `ytm:<id>` for a stream. Those two are the *same song* and the
 * whole point of the taste model is that listening to one teaches it about the other; keying on the
 * source would split every song in two and halve everything learned about it.
 *
 * [ytmId] and [localPath] are the two ways back to something playable, and both are kept when they
 * are known: Autoplay prefers the local lossless copy and falls back to the stream.
 */
@Serializable
data class TrackRef(
    val key: String,
    val title: String,
    val artist: String,
    val artistKey: String,
    val tags: List<String> = emptyList(),
    val durationMs: Long? = null,
    val ytmId: String? = null,
    val localPath: String? = null,
) {
    companion object {

        /** Builds a ref, normalizing the identity and the artist key from the display strings. */
        fun of(
            title: String,
            artist: String?,
            tags: List<String> = emptyList(),
            durationMs: Long? = null,
            ytmId: String? = null,
            localPath: String? = null,
        ): TrackRef {
            val cleanArtist = artist.orEmpty().trim()
            return TrackRef(
                key = TrackIdentity.key(cleanArtist, title),
                title = title.trim(),
                artist = cleanArtist,
                artistKey = TrackIdentity.artistKey(cleanArtist),
                tags = tags,
                durationMs = durationMs,
                ytmId = ytmId,
                localPath = localPath,
            )
        }
    }
}

/**
 * The rules that make two spellings of one song the same key.
 *
 * Pure and string-only so it can be tested exhaustively without a device. The transformations are
 * deliberately lossy in the direction that matters: a remaster, a deluxe edition and a "feat."
 * credit are the same recording for taste purposes, while a remix or a cover is a different
 * artist's name or a different title and survives.
 */
object TrackIdentity {

    /** Bracketed decorations: `(Remastered 2011)`, `[Deluxe Edition]`, `(feat. X)`. */
    private val BRACKETED = Regex("[\\[(][^)\\]]*[)\\]]")

    /** feat. / ft. / featuring, and everything after it in the title. */
    private val FEATURING = Regex("""\b(?:feat|ft|featuring)\b\.?.*$""")

    /** A trailing ` - Remastered ...` / ` - 2011 Remaster` / ` - Live ...` decoration. */
    private val TRAILING_DECORATION = Regex(
        """\s*[-–—]\s*(?:\d{4}\s+)?(?:digital\s+)?(?:re)?master(?:ed|ing)?.*$|""" +
            """\s*[-–—]\s*live(?:\s+.*)?$|""" +
            """\s*[-–—]\s*\d{4}\s+remaster.*$""",
    )

    /** Anything that is not a letter, a digit or whitespace. */
    private val PUNCTUATION = Regex("[^\\p{L}\\p{N}\\s]")

    private val WHITESPACE = Regex("\\s+")

    /** The identity key: normalized artist and title, in that order. */
    fun key(artist: String?, title: String): String = artistKey(artist) + "|" + titleKey(title)

    /** The normalized artist alone, for the per-artist maps. */
    fun artistKey(artist: String?): String = normalize(artist.orEmpty())

    /** The normalized title alone. */
    fun titleKey(title: String): String = normalize(title)

    private fun normalize(raw: String): String {
        var text = fold(raw.lowercase())
        text = text.replace(BRACKETED, " ")
        text = text.replace(FEATURING, " ")
        text = text.replace(TRAILING_DECORATION, " ")
        text = text.replace(PUNCTUATION, " ")
        return text.replace(WHITESPACE, " ").trim()
    }

    /**
     * Decomposes accents and drops the combining marks, so `café` and `cafe` agree.
     *
     * NFD then strip only the mark category, which folds Latin diacritics without touching scripts
     * that have no decomposition — Japanese titles keep every character.
     */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
