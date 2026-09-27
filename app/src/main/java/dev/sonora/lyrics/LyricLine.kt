package dev.sonora.lyrics

/**
 * One word of a line, with the stretch of the song it is sung over.
 *
 * Whole words, never syllables. A highlight that runs across "e" and "nough" separately reads as a
 * stutter, and a provider that splits them writes them that way.
 */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * One synced line.
 *
 * [text] empty is an instrumental stretch rather than a missing one: LRC files mark those with a
 * bare timestamp, and treating the timestamp as the start of nothing is what lets a gap be drawn as
 * a gap.
 *
 * [words] is empty for the providers that publish no word timing, and a line then highlights whole.
 * See [isWordSynced].
 *
 * [endMs] is the line's own end where one is known, which is the difference between a line that is
 * still being sung and one that has finished and is being held on screen.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val endMs: Long? = null,
) {
    val isGap: Boolean get() = text.isBlank()

    val isWordSynced: Boolean get() = words.isNotEmpty()

    /**
     * Where each word sits in [text], as character ranges.
     *
     * Walked forwards from where the last word ended rather than searched from the start, so a word
     * that appears twice in the line lines up with its own occurrence. A sweep that jumped to the
     * first "the" every time would light up the wrong one.
     */
    val wordSpans: List<IntRange> by lazy(LazyThreadSafetyMode.NONE) {
        var offset = 0
        words.map { word ->
            val start = text.indexOf(word.text, offset).takeIf { it >= 0 } ?: offset
            val end = (start + word.text.length).coerceAtMost(text.length)
            offset = end
            start until end
        }
    }

    /**
     * How much of the line has been sung at [positionMs], from 0 to 1.
     *
     * Per line for a line-synced source, and per character within the current word for a word-synced
     * one, so the sweep is smooth either way. A line with no known end falls back to the next line's
     * start, which is what a slowly sung line otherwise has to use.
     */
    fun sungFraction(positionMs: Long, nextStartMs: Long?): Float {
        if (words.isEmpty()) {
            val end = endMs ?: nextStartMs ?: return 0f
            if (end <= timeMs) return if (positionMs >= end) 1f else 0f
            return ((positionMs - timeMs).toFloat() / (end - timeMs)).coerceIn(0f, 1f)
        }

        val first = words.first().startMs
        val last = words.last().endMs
        if (positionMs <= first) return 0f
        if (positionMs >= last) return 1f

        for (word in words) {
            if (positionMs < word.startMs) break
            if (positionMs > word.endMs) continue
            val span = (word.endMs - word.startMs).coerceAtLeast(1L)
            return ((positionMs - word.startMs).toFloat() / span).coerceIn(0f, 1f)
        }

        return 1f
    }

    /**
     * Which words are being sung at [positionMs], and how far into each one the playhead is.
     *
     * What the bloom is drawn from. A word is only lit while it is actually sounding, so a line of
     * quick syllables has no glow at all and a carried note lights up letter by letter.
     */
    fun growingWords(positionMs: Long): List<Pair<Int, Float>> = words
        .mapIndexedNotNull { index, word ->
            when {
                positionMs < word.startMs -> null
                positionMs > word.endMs -> index to 1f
                else -> index to ((positionMs - word.startMs).toFloat() / (word.endMs - word.startMs))
                    .coerceIn(0f, 1f)
            }
        }
}

/** The lyrics of one track, with what is known and when it arrived. */
data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    /** True while the fetch is still out; the pane shows a shimmer rather than a blank. */
    val loading: Boolean = false,
    /** Why there are no lines, when there are none and none are coming. */
    val reason: String? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}
