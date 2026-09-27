package dev.sonora.spotify

import dev.sonora.spotify.SpotifyRef.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the listener pasted.
 *
 * Pure and offline on purpose: this is the only place the import can tell a listener their link was
 * wrong, and every shape below is something a real paste has arrived as — a link copied from a
 * browser, one forwarded in a message, a `spotify:` URI off the share sheet, a bare id.
 */
class SpotifyLinkTest {

    @Test
    fun `a web link is read for its id and kind`() {
        val ref = SpotifyLink.parse("https://open.spotify.com/playlist/0rhj3YhzLUz8zqg1wQX7nf")

        assertEquals(SpotifyRef("0rhj3YhzLUz8zqg1wQX7nf", Kind.PLAYLIST), ref)
    }

    @Test
    fun `an album is told apart from a playlist`() {
        val ref = SpotifyLink.parse("https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3")

        assertEquals(Kind.ALBUM, ref?.kind)
    }

    /** A track link has no importable playlist behind it, so it is not a thing this can read. */
    @Test
    fun `a track link is not a playlist`() {
        assertNull(SpotifyLink.parse("https://open.spotify.com/track/0rhj3YhzLUz8zqg1wQX7nf"))
    }

    @Test
    fun `a share sheet URI is read`() {
        val ref = SpotifyLink.parse("spotify:playlist:0rhj3YhzLUz8zqg1wQX7nf?si=abc123")

        assertEquals(SpotifyRef("0rhj3YhzLUz8zqg1wQX7nf", Kind.PLAYLIST), ref)
    }

    @Test
    fun `a bare id is read as a kind this cannot know`() {
        val ref = SpotifyLink.parse("0rhj3YhzLUz8zqg1wQX7nf")

        assertEquals(SpotifyRef("0rhj3YhzLUz8zqg1wQX7nf", Kind.UNKNOWN), ref)
    }

    /**
     * A scheme-stripped link is what some share sheets produce, and the rest of it is a perfectly
     * good link — the paste should not be rejected for a prefix the listener never typed.
     */
    @Test
    fun `a link with the scheme stripped is still a link`() {
        val ref = SpotifyLink.parse("open.spotify.com/playlist/0rhj3YhzLUz8zqg1wQX7nf")

        assertEquals(SpotifyRef("0rhj3YhzLUz8zqg1wQX7nf", Kind.PLAYLIST), ref)
    }

    @Test
    fun `a localised path is read`() {
        val ref = SpotifyLink.parse("https://open.spotify.com/intl-de/album/1DFixLWuPkv3KT3TnV35m3")

        assertEquals(Kind.ALBUM, ref?.kind)
    }

    @Test
    fun `something that is not a Spotify link is refused`() {
        assertNull(SpotifyLink.parse("https://example.com/playlist/0rhj3YhzLUz8zqg1wQX7nf"))
        assertNull(SpotifyLink.parse("just some words"))
        assertNull(SpotifyLink.parse(""))
        assertNull(SpotifyLink.parse("spotify:playlist:tooshort"))
    }
}
