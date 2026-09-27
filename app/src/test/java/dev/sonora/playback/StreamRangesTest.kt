package dev.sonora.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules that decide how a googlevideo stream is read.
 *
 * Worth pinning down on their own because every one of them was wrong in a way the logs could not
 * explain. A URL that is correct, headers that are correct, and a 403 with nothing to act on is the
 * shape all three of these failures had.
 */
class StreamRangesTest {

    // -- the length the URL declares ------------------------------------------------------------

    @Test
    fun theStreamLengthComesOffTheUrl() {
        // The player asks for a read before anything has been fetched, so the only length available
        // is the one the URL carries. Without it there is no way to know where a range ends.
        assertEquals(7_879_682L, StreamRanges.declaredLength("https://rr1.googlevideo.com/videoplayback?clen=7879682&itag=251"))
    }

    @Test
    fun aLengthIsReadWhereverItSitsInTheQuery() {
        assertEquals(42L, StreamRanges.declaredLength("https://x.googlevideo.com/v?itag=251&clen=42&c=IOS"))
    }

    @Test
    fun aUrlThatDeclaresNoLengthIsNotGuessedAt() {
        // Anything but a googlevideo stream, and a shape this app does not mint. A zero would be
        // read as "an empty stream" and cut a track off at nothing.
        assertNull(StreamRanges.declaredLength("https://example.com/track.opus"))
        assertNull(StreamRanges.declaredLength("https://x.googlevideo.com/v?clen="))
        assertNull(StreamRanges.declaredLength("https://x.googlevideo.com/v?clen=0"))
    }

    // -- how wide one request may be ------------------------------------------------------------

    @Test
    fun theChunkedClientsGetHalfAMegabyte() {
        // The two that serve their media in pieces refuse a wider range than this with 403. The
        // refusal is per client, so it has to be read per client — the same app resolves an iPhone
        // URL and a headset URL in the same queue.
        assertEquals(
            StreamRanges.NARROW_RANGE_BYTES,
            StreamRanges.limitFor("https://x.googlevideo.com/v?c=ANDROID_VR&clen=1"),
        )
        assertEquals(
            StreamRanges.NARROW_RANGE_BYTES,
            StreamRanges.limitFor("https://x.googlevideo.com/v?c=TVHTML5_SIMPLY_EMBEDDED_PLAYER&clen=1"),
        )
    }

    @Test
    fun theRestGetAMegabyte() {
        assertEquals(
            StreamRanges.WIDE_RANGE_BYTES,
            StreamRanges.limitFor("https://x.googlevideo.com/v?c=IOS&clen=1"),
        )
    }

    @Test
    fun somethingThatIsNotAStreamIsNotRanged() {
        // A `c=` that belongs to some other service's query is not YouTube's client, and treating it
        // as one would halve the range size for a server that never asked to be.
        assertEquals(
            StreamRanges.WIDE_RANGE_BYTES,
            StreamRanges.limitFor("https://example.com/track.opus?c=ANDROID_VR"),
        )
    }

    // -- the range itself -----------------------------------------------------------------------

    @Test
    fun aWholeStreamIsStillARange() {
        // Asking for a whole track in one request is answered 403. A read with no stated length is
        // closed at the end of the stream, not left open.
        assertEquals(
            "bytes=0-${StreamRanges.WIDE_RANGE_BYTES - 1}",
            StreamRanges.header(0L, C.LENGTH_UNSET.toLong(), total = 10_000_000L, limit = StreamRanges.WIDE_RANGE_BYTES),
        )
    }

    @Test
    fun aReadIsCappedAtTheLimit() {
        // A 7 MB read from a client that will only serve a megabyte is two requests, not one, and
        // asking for all seven is a 403.
        assertEquals(
            "bytes=0-524287",
            StreamRanges.header(0L, C.LENGTH_UNSET.toLong(), total = 7_000_000L, limit = StreamRanges.NARROW_RANGE_BYTES),
        )
    }

    @Test
    fun aShortReadIsNotStretchedToTheLimit() {
        assertEquals(
            "bytes=0-1023",
            StreamRanges.header(0L, 1_024L, total = 10_000_000L, limit = StreamRanges.WIDE_RANGE_BYTES),
        )
    }

    @Test
    fun aReadIsClampedToTheEndOfTheStream() {
        // Reading past the end is a question with a known answer, not a 416: the last range of a
        // 1,000-byte stream asked for as 4,096 ends at 999.
        assertEquals(
            "bytes=512-999",
            StreamRanges.header(512L, 4_096L, total = 1_000L, limit = StreamRanges.WIDE_RANGE_BYTES),
        )
    }

    @Test
    fun theEndIsInclusive() {
        // One byte at the start is bytes=0-0. Ending a range one past the last byte wanted is the
        // off-by-one that costs a seek rather than a crash, and is invisible until a track will not
        // start.
        assertEquals(
            "bytes=0-0",
            StreamRanges.header(0L, 1L, total = 1_000L, limit = StreamRanges.WIDE_RANGE_BYTES),
        )
    }

    @Test
    fun aStreamOfNoLengthCannotBeRanged() {
        assertNull(
            StreamRanges.header(0L, C.LENGTH_UNSET.toLong(), total = null, limit = StreamRanges.WIDE_RANGE_BYTES),
        )
    }

    @Test
    fun aReadPastTheEndIsNoRangeAtAll() {
        // The answer is that there is nothing left, which `open` reports as a length of zero rather
        // than as a header that would ask for a byte that does not exist.
        assertNull(StreamRanges.end(1_000L, C.LENGTH_UNSET.toLong(), total = 1_000L, limit = StreamRanges.WIDE_RANGE_BYTES))
    }
}
