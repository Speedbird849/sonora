package dev.sonora.spotify

import dev.sonora.ytm.YtmSearch
import dev.sonora.ytm.YtmTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One imported track and where it landed. [track] is null when nothing matched it. */
data class SpotifyMatch(val spotify: SpotifyTrack, val track: YtmTrack?)

/**
 * Turns a Spotify track list into YouTube Music tracks.
 *
 * Spotify serves no audio and Sonora does not use it for anything but names, so an import is
 * entirely a matching problem: find the same recording in a catalogue that will serve it. Tracks
 * that find nothing are returned rather than dropped — a playlist that quietly lost five tracks
 * would be a playlist that is not what was asked for.
 */
object SpotifyImporter {

    /**
     * Searches in flight at once. Past a handful the requests queue behind each other and the whole
     * import takes longer without finishing any sooner.
     */
    private const val MAX_PARALLEL = 4

    /**
     * Matches every track, reporting progress as answers land.
     *
     * An import of a hundred tracks is a visible wait, and a bar that sits at zero and then jumps
     * is the version of that wait people read as a hang. Results come back in playlist order
     * whatever order the searches finished in, because a reshuffled import is worse than a failed
     * one.
     */
    suspend fun match(
        tracks: List<SpotifyTrack>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SpotifyMatch> = withContext(Dispatchers.IO) {
        val limiter = Semaphore(MAX_PARALLEL)

        val matches = coroutineScope {
            tracks.map { track ->
                async { SpotifyMatch(track, limiter.withPermit { matchOne(track) }) }
            }.awaitAll()
        }

        onProgress(tracks.size, tracks.size)
        matches
    }

    /**
     * The one track, or null.
     *
     * Two queries rather than one, because a title that exactly matches a song is also an exact
     * match for the thousands of uploads of it, and the artist is what tells them apart. The second
     * drops the artist, for the case where the catalogue files a track under somebody else — a
     * composer, a featured act, a band that renamed itself.
     */
    private suspend fun matchOne(track: SpotifyTrack): YtmTrack? {
        val exact = "${track.title} ${track.artist}".trim()
        val loose = track.title.trim()

        for (query in listOf(exact, loose).distinct()) {
            val found = YtmSearch.search(query).firstOrNull { it.matches(track) }
            if (found != null) return found
        }
        return null
    }

    /**
     * Whether a catalogue result is the same recording as the Spotify track.
     *
     * Stricter than "the first result with this name", because that is the one failure a listener
     * cannot hear and cannot see: a cover, a remix or a sped-up upload carries the same strings as
     * the record it stands in for.
     *
     * Three checks, in the order they can rule a candidate out:
     *
     *  - **The title, with its packaging taken off.** Services disagree constantly about packaging
     *    — the film a song is from, the "Official Audio" tag, which featured singers get written
     *    into the title — and agree about the recording underneath.
     *  - **Version markers, which are *not* taken off.** "Remix" and "Radio Edit" mean a different
     *    take, so a title carrying one and a candidate without it are not the same recording. This
     *    is what stops a radio edit being answered with the seven-minute album cut.
     *  - **A shared artist credit.** A title is not an identity; "Riptide" is a song by a hundred
     *    people.
     */
    private fun YtmTrack.matches(spotify: SpotifyTrack): Boolean {
        val wanted = TitleParts.of(spotify.title)
        val got = TitleParts.of(title)
        if (wanted.words != got.words) return false
        if (wanted.versions != got.versions) return false
        if (!sharesArtist(artist, spotify.artist)) return false

        // A runtime only rules a candidate out when both sides know one. YouTube's search rows often
        // do not carry it, and treating an absent length as a mismatch would reject nearly
        // everything.
        val want = spotify.durationSec
        val have = durationSec
        if (want == null || have == null) return true
        return kotlin.math.abs(want - have) <= 2
    }

    /**
     * A title taken apart into the part that names the recording and the part that says which take.
     *
     * Two sets rather than one string, because the halves are judged differently: the plain words
     * have to agree, and the version markers have to agree *in both directions* — "Symmetry
     * (Remix)" is not "Symmetry", and neither is the other way round.
     */
    private data class TitleParts(val words: Set<String>, val versions: Set<String>) {

        companion object {
            fun of(raw: String): TitleParts {
                val words = mutableSetOf<String>()
                val versions = mutableSetOf<String>()

                for (part in splitKeepingBrackets(raw)) {
                    if (part.bracketed) {
                        VERSION.findAll(part.text)
                            .map { it.value.lowercase() }
                            .forEach(versions::add)
                    } else {
                        fold(part.text).split(' ')
                            .filter { it.isNotBlank() }
                            .forEach(words::add)
                    }
                }
                return TitleParts(words, versions)
            }

            private fun splitKeepingBrackets(raw: String): List<Part> {
                val parts = mutableListOf<Part>()
                val current = StringBuilder()
                var depth = 0
                var bracketed = false

                for (ch in raw) {
                    when {
                        ch == '(' || ch == '[' -> {
                            if (depth == 0) bracketed = true
                            depth++
                            current.append(ch)
                        }

                        ch == ')' || ch == ']' -> {
                            depth--
                            current.append(ch)
                            if (depth == 0) {
                                parts += Part(current.toString(), bracketed)
                                current.clear()
                                bracketed = false
                            }
                        }

                        // A bare dash separates a title from a subtitle, as in
                        // "All The Stars - From Black Panther".
                        depth == 0 && (ch == '-' || ch == '\u2013') -> {
                            parts += Part(current.toString(), false)
                            current.clear()
                        }

                        else -> current.append(ch)
                    }
                }
                if (current.isNotBlank()) parts += Part(current.toString(), bracketed)
                return parts
            }

            private data class Part(val text: String, val bracketed: Boolean)

            private val VERSION = Regex(
                "\\b(remix|remaster(ed)?|radio edit|edit|version|live|acoustic|instrumental|" +
                    "demo|cover|slowed|sped up|reverb|bootleg|mix)\\b",
                RegexOption.IGNORE_CASE,
            )
        }
    }

    /**
     * Whether the two credits name at least one artist in common.
     *
     * Every service picks its own separator — commas, "feat.", an ampersand, a bare "x" — so the
     * strings are split before comparing rather than after.
     */
    private fun sharesArtist(a: String, b: String): Boolean {
        val wanted = names(b)
        val have = names(a)
        return wanted.isNotEmpty() && have.isNotEmpty() && wanted.any { it in have }
    }

    private fun names(value: String): Set<String> =
        value.split(SEPARATORS)
            .map { fold(it).trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private val SEPARATORS = Regex(
        "\\s*(?:,|&|\\bx\\b|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)\\s*",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Case, accents and punctuation folded away before comparing.
     *
     * Every service spells the same release differently — "Beyoncé - CRAZY IN LOVE" against
     * "Beyonce Crazy in Love" — and none of that is a disagreement about the recording.
     */
    private fun fold(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), " ")
}
