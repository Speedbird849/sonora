package dev.sonora.spotify

import dev.sonora.ytm.YtmTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as the same recording.
 *
 * Pure and offline on purpose: this is where a wrong import is decided, and a wrong import is the one
 * failure a listener cannot hear and cannot see — a cover or a remix lands under the right title and
 * plays the wrong audio. Every case below is a real disagreement between how Spotify and YouTube
 * write the same track, taken from a live playlist.
 */
class SpotifyMatchTest {

    private fun youtube(title: String, artist: String, seconds: Int? = null) =
        YtmTrack(videoId = "v", title = title, artist = artist, durationSec = seconds)

    private fun spotify(title: String, artist: String, seconds: Int? = null) =
        SpotifyTrack(id = "s", title = title, artist = artist, album = null, durationSec = seconds, isExplicit = false)

    private fun assertMatch(
        spotify: SpotifyTrack,
        candidate: YtmTrack,
        why: String = "",
    ) {
        val track = SpotifyImporter
        assertTrue("should have matched $why: '${spotify.title}' vs '${candidate.title}'", track.matches(spotify, candidate))
    }

    private fun assertNoMatch(spotify: SpotifyTrack, candidate: YtmTrack) {
        assertFalse(
            "should not have matched: '${spotify.title}' vs '${candidate.title}'",
            SpotifyImporter.matches(spotify, candidate),
        )
    }

    @Test
    fun `the same title and artist match`() {
        assertMatch(
            spotify("Riptide", "Vance Joy"),
            youtube("Riptide", "Vance Joy"),
        )
    }

    /**
     * Spotify writes the featured singers into the title and YouTube does not. Both are the same
     * recording, and a rule that says otherwise rejects the right answer.
     */
    @Test
    fun `credit in the title is not part of the title's identity`() {
        assertMatch(
            spotify("One Of The Girls (with JENNIE, Lily Rose Depp)", "The Weeknd, JENNIE, Lily-Rose Depp"),
            youtube("One Of The Girls", "The Weeknd, JENNIE & Lily Rose Depp"),
        )
        assertMatch(
            spotify("Baby (feat. Ludacris)", "Justin Bieber, Ludacris"),
            youtube("Baby (feat. Ludacris)", "Justin Bieber"),
        )
    }

    @Test
    fun `case accents and punctuation are not differences`() {
        assertMatch(
            spotify("Beyoncé - Crazy in Love", "Beyoncé"),
            youtube("beyonce crazy in love", "Beyoncé"),
        )
    }

    /**
     * A subtitle after a dash is packaging — the film, the season, the soundtrack it came from. One
     * service writing it into the title is not a disagreement about the audio.
     */
    @Test
    fun `a dash subtitle is packaging`() {
        assertMatch(
            spotify("All The Stars (with SZA) - From \"Black Panther: The Album\"", "Kendrick Lamar, SZA"),
            youtube("All The Stars (From \"Black Panther: The Album\")", "Kendrick Lamar, SZA"),
        )
    }

    /**
     * The check that matters most. A radio edit and the album cut share a title and an artist and
     * differ only in length, and substituting one for the other is exactly the failure this exists
     * to prevent.
     */
    @Test
    fun `a remix is not the recording it is a remix of`() {
        assertNoMatch(
            spotify("Let It Happen - Radio Edit", "Tame Impala"),
            youtube("Let It Happen", "Tame Impala"),
        )
    }

    @Test
    fun `the same version marker on both sides is a match`() {
        assertMatch(
            spotify("Symmetry (feat. Karan Aujla) - Remix", "Ed Sheeran, Karan Aujla"),
            youtube("Symmetry [Remix] (feat. Karan Aujla)", "Ed Sheeran"),
        )
    }

    @Test
    fun `a version marker on only one side is not a match`() {
        assertNoMatch(
            spotify("Symmetry - Remix", "Ed Sheeran"),
            youtube("Symmetry", "Ed Sheeran"),
        )
    }

    /**
     * A title is not an identity. "Riptide" is a song by a hundred people, and the artist is what
     * tells the recordings apart.
     */
    @Test
    fun `a different artist is not a match`() {
        assertNoMatch(
            spotify("Riptide", "Vance Joy"),
            youtube("Riptide", "Some Cover Band"),
        )
    }

    @Test
    fun `a shared credit is enough when the credits differ in order`() {
        assertMatch(
            spotify("Some Way", "NAV, The Weeknd"),
            youtube("Some Way (feat. The Weeknd)", "NAV"),
        )
    }

    /**
     * A duration only rules a candidate out when both sides know one — YouTube's search rows often
     * do not carry it, and treating an absent length as a mismatch would reject nearly everything.
     */
    @Test
    fun `an unknown duration is not a mismatch`() {
        assertMatch(
            spotify("Riptide", "Vance Joy", seconds = 204),
            youtube("Riptide", "Vance Joy", seconds = null),
        )
        assertMatch(
            spotify("Riptide", "Vance Joy", seconds = null),
            youtube("Riptide", "Vance Joy", seconds = 205),
        )
    }

    @Test
    fun `a duration far off is a different take`() {
        assertNoMatch(
            spotify("Some Way", "NAV, The Weeknd", seconds = 180),
            youtube("Some Way (feat. The Weeknd)", "NAV", seconds = 240),
        )
    }
}
