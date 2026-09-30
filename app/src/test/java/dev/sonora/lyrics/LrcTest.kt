package dev.sonora.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LRC reader.
 *
 * Worth testing because the two shapes it reads are written by other people: a database that serves
 * one and not the other, or serves the other with three-digit fractions, has to give the same lines
 * back or the sweep is drawn against the wrong clock.
 */
class LrcTest {

    @Test
    fun `a plain line gets its stamp and its words`() {
        val lines = Lrc.parse("[00:19.16] When you were here before")

        assertEquals(1, lines.size)
        assertEquals(19_160L, lines[0].timeMs)
        assertEquals("When you were here before", lines[0].text)
        assertFalse(lines[0].isWordSynced)
    }

    @Test
    fun `a word-stamped line keeps its own timing`() {
        val lines = Lrc.parse("[00:27.39]<00:27.39>I <00:27.54>been <00:27.74>tryna")

        assertEquals(1, lines.size)
        assertTrue(lines[0].isWordSynced)
        assertEquals("I been tryna", lines[0].text)
        assertEquals(27_390L, lines[0].words[0].startMs)
        assertEquals(27_540L, lines[0].words[0].endMs)
        assertEquals(27_740L, lines[0].words[2].startMs)
    }

    @Test
    fun `the last word of a line runs until the next line does`() {
        val lines = Lrc.parse("[00:01.00]<00:01.00>one <00:01.50>two\n[00:05.00] three")

        assertEquals(5_000L, lines[0].words[1].endMs)
    }

    @Test
    fun `the last word of the song is given a beat rather than nothing`() {
        val lines = Lrc.parse("[00:01.00]<00:01.00>one")

        assertTrue("a zero-length last word never lights up", lines[0].words[0].endMs > 1_000L)
    }

    @Test
    fun `a bare stamp is a gap, and the gap knows where it ends`() {
        val lines = Lrc.parse("[00:01.00] a word\n[00:04.00]\n[00:08.00] another")

        assertEquals(3, lines.size)
        assertTrue(lines[1].isGap)
        assertEquals(8_000L, lines[1].endMs)
    }

    @Test
    fun `three digit fractions are tenths of a second, not hundredths`() {
        val lines = Lrc.parse("[00:19.123] read this at nineteen seconds")

        // Read as hundredths it would land at 19.23s, nearly a quarter of a second late.
        assertEquals(19_120L, lines[0].timeMs)
    }

    @Test
    fun `lines come back in the order the file states them`() {
        val lines = Lrc.parse("[00:30.00] third\n[00:10.00] first\n[00:20.00] second")

        assertEquals(listOf("first", "second", "third"), lines.map { it.text })
    }

    @Test
    fun `the escaped characters a file uses are unescaped`() {
        val lines = Lrc.parse("[00:01.00] rock &amp; roll &lt;yes&gt;")

        assertEquals("rock & roll <yes>", lines[0].text)
    }

    @Test
    fun `a file with no stamps in it is not lyrics`() {
        assertTrue(Lrc.parse("Just some words\non some lines").isEmpty())
        assertTrue(Lrc.parse("").isEmpty())
    }

    @Test
    fun `a word spans the range it covers, so a repeated word lines up with its own`() {
        val line = LyricLine(
            timeMs = 0,
            text = "the the end",
            words = listOf(
                LyricWord(0, 100, "the"),
                LyricWord(100, 200, "the"),
                LyricWord(200, 300, "end"),
            ),
        )

        assertEquals(listOf(0..2, 4..6, 8..10), line.wordSpans)
    }

    @Test
    fun `the sweep runs across the line where there is no word timing`() {
        val line = LyricLine(timeMs = 1_000, text = "sung over five seconds", endMs = 6_000)

        assertEquals(0f, line.revealedChars(500), 0.001f)
        assertEquals(line.text.length.toFloat(), line.revealedChars(1_000), 0.001f)
    }

    @Test
    fun `nothing has been sung before the first word starts`() {
        val line = LyricLine(
            timeMs = 500,
            text = "one",
            words = listOf(LyricWord(500, 1_500, "one")),
        )

        assertEquals(0f, line.revealedChars(0), 0.001f)
    }
}
