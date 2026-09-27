package dev.sonora.playback

import androidx.media3.common.C

/**
 * How a googlevideo stream is read: in bounded ranges, and only as far as the URL says.
 *
 * Three rules, all of them learned from a stream that refused to play rather than from
 * documentation. They are separated from the HTTP that carries them because each one was wrong in a
 * way the logs could not explain, and because they are the parts worth pinning down.
 */
internal object StreamRanges {

    /**
     * How much one request may ask for.
     *
     * A whole track in one request comes back paced to roughly playback speed — about 15 kB/s where
     * the same bytes asked for as ranges arrive at line rate — so a player that reads the whole
     * thing at once builds a buffer it can never grow, and a stall never recovers into a cushion.
     *
     * The cap is per client because the clients differ: the two that serve their media in chunks
     * refuse anything wider than half a megabyte, and the rest are content with a megabyte. Nothing
     * outside googlevideo is chunked, so a URL that is not theirs is read whole.
     */
    const val WIDE_RANGE_BYTES = 1L * 1024L * 1024L

    /** For the two clients whose media is served in half-megabyte pieces. */
    const val NARROW_RANGE_BYTES = 512L * 1024L

    /**
     * The whole length of a stream, as the URL declares it.
     *
     * Every progressive googlevideo URL carries it as `clen`, which costs nothing to read and is the
     * only length available: the player asks for a read before anything has been fetched, so there
     * is no response to have asked. Null for a URL that declares none — anything but a googlevideo
     * stream, and a shape this app does not mint.
     */
    fun declaredLength(url: String): Long? {
        val at = url.indexOf("clen=")
        if (at < 0) return null
        var cursor = at + "clen=".length
        var length = 0L
        var digits = 0
        while (cursor < url.length && url[cursor].isDigit()) {
            length = length * 10 + (url[cursor] - '0')
            digits++
            if (digits > 18) return null
            cursor++
        }
        return if (digits == 0 || length <= 0L) null else length
    }

    /** The largest single range [url]'s own client is reliably served. */
    fun limitFor(url: String): Long =
        if (url.contains("googlevideo.com") && isChunkedClient(url)) NARROW_RANGE_BYTES else WIDE_RANGE_BYTES

    private fun isChunkedClient(url: String): Boolean {
        val at = url.indexOf("c=")
        if (at < 0) return false
        val name = url.substring(at + 2).takeWhile { it == '_' || it.isUpperCase() || it.isDigit() }
        return name == "ANDROID_VR" || name.startsWith("TVHTML5_SIMPLY")
    }

    /**
     * The last byte of the next range, counted inclusively, or null when the read cannot be closed.
     *
     * A googlevideo stream is not served in one piece: asked for the whole of it the request is
     * refused with 403, and asked for a range wider than the minting client's, refused the same way.
     * So the range is always written and always written closed at both ends.
     *
     * Closing it needs a length, and a googlevideo URL declares one. A read past the end is clamped
     * to the end rather than refused, because the alternative is a 416 for a request that was only
     * asking for more than there is.
     */
    fun end(position: Long, length: Long, total: Long?, limit: Long): Long? {
        if (total == null) return null
        val wanted = if (length == C.LENGTH_UNSET.toLong()) total else minOf(total, position + length)
        val end = minOf(wanted, position + limit) - 1
        // A read that starts at or past the end of the stream has no last byte to end on, and a
        // range written for it would be backwards — `bytes=1000-999`.
        return if (end < position) null else end
    }

    /** The `Range` header for a read, or null when there is no length to close it with. */
    fun header(position: Long, length: Long, total: Long?, limit: Long): String? =
        end(position, length, total, limit)?.let { "bytes=$position-$it" }
}
