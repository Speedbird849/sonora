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
     * Internal rather than private so the rule can be tested on its own, with the real
     * disagreements between the two services written out as cases — see `SpotifyMatchTest`. It is the
     * one piece of judgement in the import and the one that cannot be checked by running it.
     */
    internal fun matches(spotify: SpotifyTrack, candidate: YtmTrack): Boolean =
        candidate.matches(spotify)

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
     *    — the film a song is from, the artist in a `Beyoncé - Crazy in Love` title, which featured
     *    singers get written into it — and agree about the recording underneath.
     *  - **Version markers, which are *not* taken off.** "Remix" and "Radio Edit" mean a different
     *    take, so a title carrying one and a candidate without it are not the same recording. This
     *    is what stops a radio edit being answered with the seven-minute album cut.
     *  - **A shared artist credit.** A title is not an identity; "Riptide" is a song by a hundred
     *    people.
     */
    private fun YtmTrack.matches(spotify: SpotifyTrack): Boolean {
        val wanted = TitleParts.of(spotify.title)
        val got = TitleParts.of(title)

        // The words must contain each other, not be equal. One service writes a bare "Baby" where the
        // other writes "Baby (feat. Ludacris)", and a bare title is the same recording written with
        // less of it — while the reverse, a candidate carrying words the asked-for title does not, is
        // a different song that happens to start the same way.
        if (wanted.words.isEmpty() || !got.words.containsAll(wanted.words)) return false

        // Version markers must be identical, in both directions: a remix is not the recording it is a
        // remix of, and neither is the other way round.
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
     * have to contain each other, and the version markers have to be identical *in both
     * directions* — "Symmetry (Remix)" is not "Symmetry", and neither is the other way round.
     */
    private data class TitleParts(val words: Set<String>, val versions: Set<String>) {

        companion object {
            fun of(raw: String): TitleParts {
                val words = mutableSetOf<String>()
                val versions = mutableSetOf<String>()

                for (part in split(raw)) {
                    val markers = VERSION.findAll(part.text)
                        .map { it.value.lowercase() }
                        .toList()
                    versions += markers

                    // A bracketed part with no version marker in it is a credit — "(with JENNIE,
                    // Lily Rose)" — and its words are dropped rather than compared. The artist check
                    // below is what decides whether that credit was honest, and comparing the names
                    // here as well would reject a candidate for being well described.
                    if (part.bracketed && markers.isEmpty()) continue

                    // Everything after a bare dash is a subtitle: the film, the season, the artist
                    // in a "Beyoncé - Crazy in Love" title. Its words go, but a version marker in it
                    // stays, because "Symmetry - Remix" is a different take and the dash is only
                    // carrying the word "Remix".
                    if (part.subtitle) continue

                    // A marker that explained itself is not also a word of the title, so "Symmetry
                    // [Remix]" and "Symmetry" reduce to the same words and differ only in versions.
                    fold(VERSION.replace(part.text, " ")).split(' ')
                        .filter { it.isNotBlank() && it !in CREDIT_WORDS }
                        .forEach(words::add)
                }
                return TitleParts(words, versions)
            }

            /**
             * Words that carry credit rather than identity.
             *
             * Dropped wherever they appear so that "One Of The Girls (with JENNIE)" and
             * "One Of The Girls" reduce to the same words.
             */
            private val CREDIT_WORDS = setOf("with", "feat", "ft", "featuring", "and", "x", "vs")

            private val VERSION = Regex(
                "\\b(remix|remaster(ed)?|radio edit|edit|version|live|acoustic|instrumental|" +
                    "demo|cover|slowed|sped up|reverb|bootleg|mix)\\b",
                RegexOption.IGNORE_CASE,
            )

            /** One run of a title: some text, and how it was written. */
            private data class Part(val text: String, val bracketed: Boolean, val subtitle: Boolean)

            /**
             * Splits a title into runs, keeping brackets intact.
             *
             * Two boundaries matter and neither can be found with a regular expression over the
             * whole string: a bracket's contents are one unit, and a *spaced* dash starts a subtitle.
             * The dash has to be spaced or "Spider-Man" would come apart in the middle, and the text
             * before a bracket has to be emitted separately or "One Of The Girls " is swallowed by
             * the credit that follows it and the title loses the words that name it.
             */
            private fun split(raw: String): List<Part> {
                val parts = mutableListOf<Part>()
                val bracket = StringBuilder()
                val lead = StringBuilder()
                var depth = 0
                var afterDash = false

                for (ch in raw) {
                    when {
                        ch == '(' || ch == '[' -> {
                            // The text before the bracket is a run of its own, so it is emitted
                            // here rather than being carried into the bracket — carrying it in would
                            // make "One Of The Girls (with JENNIE)" one part, and the credit rule
                            // below would then drop the title along with the credit.
                            if (depth == 0 && lead.isNotBlank()) {
                                parts += Part(lead.toString(), false, afterDash)
                                lead.clear()
                                afterDash = false
                            }
                            depth++
                            bracket.append(ch)
                        }

                        ch == ')' || ch == ']' -> {
                            depth--
                            bracket.append(ch)
                            if (depth == 0) {
                                if (bracket.isNotBlank()) parts += Part(bracket.toString(), true, afterDash)
                                bracket.clear()
                            }
                        }

                        depth == 0 && (ch == '-' || ch == '–') && lead.lastOrNull()?.isWhitespace() == true -> {
                            if (lead.isNotBlank()) parts += Part(lead.toString(), false, afterDash)
                            lead.clear()
                            afterDash = true
                        }

                        depth == 0 -> lead.append(ch)

                        else -> bracket.append(ch)
                    }
                }

                if (lead.isNotBlank()) parts += Part(lead.toString(), false, afterDash)
                if (bracket.isNotBlank()) parts += Part(bracket.toString(), true, afterDash)
                return parts
            }
        }
    }

    /**
     * Whether the two credits name at least one artist in common.
     *
     * Every service picks its own separator — commas, "feat.", an ampersand, a bare "x" — so the
     * strings are split before comparing rather than after. A shared name is enough: credits are
     * routinely written in a different order, with a different subset, or with a featured act
     * promoted to a co-headline, and all three are the same recording.
     *
     * Compared on the first word of each name rather than the whole of it, because "The Weeknd" and
     * "Weeknd, The" are one artist written two ways, and an exact comparison would call them two.
     */
    private fun sharesArtist(a: String, b: String): Boolean {
        val wanted = names(b)
        val have = names(a)
        return wanted.isNotEmpty() && have.isNotEmpty() && wanted.any { want ->
            have.any { it == want || it.startsWith("$want ") || want.startsWith("$it ") }
        }
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
