package dev.sonora.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that decides whether a read of a stream is sent as a range.
 *
 * Worth pinning down on its own because the failure it prevents is silent from the outside: an
 * un-ranged request is not served slowly, it is refused, and the refusal arrives as a 403 on a URL
 * that is correct and not yet stale. The URL and the client identity are both right, so the obvious
 * guesses — a throttled address, a bad header — are all wrong.
 */
class RangeHeaderTest {

    @Test
    fun aWholeStreamAsksForNoRange() {
        // "The whole file" is the one request these URLs refuse. Only a genuine partial read — or a
        // read from an offset with no stated length — carries a range.
        assertNull(rangeHeader(position = 0L, length = C.LENGTH_UNSET.toLong()))
    }

    @Test
    fun aPartialReadAtTheStartIsRanged() {
        assertEquals("bytes=0-98303", rangeHeader(position = 0L, length = 98_304L))
    }

    @Test
    fun aSeekIsRangedFromWhereItStarts() {
        assertEquals("bytes=1048576-1146880", rangeHeader(position = 1_048_576L, length = 98_305L))
    }

    @Test
    fun aReadToTheEndStopsAtTheOffset() {
        // An open-ended read still has to be a range, or it is the refused whole-file request. The
        // end is left off rather than guessed: a length of "however much is left" has no number to
        // put there, and an open range is exactly what a streaming read wants anyway.
        assertEquals("bytes=4096-", rangeHeader(position = 4_096L, length = C.LENGTH_UNSET.toLong()))
    }

    @Test
    fun theEndIsInclusive() {
        // One byte at the start is bytes=0-0. Reading "to index 0 inclusive" and getting a header
        // that ends one past it is the kind of off-by-one that costs a seek, not a crash.
        assertEquals("bytes=0-0", rangeHeader(position = 0L, length = 1L))
    }
}
