package dev.sonora.playback

import android.util.Log

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.HttpDataSource
import dev.sonora.ytm.YtmHttp
import java.io.IOException
import okhttp3.Response

/** The log tag for the one class in here that has anything to say. */
private const val TAG = "StreamDataSource"

/**
 * The HTTP source a resolved YouTube Music stream is played through.
 *
 * Exists because of one thing googlevideo insists on: the headers that fetched a stream URL are
 * the headers it expects to see again. A URL resolved with one client identity and fetched with
 * another is either throttled to a crawl or refused with 403, and the failure looks like a network
 * problem rather than a mistake.
 *
 * Which identity minted a URL differs per stream, so the headers are asked for per open rather than
 * fixed when this is built — see [factory].
 *
 * Shares [YtmHttp.client] with the resolver for the same reason: DNS, address family and the
 * connection pool all have to match between the request that minted the URL and the one that
 * fetches the bytes.
 *
 * Hand-rolled rather than Media3's own HTTP source because all this needs is to add a fixed set of
 * headers to a GET and hand the body to the player, and that source is built around the
 * `DefaultHttpDataSource` request model.
 */
@UnstableApi
internal class StreamDataSource(

    /** Asked per open, not held: which client minted a URL is a property of the stream. */
    private val headersFor: (String) -> Map<String, String>,
    private val context: Context,
) : BaseDataSource(/* isNetwork = */ true) {

    private var response: Response? = null

    private var opened: Uri? = null

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

        val opened = try {
            YtmHttp.client.newCall(request(dataSpec)).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        if (!opened.isSuccessful) {
            val code = opened.code
            opened.close()
            // 403 from googlevideo is YouTube refusing the *bytes* for an address, not a problem
            // with the URL or the headers: the same URL, with the same client identity, works from
            // a different network. Said in the message because a bare "Response code: 403" twenty
            // frames into playback is not something anybody can act on.
            if (code == 403) {
                Log.w(
                    TAG,
                    "googlevideo refused the stream for $code. The URL was minted and is not " +
                        "stale; the address asking for the bytes was refused.",
                )
            }
            throw HttpDataSource.InvalidResponseCodeException(
                opened.code,
                /* responseMessage = */ null,
                /* cause = */ null,
                opened.headers.toMultimap(),
                dataSpec,
                /* responseBody = */ ByteArray(0),
            )
        }

        response = opened
        transferStarted(dataSpec)

        val declared = opened.body?.contentLength() ?: -1L
        if (declared >= 0) return declared

        // A ranged request needs its length up front to know where the range ends, and without a
        // content length there is no way to work it out. Refusing is the honest answer: treating it
        // as "stream to the end" would hand the player the whole file for what was asked as a slice.
        if (dataSpec.position != 0L || dataSpec.length != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
            throw HttpDataSource.HttpDataSourceException(
                "no content length for a ranged request",
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        return -1L
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        platform?.let { return it.read(buffer, offset, length) }

        val read = response?.body?.source()?.read(buffer, offset, length) ?: -1
        if (read > 0) bytesTransferred(read)
        return read
    }

    /** The URI this source was opened for, which the base class and the loader both ask for. */
    override fun getUri(): Uri? = opened

    override fun close() {
        platform?.close()
        platform = null
        response?.close()
        response = null
        opened = null
        transferEnded()
    }

    private fun request(dataSpec: DataSpec) = okhttp3.Request.Builder()
        .url(dataSpec.uri.toString())
        .apply { headersFor(dataSpec.uri.toString()).forEach { (name, value) -> header(name, value) } }
        .build()

    companion object {
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
     * The check that keeps a downloaded file out of this source. It is an HTTP client with a fixed
     * set of googlevideo headers, and handed a `content://` URI it cannot open one — so the player
     * asks this first and sends everything else to the factory that knows how to read it.
     */
    fun isStream(uri: Uri): Boolean = uri.scheme == "http" || uri.scheme == "https"
    }
}

