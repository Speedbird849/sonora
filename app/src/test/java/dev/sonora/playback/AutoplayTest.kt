package dev.sonora.playback

import dev.sonora.ytm.YtmTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The autoplay rules, kept apart from the player so they can be argued about without an emulator.
 */
class AutoplayTest {

    @Test
    fun `the queue is topped up only when the sounding track is the last one`() {
        assertTrue(shouldTopUp(true, false, currentIndex = 4, itemCount = 5, loadInProgress = false))
        assertFalse(shouldTopUp(true, false, currentIndex = 3, itemCount = 5, loadInProgress = false))
    }

    @Test
    fun `autoplay off means no topping up, however short the queue is`() {
        assertFalse(shouldTopUp(false, false, currentIndex = 9, itemCount = 10, loadInProgress = false))
    }

    @Test
    fun `a load already running is not joined by a second one`() {
        assertFalse(shouldTopUp(true, false, currentIndex = 9, itemCount = 10, loadInProgress = true))
    }

    @Test
    fun `repeat-all has its own plan for what comes next`() {
        assertFalse(shouldTopUp(true, repeatAll = true, currentIndex = 9, itemCount = 10, loadInProgress = false))
    }

    @Test
    fun `nothing is topped up before playback has started`() {
        assertFalse(shouldTopUp(true, false, currentIndex = -1, itemCount = 0, loadInProgress = false))
    }

    @Test
    fun `what is already queued is not queued again`() {
        val queued = listOf(track("a"), track("b"))
        val candidates = listOf(track("b"), track("c"), track("d"))

        assertEquals(listOf("c", "d"), extend(queued, candidates, recent = emptyList()).map { it.videoId })
    }

    @Test
    fun `what just finished is not suggested straight back`() {
        val candidates = listOf(track("a"), track("b"), track("c"))

        assertEquals(
            listOf("b", "c"),
            extend(emptyList(), candidates, recent = listOf("a")).map { it.videoId },
        )
    }

    @Test
    fun `the cap is the cap, however many candidates there are`() {
        val candidates = (1..40).map { track("v$it") }

        assertEquals(MAX_QUEUED_AUTOPLAY, extend(emptyList(), candidates, recent = emptyList()).size)
    }

    @Test
    fun `a duplicate inside the candidates is only added once`() {
        val candidates = listOf(track("a"), track("a"), track("b"))

        assertEquals(listOf("a", "b"), extend(emptyList(), candidates, recent = emptyList()).map { it.videoId })
    }

    @Test
    fun `the order is the network's, not the map's`() {
        val candidates = listOf(track("z"), track("y"), track("x"))

        assertEquals(
            listOf("z", "y", "x"),
            extend(emptyList(), candidates, recent = emptyList()).map { it.videoId },
        )
    }

    @Test
    fun `a track of no length is not worth queueing`() {
        val candidates = listOf(track("a", duration = 0), track("b"))

        assertEquals(listOf("b"), extend(emptyList(), candidates, recent = emptyList()).map { it.videoId })
    }

    @Test
    fun `the seed is the artist, because a song's own title comes back as that song`() {
        assertEquals("Tame Impala", seedQueryFor(track("a", artist = "Tame Impala")))
    }

    @Test
    fun `a track with no artist is seeded by its title instead`() {
        assertEquals("Title a", seedQueryFor(track("a", artist = "")))
    }

    @Test
    fun `a track with neither is not a search`() {
        assertNull(seedQueryFor(track("a", artist = "", title = "")))
        assertNull(seedQueryFor(null))
    }

    @Test
    fun `an empty answer goes back one step rather than ending the queue`() {
        val history = listOf(track("a", artist = "First"), track("b", artist = "Second"))

        assertEquals("Second", nextSeed(listOf("First"), history)?.artist)
    }

    @Test
    fun `the same artist is not asked twice`() {
        val history = listOf(track("a", artist = "Same"), track("b", artist = "Same"))

        assertNull(nextSeed(listOf("Same"), history))
    }

    @Test
    fun `nothing is left to try once every artist has been asked`() {
        assertNull(nextSeed(listOf("Only"), listOf(track("a", artist = "Only"))))
    }

    private fun track(
        id: String,
        artist: String = "Some Artist",
        title: String = "Title $id",
        duration: Int? = 200,
    ) = YtmTrack(
        videoId = id,
        title = title,
        artist = artist,
        durationSec = duration,
    )
}
