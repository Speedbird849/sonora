package dev.sonora.backend.taste

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaySignalTest {

    @Test
    fun `a completed play is plus one`() {
        assertEquals(1.0, PlaySignal.signal(0.8, 200_000, liked = false), 1e-9)
        assertEquals(1.0, PlaySignal.signal(1.0, 200_000, liked = false), 1e-9)
    }

    @Test
    fun `more than half is a partial`() {
        assertEquals(0.4, PlaySignal.signal(0.5, 100_000, liked = false), 1e-9)
        assertEquals(0.4, PlaySignal.signal(0.79, 150_000, liked = false), 1e-9)
    }

    @Test
    fun `the middle band scores nothing`() {
        assertEquals(0.0, PlaySignal.signal(0.25, 60_000, liked = false), 1e-9)
        assertEquals(0.0, PlaySignal.signal(0.49, 90_000, liked = false), 1e-9)
    }

    @Test
    fun `an early skip is punished hardest`() {
        assertEquals(-0.8, PlaySignal.signal(0.1, 20_000, liked = false), 1e-9)
        assertEquals(-0.8, PlaySignal.signal(null, 10_000, liked = false), 1e-9)
    }

    @Test
    fun `a little of a song after a long time is abandoned rather than skipped`() {
        assertEquals(-0.5, PlaySignal.signal(0.1, 45_000, liked = false), 1e-9)
    }

    @Test
    fun `a like lifts any play`() {
        assertEquals(1.5, PlaySignal.signal(0.1, 5_000, liked = true), 1e-9)
        assertEquals(1.5, PlaySignal.signal(1.0, 200_000, liked = true), 1e-9)
    }
}
