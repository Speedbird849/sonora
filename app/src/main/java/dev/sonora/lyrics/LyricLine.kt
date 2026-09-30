package dev.sonora.lyrics

/**
 * One word of a line, with the stretch of the song it is sung over.
 *
 * Syllables from word-synced providers are merged into whole words on the way in, so [startMs] is
 * the first syllable's start and [endMs] the last one's end. Whole words are what the sweep needs —
 * a highlight that ran across "e" and "nough" separately reads as a stutter.
 */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * Which side of the panel a line is sung from.
 *
 * A duet is laid out on opposite sides so a call-and-response reads as two people rather than one
 * long verse. [Start] is the default and the only side a single-voice song ever uses.
 */
enum class LyricAlignment { Start, End }

/**
 * One synced line. [timeMs] is when it starts; a blank [text] is an instrumental stretch.
 *
 * [words] is populated by providers that carry word-level timing (BetterLyrics, LyricsPlus,
 * Apple Music TTML). LRCLIB has none, so a line from there highlights whole; see [isWordSynced].
 *
 * [sungUntilMs] is the line's own end where a line-synced provider states one, which is what lets
 * an interlude be told apart from a slowly sung line.
 *
 * [background] is the answering vocal — the "(ooh)" or echoed phrase a second voice sings over the
 * lead. It is a line in its own right, with its own stamp and words. Kept apart it draws underneath
 * the lead on its own clock.
 *
 * [timingSource] is for display-only translation: drive its sweep and bloom from the original vocal.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val sungUntilMs: Long? = null,
    val background: LyricLine? = null,
    val alignment: LyricAlignment = LyricAlignment.Start,
    val timingSource: LyricLine? = null,
) {
    /** Secondary constructor allowing [endMs] parameter for backward compatibility. */
    constructor(
        timeMs: Long,
        text: String,
        words: List<LyricWord> = emptyList(),
        endMs: Long?,
    ) : this(
        timeMs = timeMs,
        text = text,
        words = words,
        sungUntilMs = endMs,
    )

    val isGap: Boolean get() = text.isBlank()

    val isWordSynced: Boolean get() = words.isNotEmpty()

    /**
     * Where each of [words] sits in [text], as character ranges.
     *
     * Walked from where the last one ended rather than searched from the start, so a word repeated
     * in the line lines up with its own occurrence.
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
     * Whether anything on this line is off the floor at [positionMs].
     */
    fun isLifted(positionMs: Long): Boolean {
        val first = words.firstOrNull() ?: return false
        if (positionMs <= first.startMs) return false
        return positionMs < words.last().endMs + RISE_MS || isGrowing(positionMs)
    }

    /**
     * How far the word covering [positionMs] has lifted, 0..1.
     *
     * Words rise as they land and settle back once they are past. Up from the word's own start and
     * down from its own end, both over [RISE_MS].
     */
    fun wordLift(index: Int, positionMs: Long): Float {
        val word = words.getOrNull(index) ?: return 0f
        val rising = ((positionMs - word.startMs) / RISE_MS).coerceIn(0f, 1f)
        val falling = (1f - (positionMs - word.endMs) / RISE_MS).coerceIn(0f, 1f)
        return smooth(minOf(rising, falling))
    }

    /**
     * Whether anything actually told us when the singing stops, rather than only when it starts.
     */
    val hasKnownEnd: Boolean get() = words.isNotEmpty() || sungUntilMs != null

    /**
     * When the last word finishes — or the line's own end where the provider gave one, or [timeMs]
     * when nothing did.
     */
    val endMs: Long
        get() {
            val lead = words.lastOrNull()?.endMs ?: sungUntilMs ?: timeMs
            return maxOf(lead, background?.endMs ?: lead)
        }

    /**
     * How far through the line the singing has got, as a fractional index into [text] (0..text.length).
     * The sweep reveals up to this character.
     *
     * Within a word it interpolates across that word's span. Whitespace between two words is
     * credited to the gap between them, filling as the singer moves on.
     */
    fun revealedChars(positionMs: Long): Float {
        timingSource?.let { source ->
            if (source.text.isEmpty()) return 0f
            return (source.revealedChars(positionMs) / source.text.length)
                .coerceIn(0f, 1f) * text.length
        }
        if (words.isEmpty()) return if (positionMs >= timeMs) text.length.toFloat() else 0f
        var offset = 0
        words.forEachIndexed { index, word ->
            val start = text.indexOf(word.text, offset).takeIf { it >= 0 } ?: offset
            val end = (start + word.text.length).coerceAtMost(text.length)
            if (positionMs < word.startMs) return start.toFloat()
            if (positionMs < word.endMs) {
                val span = (word.endMs - word.startMs).coerceAtLeast(1L)
                val through = (positionMs - word.startMs).toFloat() / span
                return start + through * (end - start)
            }
            val next = words.getOrNull(index + 1)
            if (next != null && positionMs < next.startMs) {
                val gapStart = text.indexOf(next.text, end).takeIf { it >= 0 } ?: end
                val pause = (next.startMs - word.endMs).coerceAtLeast(1L)
                val through = (positionMs - word.endMs).toFloat() / pause
                return end + through * (gapStart - end)
            }
            offset = end
        }
        return text.length.toFloat()
    }

    /**
     * How much of a word's lift is left at [positionMs] — 1 while it is being sung, easing to 0
     * over [RISE_MS] once it is past.
     */
    fun wordFall(index: Int, positionMs: Long): Float {
        val word = words.getOrNull(index) ?: return 0f
        return smooth((1f - (positionMs - word.endMs) / RISE_MS).coerceIn(0f, 1f))
    }

    /**
     * The words held long enough to be worth animating letter by letter.
     */
    val growingWords: List<GrowingWord> by lazy(LazyThreadSafetyMode.NONE) {
        words.mapIndexedNotNull { index, word ->
            if (word.canGrow()) GrowingWord(index, word) else null
        }
    }

    /** The letter-by-letter treatment for word [index], where it has earned one. */
    fun growingAt(index: Int): GrowingWord? =
        growingWords.firstOrNull { it.index == index }

    /** Whether any word on this line is mid-flight at [positionMs]. */
    fun isGrowing(positionMs: Long): Boolean = growingWords.any {
        positionMs >= it.startMs && positionMs <= it.restsAtMs
    }
}

/**
 * One word held long enough to be animated a letter at a time.
 *
 * Every letter runs the same three-beat move — swell up and forward, hold, then settle back to the
 * small lift every sung word carries. Each letter starts [GROW_STAGGER] of the word's own length
 * after the one before it, so movement travels along the word.
 */
class GrowingWord internal constructor(
    val index: Int,
    word: LyricWord,
) {
    val startMs: Long = word.startMs
    val endMs: Long = word.endMs

    private val chars: Int = word.text.length
    private val scalePeak = FloatArray(chars)
    private val shiftPeak = FloatArray(chars)
    private val risePeak = FloatArray(chars)
    private val bloomPeak = FloatArray(chars)

    /** When the last letter has finished moving and is just sitting lifted. */
    val restsAtMs: Long

    init {
        val held = (endMs - startMs).coerceAtLeast(1L).toFloat()
        val earned = ((held - GROW_RAMP_MIN_MS) / (GROW_RAMP_MAX_MS - GROW_RAMP_MIN_MS))
            .coerceIn(0f, 1f)
            .let { it * it * it }
        val decay = decayRate(chars, held)
        val bloomPace = minOf(GROW_BLOOM_PACE_MAX, held / GROW_BLOOM_PACE_MS)
        val bloomSpread = when {
            chars <= 3 -> GROW_BLOOM_SHORT
            chars >= 6 -> GROW_BLOOM_LONG
            else -> 1f
        }
        val base = if (chars <= 3) GROW_BASE_SHORT else GROW_BASE_LONG
        val liftPace = (held / GROW_LIFT_PACE_MS).coerceIn(GROW_LIFT_FLOOR, 1f)

        for (i in 0 until chars) {
            val place = if (chars > 1) i.toFloat() / (chars - 1) else 0f
            val reach = earned * (1f - place * decay)
            val scale = 1f + base + reach * GROW_SCALE_RANGE
            scalePeak[i] = scale * GROW_SCALE_TRIM
            bloomPeak[i] = (GROW_BLOOM_FLOOR + reach * GROW_BLOOM_RANGE) * bloomPace * bloomSpread
            risePeak[i] = ((scale - 1f) / GROW_SCALE_CEILING) * liftPace
            val centre = (i + 0.5f) / chars
            shiftPeak[i] = (centre - 0.5f) * 2f * (scale - 1f) * GROW_SHIFT_EM * GROW_SCALE_TRIM
        }

        val last = (chars - 1).coerceAtLeast(0) * GROW_STAGGER + GROW_SPAN
        restsAtMs = startMs + (held * last).toLong()
    }

    /**
     * Where letter [charIndex] has got to at [positionMs], written into [into].
     */
    fun sampleInto(charIndex: Int, positionMs: Long, into: CharGrowth) {
        val span = (endMs - startMs).coerceAtLeast(1L).toFloat()
        val elapsed = positionMs - startMs - charIndex * span * GROW_STAGGER
        val phase = (elapsed / (span * GROW_SPAN)).coerceIn(0f, 1f)
        val peakScale = scalePeak[charIndex]
        when {
            phase < GROW_IN -> {
                val t = smooth(phase / GROW_IN)
                into.scale = 1f + (peakScale - 1f) * t
                into.shift = shiftPeak[charIndex] * t
                into.rise = risePeak[charIndex] * t
                into.bloom = bloomPeak[charIndex] * t
            }
            phase < GROW_HOLD -> {
                into.scale = peakScale
                into.shift = shiftPeak[charIndex]
                into.rise = risePeak[charIndex]
                into.bloom = bloomPeak[charIndex]
            }
            phase < GROW_OUT -> {
                val t = smooth((phase - GROW_HOLD) / (GROW_OUT - GROW_HOLD))
                into.scale = peakScale + (1f - peakScale) * t
                into.shift = shiftPeak[charIndex] * (1f - t)
                into.rise = risePeak[charIndex] + (GROW_REST - risePeak[charIndex]) * t
                into.bloom = bloomPeak[charIndex] * (1f - t)
            }
            else -> {
                into.scale = 1f
                into.shift = 0f
                into.rise = GROW_REST
                into.bloom = 0f
            }
        }
    }
}

/** Where one letter of a [GrowingWord] is, filled in by [GrowingWord.sampleInto]. */
class CharGrowth {
    /** Swell, about the letter's own centre. 1 is the letter as laid out. */
    var scale: Float = 1f

    /** Lean away from the middle of the word, in ems. */
    var shift: Float = 0f

    /** Lift, in multiples of the ordinary sung-word rise. */
    var rise: Float = 0f

    /** Bloom, 0..1, at its own peak partway up rather than at the top. */
    var bloom: Float = 0f
}

/** How long a word takes to rise, and to settle back down once it is past. */
private const val RISE_MS = 700f

/** Ease in and out of the ends, so the lift has no corners on it. */
private fun smooth(fraction: Float) = fraction * fraction * (3f - 2f * fraction)

private fun LyricWord.canGrow(): Boolean {
    val length = text.length
    if (length == 0 || length > GROW_MAX_CHARS) return false
    if ('-' in text || text.any { it.isBlockScript() || it.isJoinedScript() }) return false
    val held = endMs - startMs
    return when {
        length == 1 -> held >= GROW_MIN_SOLO_MS
        length <= 3 -> held >= GROW_MIN_SHORT_MS + (length - 2) * GROW_SHORT_STEP_MS
        length == 4 -> held >= GROW_MIN_FOUR_MS
        else -> held >= GROW_MIN_LONG_MS && held >= length * GROW_MS_PER_CHAR
    }
}

private fun decayRate(length: Int, heldMs: Float): Float {
    val long = length > GROW_DECAY_LONG_CHARS
    val quick = heldMs < GROW_DECAY_QUICK_MS
    if (!long && !quick) return 0f
    var strength = 0f
    if (long) {
        strength += minOf((length - GROW_DECAY_LONG_CHARS) / 5f, 1f) * GROW_DECAY_LONG
    }
    if (quick) {
        val short = maxOf(0f, 1f - (heldMs - GROW_DECAY_QUICK_FLOOR_MS) / 400f)
        strength += short * if (length > 3) GROW_DECAY_QUICK else GROW_DECAY_QUICK_TINY
    }
    return minOf(strength, GROW_DECAY_MAX)
}

private fun Char.isBlockScript(): Boolean =
    this in '一'..'鿿' || this in '぀'..'ゟ' ||
        this in '゠'..'ヿ' || this in '가'..'힯'

private fun Char.isJoinedScript(): Boolean =
    this in '֐'..'ࣿ'

private const val GROW_MAX_CHARS = 7
private const val GROW_MIN_SOLO_MS = 1_100L
private const val GROW_MIN_SHORT_MS = 1_360L
private const val GROW_SHORT_STEP_MS = 140L
private const val GROW_MIN_FOUR_MS = 1_050L
private const val GROW_MIN_LONG_MS = 900L
private const val GROW_MS_PER_CHAR = 200L

private const val GROW_DECAY_LONG_CHARS = 5
private const val GROW_DECAY_QUICK_MS = 1_200f
private const val GROW_DECAY_QUICK_FLOOR_MS = 800f
private const val GROW_DECAY_LONG = 0.4f
private const val GROW_DECAY_QUICK = 0.3f
private const val GROW_DECAY_QUICK_TINY = 0.1f
private const val GROW_DECAY_MAX = 0.7f

private const val GROW_STAGGER = 0.09f
private const val GROW_SPAN = 1.5f
private const val GROW_IN = 0.25f
private const val GROW_HOLD = 0.30f
private const val GROW_OUT = 0.75f
private const val GROW_REST = 1f
private const val GROW_RAMP_MIN_MS = 400f
private const val GROW_RAMP_MAX_MS = 3_000f

private const val GROW_BASE_SHORT = 0.05f
private const val GROW_BASE_LONG = 0.04f
private const val GROW_SCALE_RANGE = 0.08f
private const val GROW_SCALE_CEILING = 0.1f
private const val GROW_SCALE_TRIM = 0.98f
private const val GROW_SHIFT_EM = 25f / 34f
private const val GROW_BLOOM_FLOOR = 0.35f
private const val GROW_BLOOM_RANGE = 0.45f
private const val GROW_BLOOM_PACE_MS = 1_500f
private const val GROW_BLOOM_PACE_MAX = 1.1f
private const val GROW_BLOOM_SHORT = 0.85f
private const val GROW_BLOOM_LONG = 1.1f
private const val GROW_LIFT_PACE_MS = 2_000f
private const val GROW_LIFT_FLOOR = 0.3f

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
