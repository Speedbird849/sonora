package dev.sonora.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import dev.sonora.ytm.YtmHttp
import dev.sonora.ytm.YtmStream
import java.io.IOException
import okhttp3.Response

/** The log tag for the one class in here that has anything to say. */
private const val TAG = "StreamDataSource"

/**
 * The HTTP source a resolved YouTube Music stream is played through, read in bounded ranges.
 *
 * Exists because of two things googlevideo insists on, and both of them present as a refusal rather
 * than as a warning.
 *
 * The first is identity: the headers that fetched a stream URL are the headers it expects to see
 * again, and a URL resolved with one client and fetched with another is throttled to a crawl or
 * refused with 403. Which identity minted a URL differs per stream, so the headers are asked for per
 * open rather than fixed when this is built — see [factory].
 *
 * The second is shape. googlevideo serves its media in ranges and refuses the alternatives: a request
 * for a whole track is answered 403, and so is one for a range wider than the minting client will
 * serve. So every read here is a closed range no wider than that client, and the length the player
 * is told about is the whole stream even though it arrives a range at a time — see [StreamRanges] for
 * the rules and why they are what they are. Nothing above this has to know the chunking happened.
 *
 * Shares [YtmHttp.client] with the resolver for the same reason as the headers: DNS, address family
 * and the connection pool all have to match between the request that minted the URL and the one that
 * fetches the bytes.
 */
@UnstableApi
internal class StreamDataSource(

    /** Asked per open, not held: which client minted a URL is a property of the stream. */
    private val headersFor: (String) -> Map<String, String>,
    private val context: Context,
) : BaseDataSource(/* isNetwork = */ true) {

    private var response: Response? = null

    private var opened: Uri? = null

    /** The read the player asked for, where it has got to, and how much is left of it. */
    private var read: DataSpec? = null
    private var position = 0L
    private var remaining = 0L

    /** The range currently open, and how much of it is left. */
    private var chunkEnd = -1L

    /** How wide this stream's ranges may be, and how long the whole thing is. */
    private var limit = Long.MAX_VALUE
    private var total: Long? = null

    /**
     * Set when the request cannot be improved on, and is simply forwarded.
     *
     * A URL that declares no length has no last range to end on, so it is read the way it asks to
     * be. Nothing this app mints is in that position; a downloaded file never gets here at all — see
     * [isStream].
     */
    private var asAsked = false

    /**
     * The source used for anything that is not an HTTP URL.
     *
     * This class is installed as the player's data source for *every* item, because a factory
     * cannot pick per item, and a downloaded track is a `content://` URI that an HTTP client cannot
     * open. Handing those back here rather than refusing them is what lets one source serve a queue
     * holding both downloaded files and minted stream URLs.
     */
    private var platform: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        opened = dataSpec.uri

        if (!isStream(dataSpec.uri)) {
            val delegate = DefaultDataSource.Factory(context).createDataSource()
            platform = delegate
            return delegate.open(dataSpec)
        }

        transferInitializing(dataSpec)

        val url = dataSpec.uri.toString()
        total = StreamRanges.declaredLength(url)
        limit = StreamRanges.limitFor(url)
        read = dataSpec
        position = dataSpec.position
        asAsked = total == null

        if (asAsked) {
            remaining = C.LENGTH_UNSET.toLong()
            openChunk(UNBOUNDED)
            return -1L
        }

        val whole = total!!
        val end = StreamRanges.end(dataSpec.position, dataSpec.length, whole, limit)
        if (end == null) {
            remaining = 0L
            return 0L
        }
        remaining = end - dataSpec.position + 1
        openChunk(end)
        return remaining
    }

    /**
     * Opens the next range of the read, at most as wide as this stream is served.
     *
     * The refused range is the one to log by name. Which client minted a URL is the first thing
     * worth knowing when a stream dies, and by the time this surfaces as a playback error there is
     * nowhere else left to learn it from.
     */
    private fun openChunk(end: Long) {
        val asked = requireNotNull(read)
        chunkEnd = end
        val spec = asked.buildUpon().setPosition(position).build()
        try {
            val call = YtmHttp.client.newCall(request(spec, end))
            val openedCall = call.execute()
            if (!openedCall.isSuccessful) {
                report(spec, openedCall)
                openedCall.close()
                throw HttpDataSource.InvalidResponseCodeException(
                    openedCall.code,
                    /* responseMessage = */ null,
                    /* cause = */ null,
                    openedCall.headers.toMultimap(),
                    spec,
                    /* responseBody = */ ByteArray(0),
                )
            }
            response = openedCall
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                spec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        transferStarted(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        platform?.let { return it.read(buffer, offset, length) }
        if (!asAsked && remaining == 0L) return C.RESULT_END_OF_INPUT

        // A range that ends early is re-opened for the part that did not arrive, which is also how
        // the step to the next range happens. The attempt limit is what stops a server that has
        // decided to send nothing from spinning here forever.
        repeat(MAX_EMPTY_RANGES) {
            if (!asAsked && position > chunkEnd) {
                closeChunk()
                openChunk(
                    StreamRanges.end(position, C.LENGTH_UNSET.toLong(), total, limit)
                        ?: return C.RESULT_END_OF_INPUT,
                )
            }
            val wanted = if (asAsked) {
                length
            } else {
                minOf(length.toLong(), chunkEnd - position + 1).toInt()
            }
            val got = response?.body?.source()?.read(buffer, offset, wanted) ?: C.RESULT_END_OF_INPUT
            if (got != C.RESULT_END_OF_INPUT) {
                position += got
                if (!asAsked) remaining -= got
                bytesTransferred(got)
                return got
            }
            if (asAsked) return C.RESULT_END_OF_INPUT
        }
        return C.RESULT_END_OF_INPUT
    }

    /** The URI this source was opened for, which the base class and the loader both ask for. */
    override fun getUri(): Uri? = opened

    override fun close() {
        platform?.close()
        platform = null
        closeChunk()
        read = null
        remaining = 0L
        total = null
        transferEnded()
    }

    private fun closeChunk() {
        response?.close()
        response = null
        chunkEnd = -1L
    }

    private fun request(dataSpec: DataSpec, end: Long) = okhttp3.Request.Builder()
        .url(dataSpec.uri.toString())
        .apply { headersFor(dataSpec.uri.toString()).forEach { (name, value) -> header(name, value) } }
        .apply { if (end != UNBOUNDED) header("Range", "bytes=$position-$end") }
        .build()

    /**
     * Hands a refusal back to whoever minted the URL that was refused, by name.
     *
     * So the client that minted it is retired for the track that wanted it, which is the only thing
     * that makes the next attempt a different one. A 403 here is nearly always that client's
     * problem rather than the address's: a URL this app minted is bound to the connection that
     * minted it, and both went out over the same client.
     */
    private fun report(spec: DataSpec, refused: Response) {
        if (refused.code != 403) return
        val url = spec.uri
        Log.w(
            TAG,
            "googlevideo refused bytes ${refused.code} at $position for " +
                "${url.host} as ${url.getQueryParameter("c")}",
        )
        YtmStream.onRefused(url.toString())
    }

    companion object {
        /** Enough to ride out a truncated range, not enough to hang on a dead one. */
        const val MAX_EMPTY_RANGES = 3

        /** The end of a range that is not closed, for a URL that declares no length. */
        private const val UNBOUNDED = -1L

        /**
         * A factory that looks the headers up per URL as it opens.
         *
         * Takes a lookup rather than a fixed map because the client identity is a property of the
         * stream, not of the player: one queue can hold an iPhone-minted URL and a TV-minted one at
         * the same time, and a factory holding one set of headers would be wrong for the other.
         */
        fun factory(
            appContext: Context,
            headersFor: (String) -> Map<String, String>,
        ): DataSource.Factory =
            DataSource.Factory { StreamDataSource(headersFor, appContext) }

        /**
         * Whether a URI is one of ours to fetch.
         *
         * The check that keeps a downloaded file out of this source. It is an HTTP client with a
         * fixed set of googlevideo headers, and handed a `content://` URI it cannot open one — so
         * the player asks this first and sends everything else to the factory that knows how to
         * read it.
         */
        fun isStream(uri: Uri): Boolean = uri.scheme == "http" || uri.scheme == "https"
    }
}
