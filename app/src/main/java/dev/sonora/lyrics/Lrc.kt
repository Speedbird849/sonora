package dev.sonora.lyrics

/**
 * Reads the two LRC shapes a lyrics database actually serves.
 *
 * **Plain** — one stamp per line, no timing inside it:
 *
 * ```
 * [00:19.16] When you were here before
 * ```
 *
 * **Enhanced**, with a stamp in front of every word:
 *
 * ```
 * [00:27.39]<00:27.39>I <00:27.54>been <00:27.74>tryna
 * ```
 *
 * Both are read by the same pass rather than by two parsers, because the enhanced form *is* the
 * plain form with more stamps on it, and a second parser would be a second thing to keep in step
 * when a line is neither.
 */
object Lrc {

    private val LINE = Regex("""^\[(\d{1,4}):(\d{2})[.:](\d{2,3})](.*)$""")
    private val WORD = Regex("""<(\d{1,4}):(\d{2})[.:](\d{2,3})>([^<]*)""")

    /**
     * How long the last word of the song is given when there is no next line to end it.
     *
     * A beat rather than nothing: with a zero-length word its sweep never runs, so the last word of
     * every song is the one word on the screen that never lights up.
     */
    private const val TAIL_MS = 2_000L

    /** Every timed line in [lrc], in the order the file puts them. */
    fun parse(lrc: String): List<LyricLine> {
        val rows = lrc.lineSequence()
            .mapNotNull { line -> LINE.matchEntire(line.trim())?.let { toRow(it) } }
            .sortedBy { it.timeMs }
            .toList()

        if (rows.isEmpty()) return emptyList()

        return rows.mapIndexedNotNull { index, row ->
            val nextStart = rows.getOrNull(index + 1)?.timeMs
            val line = row.toLine(nextStart)
            line
        }
    }

    private data class Row(
        val timeMs: Long,
        val words: List<MatchResult>,
        val plain: String,
    )

    private fun toRow(match: MatchResult): Row = Row(
        timeMs = stamp(match.groupValues[1], match.groupValues[2], match.groupValues[3]),
        words = WORD.findAll(match.groupValues[4]).toList(),
        plain = match.groupValues[4].trim(),
    )

    private fun Row.toLine(nextStartMs: Long?): LyricLine? {
        if (words.isEmpty()) {
            val text = decode(plain)
            return if (text.isBlank()) LyricLine(timeMs, "", endMs = nextStartMs) else LyricLine(timeMs, text)
        }

        // A word runs until the next one starts; the last one until the next line does.
        val lineEnd = nextStartMs ?: (stampOf(words.last()) + TAIL_MS)

        val parsed = words.mapIndexedNotNull { index, match ->
            val text = decode(match.groupValues[4]).trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val start = stamp(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            val end = words.getOrNull(index + 1)?.let(::stampOf) ?: lineEnd
            LyricWord(start, end.coerceAtLeast(start), text)
        }
        if (parsed.isEmpty()) return null

        return LyricLine(
            timeMs = minOf(timeMs, parsed.first().startMs),
            text = parsed.joinToString(" ") { it.text },
            words = parsed,
            endMs = lineEnd,
        )
    }

    private fun stampOf(match: MatchResult): Long =
        stamp(match.groupValues[1], match.groupValues[2], match.groupValues[3])

    private fun stamp(minutes: String, seconds: String, fraction: String): Long {
        val hundredths = when (fraction.length) {
            1 -> fraction.toLongOrNull() ?: 0L
            2 -> fraction.toLongOrNull() ?: 0L
            // Three digits are tenths of a second, which is what some files write; reading those as
            // hundredths puts every word a tenth of a second early.
            else -> (fraction.toLongOrNull() ?: 0L) / 10
        }
        return (minutes.toLongOrNull() ?: 0L) * 60_000L +
            (seconds.toLongOrNull() ?: 0L) * 1_000L +
            hundredths * 10L
    }

    /**
     * The few entities LRC files escape, and nothing else.
     *
     * Anything not listed here is left exactly as written, because a database that is handed `&`
     * in a chorus is handing it to us on purpose.
     */
    private fun decode(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .trim()
}
