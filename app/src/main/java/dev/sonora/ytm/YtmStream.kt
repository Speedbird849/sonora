package dev.sonora.ytm

import android.content.Context
import android.util.Log
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeLocale
import java.util.concurrent.ConcurrentHashMap

/** A playable audio stream for one YouTube Music track, and what its fetch has to carry. */
data class YtmAudio(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String,
    val kbps: Int,
    val sampleRateHz: Int?,
    /** Which client identity minted the URL. Shown on the player for diagnosis. */
    val clientName: String,
)

/**
 * Turns a YouTube Music videoId into a URL the player can stream.
 *
 * YouTube does not publish an interface for this, so nothing here is a documented API and all of it
 * can change without notice. InnerTubeX does the hard part — it keeps a catalog of client
 * identities, knows which of them are ciphered and need a proof-of-origin token, and handles the
 * cipher — and this is the wiring around it plus the two things only the app can do: cache the
 * player config on disk, and hand back the headers the media fetch must repeat.
 *
 * ### Why the headers come back with the URL
 *
 * googlevideo bakes the minting client into the URL as `c=`/`cver` and checks the headers of the
 * request that fetches the bytes against it. A URL fetched with the wrong user agent is throttled to
 * a crawl or refused with 403, so a resolved stream is not a bare string: it is the string plus the
 * identity that asked for it.
 */
object YtmStream {

    private const val TAG = "YtmStream"

    /** Where the cipher configuration is published. Cached on disk so it is fetched rarely. */
    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    /** Below this, asking for the best rung is asking for more than a lossy source can give. */
    private const val LOW_KBPS = 64

    /** What each minted URL was, so its fetch can be dressed and its refusal attributed. */
    private val minted = ConcurrentHashMap<String, YtmAudio>()

    private var repository: PlayerConfigRepository? = null

    /**
     * The player-config cache, or a failure that says what is wrong.
     *
     * A plain `repository!!` reads as a crash with no explanation, and this one is easy to hit: the
     * resolver is only prepared by [init], so anything that resolves before the Application has run
     * — a service started by a notification, a test that forgot — takes the `!!` and dies on a
     * `NullPointerException` with no message. Saying which precondition was missed costs one line
     * and turns a mystery into a one-line fix.
     */
    private fun repository(): PlayerConfigRepository = checkNotNull(repository) {
        "YtmStream.init(context) has not been called"
    }

    private val logger = InnerTubeLogger { event ->
        if (event.level == InnerTubeLogLevel.DEBUG) return@InnerTubeLogger
        val line = "ITX ${event.tag}: ${event.message}"
        if (event.level == InnerTubeLogLevel.INFO) Log.d(TAG, line) else Log.w(TAG, line)
    }

    private val http = YtmHttp.ktor

    /**
     * No proof-of-origin token.
     *
     * InnerTubeX treats a provider with no capabilities as "this client needs no token", and falls
     * back to identities that answer without one. That costs the best audio — a PO token is what
     * unlocks the higher rungs — but it means a track can play before any of that machinery exists,
     * and a track that plays badly is worth more than a track that does not play.
     */
    private val noTokens = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)
        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? =
            null
    }

    private val innerTube = InnerTube(http, logger = logger)

    // Both of these read the repository, so both are only safe after [init]. Kept lazy so that
    // merely importing this object costs nothing.
    private val cipherService by lazy {
        YouTubeCipherService(http, RemotePlayerConfigStore(http, repository(), logger), logger)
    }

    /**
     * The order clients are tried in, and why this one.
     *
     * The library's own order is a fixed preference list plus a health monitor that demotes a client
     * for a while after it fails. Both are right in general and wrong here: the preference list is
     * written for a network YouTube serves, and a client that has been demoted stays demoted for the
     * rest of the session — so one bad hour leaves every later track unplayable, and the listener
     * sees a search that works and a player that does not.
     *
     * So the order is stated here, and the monitor is [ClientHealthMonitor.Companion.NONE]. A
     * client that fails is skipped for this track and the next one tries; nothing is written off
     * for longer than the request that failed.
     *
     * The order itself is the one that works without an account and without a proof-of-origin
     * token. [YouTubeClient.IOS] leads because it is the one that reliably answers a signed-out
     * `player` request; the rest are fallbacks for the networks where it does not.
     */
    private val clientOrder = listOf(
        YouTubeClient.IOS,
        YouTubeClient.VISIONOS,
        YouTubeClient.WEB_REMIX,
        YouTubeClient.ANDROID_VR_1_65_10,
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        YouTubeClient.WEB,
        YouTubeClient.MWEB,
        YouTubeClient.IOS_MUSIC,
        YouTubeClient.ANDROID_MUSIC,
        YouTubeClient.TVHTML5,
    )

    private val orderOnly = object : ClientFallbackStrategy {
        override fun resolveClients(hints: ContentHints): List<YouTubeClient> {
            // Declared in reverse and reversed here, because the strategy's contract is a
            // preference order and a list literal read bottom-up is a preference order nobody can
            // read.
            return clientOrder.reversed()
        }
    }

    private val extractor by lazy {
        // Positional, because the named form binds to the internal constructor that takes a
        // director rather than a strategy, and that one is not ours to build.
        InnerTubeExtractor(
            YtConfigParserImpl(http, innerTube, RemotePlayerConfigStore(http, repository(), logger), logger),
            cipherService,
            innerTube,
            orderOnly,
            noTokens,
            ClientHealthMonitor.NONE,
            logger,
        )
    }

    /**
     * Prepares the resolver. Safe to call more than once.
     *
     * Needs a Context only for the on-disk cache of the cipher configuration; nothing here reaches
     * the network until a track asks to be played.
     *
     * Called from [dev.sonora.SonoraApplication], so it has already happened by the time anything
     * can ask for a stream.
     */
    fun init(context: Context) {
        if (repository != null) return

        val prefs = context.applicationContext.getSharedPreferences("ytm_player_config", Context.MODE_PRIVATE)
        repository = object : PlayerConfigRepository {
            override val enabled: Boolean get() = true
            override val sourceUrl: String = PLAYER_CONFIG_URL
            override val defaultSourceUrl: String = PLAYER_CONFIG_URL
            override var cachedJson: String
                get() = prefs.getString("json", "").orEmpty()
                set(value) { prefs.edit().putString("json", value).apply() }
            override var cachedAtMs: Long
                get() = prefs.getLong("cached_at_ms", 0L) ?: 0L
                set(value) { prefs.edit().putLong("cached_at_ms", value).apply() }
            override var cachedSourceUrl: String
                get() = prefs.getString("source_url", "").orEmpty()
                set(value) { prefs.edit().putString("source_url", value).apply() }
            override var cachedEtag: String
                get() = prefs.getString("etag", "").orEmpty()
                set(value) { prefs.edit().putString("etag", value).apply() }
        }
    }

    /**
     * Resolves [videoId] to something playable, or null when no client would serve it.
     *
     * @throws com.metrolist.innertubex.extraction.StreamResolveException when InnerTubeX tried its
     *   whole catalog and every identity was refused — which is what a bot-check or a region block
     *   looks like from here.
     */
    suspend fun resolve(videoId: String, maxKbps: Int = 0): YtmAudio? {
        val stream = extractor.extract(
            videoId = videoId,
            // HLS and SABR are both refused outright below: this player is given a single
            // progressive URL, and asking for a segmented delivery it cannot mount would only
            // produce a stream that resolves and then fails to play.
            hints = ContentHints().withStreamCapabilities(
                allowHls = false,
                allowSabr = false,
                allowBoundedRange = true,
            ),
            audioQuality = if (maxKbps in 1..LOW_KBPS) AudioQuality.LOW else AudioQuality.AUTO,
            clientPlaybackNonce = generateClientPlaybackNonce(),
        ) ?: return null

        check(stream.sabrBootstrap == null) { "SABR is not supported by this playback engine" }

        val mime = stream.mimeType.orEmpty()
        val audio = YtmAudio(
            videoId = videoId,
            url = stream.audioUrl,
            headers = stream.headers,
            mimeType = if (stream.codecs.isNullOrBlank()) mime else "$mime; codecs=\"${stream.codecs}\"",
            kbps = (stream.bitrate ?: 0) / 1000,
            sampleRateHz = stream.sampleRate,
            clientName = stream.clientName,
        )
        minted[audio.url] = audio
        return audio
    }

    /** The headers the media fetch for [url] must carry, when this resolver minted it. */
    fun headersFor(url: String): Map<String, String>? = minted[url]?.headers

    /**
     * Forgets a URL googlevideo refused, so the next attempt asks a different client.
     *
     * @return the videoId it was minted for, or null when it was not ours.
     */
    fun onRefused(url: String): String? = minted.remove(url)?.videoId

    private const val RANGE_BYTES = 1024L * 1024
}
