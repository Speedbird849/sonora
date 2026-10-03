package dev.sonora.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLineTest {

    @Test
    fun `revealedChars for plain line is 0 before stamp and full length at or after stamp`() {
        val line = LyricLine(timeMs = 1_000, text = "Hello world")
        assertEquals(0f, line.revealedChars(500), 0.001f)
        assertEquals(11f, line.revealedChars(1_000), 0.001f)
        assertEquals(11f, line.revealedChars(2_000), 0.001f)
    }

    @Test
    fun `revealedChars sweeps across words and gaps cleanly`() {
        // "I love you"
        //  0123456789
        //  words: [0..1] "I" (1000..1200), [2..6] "love" (1500..2000), [7..10] "you" (2100..2500)
        val line = LyricLine(
            timeMs = 1_000,
            text = "I love you",
            words = listOf(
                LyricWord(1_000, 1_200, "I"),
                LyricWord(1_500, 2_000, "love"),
                LyricWord(2_100, 2_500, "you"),
            ),
        )

        // Before first word
        assertEquals(0f, line.revealedChars(500), 0.001f)

        // Halfway through "I" (len 1, start 0): 0 + 0.5 * 1 = 0.5
        assertEquals(0.5f, line.revealedChars(1_100), 0.001f)

        // Exactly at end of "I": revealedChars should be 1.0 (start of gap to next word)
        assertEquals(1f, line.revealedChars(1_200), 0.001f)

        // Midway between "I" (end 1200) and "love" (start 1500):
        // text is "I love you". gap start is 2 ("love").
        // Gap pause = 300ms. At 1350 (halfway), through = 0.5.
        // revealed = 1 + 0.5 * (2 - 1) = 1.5
        assertEquals(1.5f, line.revealedChars(1_350), 0.001f)

        // Halfway through "love" (start index 2, length 4, span 500ms from 1500 to 2000):
        // At 1750, through = 0.5. revealed = 2 + 0.5 * 4 = 4.0
        assertEquals(4.0f, line.revealedChars(1_750), 0.001f)

        // After last word completes
        assertEquals(10f, line.revealedChars(3_000), 0.001f)
    }

    @Test
    fun `revealedChars scales correctly with translation timingSource`() {
        val original = LyricLine(
            timeMs = 1_000,
            text = "Hello",
            words = listOf(LyricWord(1_000, 2_000, "Hello")),
        )
        val translation = LyricLine(
            timeMs = 1_000,
            text = "Bonjour tout le monde", // 21 chars
            timingSource = original,
        )

        // At 1500, original is 50% revealed (2.5 / 5.0)
        // translation revealed should be 0.5 * 21 = 10.5
        assertEquals(10.5f, translation.revealedChars(1_500), 0.01f)
    }

    @Test
    fun `wordLift rises from word start and falls after word end`() {
        // A quick word that does not qualify for GrowingWord
        val line = LyricLine(
            timeMs = 1_000,
            text = "quick",
            words = listOf(LyricWord(1_000, 1_400, "quick")),
        )

        // Before start: lift is 0
        assertEquals(0f, line.wordLift(0, 900), 0.001f)
        assertFalse(line.isLifted(900))

        // Rising during the word
        assertTrue(line.isLifted(1_200))
        assertTrue(line.wordLift(0, 1_200) > 0f)

        // Falling after 1400ms over 700ms (settles at 2100ms)
        assertTrue(line.isLifted(1_750))
        assertTrue(line.wordLift(0, 1_750) in 0.1f..0.9f)
        assertEquals(0f, line.wordLift(0, 2_200), 0.001f)
        assertFalse(line.isLifted(2_200))
    }

    @Test
    fun `isLifted stays true for growing words until restsAtMs`() {
        val line = LyricLine(
            timeMs = 1_000,
            text = "one",
            words = listOf(LyricWord(1_000, 3_000, "one")),
        )
        val growing = line.growingAt(0)
        assertNotNull(growing)
        val restsAt = growing!!.restsAtMs

        assertTrue(line.isLifted(restsAt - 50))
        assertFalse(line.isLifted(restsAt + 50))
    }

    @Test
    fun `GrowingWord qualifies on held notes and samples growth phases`() {
        // "sky" held for 2500ms qualifies for letter-by-letter treatment
        val line = LyricLine(
            timeMs = 1_000,
            text = "sky",
            words = listOf(LyricWord(1_000, 3_500, "sky")),
        )

        assertEquals(1, line.growingWords.size)
        val growing = line.growingAt(0)
        assertNotNull(growing)

        val growth = CharGrowth()
        // Sample at start: scale is 1, rise is 0, bloom is 0
        growing!!.sampleInto(0, 1_000, growth)
        assertEquals(1f, growth.scale, 0.01f)
        assertEquals(0f, growth.rise, 0.01f)

        // Sample at peak hold (e.g. 1500ms): scale > 1, rise > 0, bloom > 0
        growing.sampleInto(0, 1_500, growth)
        assertTrue("scale should swell above 1", growth.scale > 1.02f)
        assertTrue("rise should be positive", growth.rise > 0.05f)
        assertTrue("bloom should be positive", growth.bloom > 0.1f)
    }

    @Test
    fun `short or hyphenated words do not trigger GrowingWord`() {
        val line = LyricLine(
            timeMs = 1_000,
            text = "rap-id note",
            words = listOf(
                LyricWord(1_000, 1_200, "rap-id"), // hyphenated
                LyricWord(1_200, 1_400, "note"),   // 4 chars held only 200ms
            ),
        )

        assertTrue(line.growingWords.isEmpty())
        assertNull(line.growingAt(0))
        assertNull(line.growingAt(1))
    }

    @Test
    fun `instrumental gaps are inserted for long intro and breaks between lines`() {
        val lines = listOf(
            LyricLine(timeMs = 5_000, text = "Line 1", words = listOf(LyricWord(5_000, 6_000, "Line 1"))),
            LyricLine(timeMs = 12_000, text = "Line 2", words = listOf(LyricWord(12_000, 13_000, "Line 2"))),
        )

        val withGaps = lines.withInstrumentalGaps()

        // 1. Intro gap inserted at 0L because line 1 starts at 5_000 >= MIN_GAP_MS (4000)
        assertEquals(0L, withGaps[0].timeMs)
        assertTrue(withGaps[0].isGap)

        // 2. Line 1
        assertEquals("Line 1", withGaps[1].text)

        // 3. Instrumental break from line 1 end (6_000) to line 2 start (12_000), silence = 6_000 >= 4_000
        assertEquals(6_000L, withGaps[2].timeMs)
        assertTrue(withGaps[2].isGap)

        // 4. Line 2
        assertEquals("Line 2", withGaps[3].text)
    }

    @Test
    fun `endMs considers backing vocals when they extend past the lead vocal`() {
        val backing = LyricLine(
            timeMs = 2_000,
            text = "(echo)",
            words = listOf(LyricWord(2_000, 4_500, "(echo)")),
        )
        val lead = LyricLine(
            timeMs = 1_000,
            text = "Lead vocal",
            words = listOf(LyricWord(1_000, 3_000, "Lead"), LyricWord(3_000, 3_500, "vocal")),
            background = backing,
            alignment = LyricAlignment.Start,
        )

        assertEquals(4_500L, lead.endMs)
    }
}
