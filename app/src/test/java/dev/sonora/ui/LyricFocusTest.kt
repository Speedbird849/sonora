package dev.sonora.ui

import dev.sonora.lyrics.LyricLine
import dev.sonora.lyrics.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricFocusTest {

    @Test
    fun `empty lines return empty focus`() {
        assertEquals(emptyList<Int>(), activeLyricRows(emptyList(), 1_000L))
    }

    @Test
    fun `before first line returns empty focus`() {
        val lines = listOf(LyricLine(timeMs = 1_000L, text = "Hello"))
        assertEquals(emptyList<Int>(), activeLyricRows(lines, 500L))
    }

    @Test
    fun `active line during playback is returned`() {
        val lines = listOf(
            LyricLine(timeMs = 1_000L, text = "Hello", sungUntilMs = 3_000L),
            LyricLine(timeMs = 4_000L, text = "World", sungUntilMs = 6_000L),
        )
        assertEquals(listOf(0), activeLyricRows(lines, 2_000L))
        assertEquals(listOf(1), activeLyricRows(lines, 4_500L))
    }

    @Test
    fun `overlapping vocals keep both rows active`() {
        val lines = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "Lead singing long note",
                words = listOf(LyricWord(1_000L, 5_000L, "Lead")),
            ),
            LyricLine(
                timeMs = 3_000L,
                text = "Backing response starts early",
                words = listOf(LyricWord(3_000L, 6_000L, "Backing")),
            ),
        )
        // At 3500ms, line 0 is still singing (ends at 5000ms), and line 1 has started (3000ms)
        assertEquals(listOf(0, 1), activeLyricRows(lines, 3_500L))
    }
}
