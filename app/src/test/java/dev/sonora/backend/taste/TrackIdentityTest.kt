package dev.sonora.backend.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityTest {

    @Test
    fun `a remaster is the same song`() {
        assertEquals(
            TrackIdentity.titleKey("Song"),
            TrackIdentity.titleKey("Song (Remastered 2011)"),
        )
        assertEquals(
            TrackIdentity.titleKey("Song"),
            TrackIdentity.titleKey("Song - Remastered"),
        )
        assertEquals(
            TrackIdentity.titleKey("Song"),
            TrackIdentity.titleKey("Song - 2011 Remaster"),
        )
    }

    @Test
    fun `a deluxe edition and a live take are the same song`() {
        assertEquals(
            TrackIdentity.titleKey("Song"),
            TrackIdentity.titleKey("Song [Deluxe Edition]"),
        )
        assertEquals(
            TrackIdentity.titleKey("Song"),
            TrackIdentity.titleKey("Song (Live at Wembley)"),
        )
    }

    @Test
    fun `a featuring credit is dropped from the title`() {
        assertEquals(
            TrackIdentity.titleKey("Song"),
            TrackIdentity.titleKey("Song feat. Someone"),
        )
        assertEquals(
            TrackIdentity.titleKey("Song"),
            TrackIdentity.titleKey("Song (feat. Someone)"),
        )
        assertEquals(
            TrackIdentity.titleKey("Song"),
            TrackIdentity.titleKey("Song Ft. Someone Else"),
        )
    }

    @Test
    fun `casing punctuation and unicode are folded`() {
        assertEquals("hello world", TrackIdentity.titleKey("  Hello,   World! "))
        assertEquals("cafe", TrackIdentity.titleKey("Café"))
        assertEquals("bjork", TrackIdentity.artistKey("BJÖRK"))
        // Non-Latin scripts have no decomposition, so nothing is lost.
        assertEquals("夜に駆ける", TrackIdentity.titleKey("夜に駆ける"))
    }

    @Test
    fun `a streamed copy and a downloaded copy of one song share a key`() {
        val streamed = TrackRef.of(
            title = "Never Gonna Give You Up (Remastered)",
            artist = "Rick Astley",
            ytmId = "dQw4w9WgXcQ",
        )
        val downloaded = TrackRef.of(
            title = "Never Gonna Give You Up",
            artist = "rick astley",
            localPath = "/sdcard/Music/rick-astley-never-gonna-give-you-up.flac",
        )

        assertEquals(streamed.key, downloaded.key)
    }

    @Test
    fun `different songs stay different`() {
        assertNotEquals(
            TrackIdentity.key("Rick Astley", "Never Gonna Give You Up"),
            TrackIdentity.key("Rick Astley", "Together Forever"),
        )
        assertNotEquals(
            TrackIdentity.key("Artist A", "Same Title"),
            TrackIdentity.key("Artist B", "Same Title"),
        )
    }

    @Test
    fun `a title with a number keeps it`() {
        assertEquals("halo 3", TrackIdentity.titleKey("Halo 3"))
        assertTrue(TrackIdentity.titleKey("Halo 3") != TrackIdentity.titleKey("Halo"))
    }
}
