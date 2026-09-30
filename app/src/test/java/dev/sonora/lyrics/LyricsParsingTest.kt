package dev.sonora.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParsingTest {

    @Test
    fun `TtmlLyrics parses syllables and merges them into whole words`() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:01.000" end="00:03.000">
                    <span begin="00:01.000" end="00:01.500">Hel</span><span begin="00:01.500" end="00:02.000">lo </span><span begin="00:02.000" end="00:02.800">world</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lines = TtmlLyrics.parse(ttml)
        val sungLines = lines.filter { !it.isGap }
        assertEquals(1, sungLines.size)
        val line = sungLines[0]
        assertEquals("Hello world", line.text)
        assertEquals(2, line.words.size)
        assertEquals("Hello", line.words[0].text)
        assertEquals(1_000L, line.words[0].startMs)
        assertEquals(2_000L, line.words[0].endMs)
        assertEquals("world", line.words[1].text)
        assertEquals(2_000L, line.words[1].startMs)
        assertEquals(2_800L, line.words[1].endMs)
    }

    @Test
    fun `TtmlLyrics extracts background vocals into line background`() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:01.000" end="00:04.000">
                    <span begin="00:01.000" end="00:02.000">Lead vocal </span>
                    <span ttm:role="x-bg" begin="00:02.500" end="00:03.500">(echo)</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lines = TtmlLyrics.parse(ttml).filter { !it.isGap }
        assertEquals(1, lines.size)
        val lead = lines[0]
        assertEquals("Lead vocal", lead.text)
        assertNotNull(lead.background)
        assertEquals("(echo)", lead.background!!.text)
        assertEquals(2_500L, lead.background!!.timeMs)
    }

    @Test
    fun `TtmlLyrics computes duet alignments for alternating agents`() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <ttm:agent xml:id="v1" type="person" />
                  <ttm:agent xml:id="v2" type="person" />
                </metadata>
              </head>
              <body>
                <div>
                  <p begin="00:01.000" end="00:02.000" ttm:agent="v1">
                    <span begin="00:01.000" end="00:02.000">Voice one</span>
                  </p>
                  <p begin="00:02.500" end="00:03.500" ttm:agent="v2">
                    <span begin="00:02.500" end="00:03.500">Voice two</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lines = TtmlLyrics.parse(ttml).filter { !it.isGap }
        assertEquals(2, lines.size)
        assertEquals(LyricAlignment.Start, lines[0].alignment)
        assertEquals(LyricAlignment.End, lines[1].alignment)
    }

    @Test
    fun `EnhancedLrc parses word timestamps and decodes HTML entities`() {
        val lrc = """
            [00:10.50]<00:10.50>Don&#x27;t <00:11.00>stop <00:11.80>&amp; <00:12.00>listen
            [00:15.00] Next line
        """.trimIndent()

        val lines = EnhancedLrc.parse(lrc).filter { !it.isGap }
        assertTrue(lines.isNotEmpty())
        val line = lines[0]
        assertEquals("Don't stop & listen", line.text)
        assertEquals(4, line.words.size)
        assertEquals("Don't", line.words[0].text)
        assertEquals(10_500L, line.words[0].startMs)
        assertEquals(11_000L, line.words[0].endMs)
        assertEquals("&", line.words[2].text)
    }

    @Test
    fun `BackgroundVocals splits trailing bracket into background line`() {
        val lines = listOf(
            LyricLine(
                timeMs = 1_000,
                text = "Hold me now (Hold me now)",
                words = listOf(
                    LyricWord(1_000, 1_500, "Hold"),
                    LyricWord(1_500, 2_000, "me"),
                    LyricWord(2_000, 2_500, "now"),
                    LyricWord(2_600, 3_000, "(Hold"),
                    LyricWord(3_000, 3_500, "me"),
                    LyricWord(3_500, 4_000, "now)"),
                ),
            ),
        )

        val split = lines.withBackgroundVocals()
        assertEquals(1, split.size)
        val lead = split[0]
        assertEquals("Hold me now", lead.text)
        assertEquals(3, lead.words.size)
        assertNotNull(lead.background)
        assertEquals("(Hold me now)", lead.background!!.text)
        assertEquals(3, lead.background!!.words.size)
        assertEquals(2_600L, lead.background!!.timeMs)
    }

    @Test
    fun `LyricsQuery cleans packaging while preserving musical versions`() {
        assertEquals("Dracula", "Dracula (feat. JENNIE)".forLyricsSearch())
        assertEquals("Blinding Lights", "Blinding Lights [Official Music Video]".forLyricsSearch())
        assertEquals("Starboy", "Starboy ft. Daft Punk (Official Audio)".forLyricsSearch())
        // Version info preserved:
        assertEquals("Save Your Tears (Remix)", "Save Your Tears (Remix)".forLyricsSearch())
        assertEquals("Creep (Acoustic)", "Creep (Acoustic)".forLyricsSearch())

        assertEquals("The Weeknd", "The Weeknd - Topic".artistForLyricsSearch())
    }
}
