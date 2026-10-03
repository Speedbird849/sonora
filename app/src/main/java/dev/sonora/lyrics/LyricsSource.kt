package dev.sonora.lyrics

/**
 * Lyric databases queried by [LyricsRepository].
 */
enum class LyricsSource(
    val label: String,
    val wordSynced: Boolean,
) {
    BINI_LYRICS(label = "BiniLyrics", wordSynced = true),
    BETTER_LYRICS(label = "BetterLyrics", wordSynced = true),
    LYRICS_PLUS(label = "LyricsPlus", wordSynced = true),
    SIMP_MUSIC(label = "SimpMusic", wordSynced = true),
    LRCLIB(label = "LRCLIB", wordSynced = false),
}
