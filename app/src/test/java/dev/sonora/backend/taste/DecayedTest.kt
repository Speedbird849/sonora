package dev.sonora.backend.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecayedTest {

    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun `a value halved is exact at one half-life`() {
        val d = Decayed(value = 8.0, t = 0L)

        assertEquals(4.0, d.at(day, day), 1e-9)
    }

    @Test
    fun `two half-lives is a quarter`() {
        val d = Decayed(value = 8.0, t = 0L)

        assertEquals(2.0, d.at(2 * day, day), 1e-9)
    }

    @Test
    fun `no elapsed time is no decay`() {
        val d = Decayed(value = 3.5, t = 1_000L)

        assertEquals(3.5, d.at(1_000L, day), 1e-9)
    }

    @Test
    fun `add decays before adding`() {
        // 8 at t=0, decayed one half-life to 4, plus 1 = 5 at t=day.
        val d = Decayed(value = 8.0, t = 0L).add(1.0, now = day, halfLifeMs = day)

        assertEquals(5.0, d.value, 1e-9)
        assertEquals(day, d.t)
    }

    @Test
    fun `a backwards clock does not gain`() {
        val d = Decayed(value = 2.0, t = 10 * day)

        assertEquals(2.0, d.at(day, day), 1e-9)
    }

    @Test
    fun `an untouched value is zero`() {
        assertTrue(Decayed().at(day, day) == 0.0)
    }
}
