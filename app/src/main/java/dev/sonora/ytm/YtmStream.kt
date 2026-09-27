package dev.sonora.ytm

import android.content.Context
import android.os.SystemClock
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
import com.metrolist.innertubex.models.YouTubeLocale
import dev.sonora.playback.StreamRanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    /**
     * The library's own name for the exact variant of that client, which is what a refusal is
     * retired against: one headset identity can be refused while a second one answers.
     */
    val profileId: String,
)

/**
 * Turns a YouTube Music videoId into a URL the player can stream.
 *
 * YouTube does not publish an interface for this, so nothing here is a documented API and all of it
 * can change without notice. InnerTubeX does the hard part — it keeps a catalog of client
 * identities, knows which of them are ciphered and need a proof-of-origin token, and handles the
 * cipher — and this is the wiring around it plus the three things only the app can do: cache the
 * player config on disk, pay the cold costs before a track asks for them, and hand back the headers
 * the media fetch must repeat.
 *
 * ### Why the headers come back with the URL
 *
 * googlevideo bakes the minting client into the URL as `c=`/`cver` and checks the headers of the
 * request that fetches the bytes against it. A URL fetched with the wrong user agent is throttled to
 * a crawl or refused with 403, so a resolved stream is not a bare string: it is the string plus the
 * identity that asked for it.
 *
 * ### Why a resolved stream is not yet a playable one
 *
 * A client that answers a `player` request can still hand back a URL that serves the first megabyte
 * and refuses everything after it, which is not visible from the response and not visible from the
 * opening either: the first minute plays, and then the track dies. So every stream is asked for a
 * range past that boundary before it is handed over, and a client that refuses is retired for that
 * track and the next identity is asked instead. See [verify].
 */
object YtmStream {

    private const val TAG = "YtmStream"

    /** Where the cipher configuration is published. Cached on disk so it is fetched rarely. */
    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    /** Below this, asking for the best rung is asking for more than a lossy source can give. */
    private const val LOW_KBPS = 64

    /**
     * How far into a stream the verification reads.
     *
     * A megabyte is where the refusal starts for the clients that refuse at all, and about a minute
     * into a 128kB stream — so a stream that passes has demonstrably served the part that is played
     * and not merely the part that is opened.
     */
    private const val VERIFY_BOUNDARY_BYTES = 1L * 1024L * 1024L

    /** Enough of the answer has to actually arrive, or the request was not really served. */
    private const val VERIFY_READ_BYTES = 16L * 1024L

    /** Identities tried before giving a track up, and how long a refusal keeps one out. */
    private const val MAX_VERIFY_ATTEMPTS = 4
    private const val RETIRED_MS = 10L * 60L * 1000L

    /** Off the cold-start path; the first tap on a track is rarely sooner. */
    private const val WARM_DELAY_MS = 2_000L

    /** Players rotate every few days; only the newest are worth their megabytes. */
    private const val KEPT_PLAYERS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What each minted URL was, so its fetch can be dressed and its refusal attributed. */
    private val minted = ConcurrentHashMap<String, YtmAudio>()

    /** Identities refused a track, and until when. */
    private val retired = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    private var repository: PlayerConfigRepository? = null
    private var playerDir: File? = null

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
        val detail = event.details.entries
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { (key, value) -> "$key=$value" }
            .orEmpty()
        val line = buildString {
            append("ITX ").append(event.tag).append(": ").append(event.message)
            if (detail.isNotEmpty()) append(" [").append(detail).append(']')
        }
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

    private val innerTube = InnerTube(http, logger = logger).apply {
        // Stated rather than left to the default, because a catalogue answered in the wrong language
        // is a catalogue with no artists in it.
        locale = YouTubeLocale(gl = "US", hl = "en")
    }

    // Both of these read the repository, so both are only safe after [init]. Kept lazy so that
    // merely importing this object costs nothing.
    private val cipherService by lazy {
        YouTubeCipherService(http, RemotePlayerConfigStore(http, repository(), logger), logger)
    }

    private val extractor by lazy {
        // The library's own choice of which identity to ask, by manifest and by what this build can
        // actually provide. Stated here only for the record, because an earlier version of this file
        // had a hand-written preference order and the effect was that every track that mattered
        // resolved to a headset URL that served a megabyte and then refused.
        InnerTubeExtractor(
            configParser = YtConfigParserImpl(http, innerTube, RemotePlayerConfigStore(http, repository(), logger), logger),
            cipherService = cipherService,
            innerTube = innerTube,
            tokenProvider = noTokens,
            logger = logger,
        )
    }

    /**
     * Prepares the resolver. Safe to call more than once.
     *
     * Needs a Context only for the two on-disk caches: the cipher configuration, and the players the
     * cipher has already been solved for. Nothing here reaches the network until a track asks to be
     * played, or until the warm-up decides it is worth paying for.
     *
     * Called from [dev.sonora.SonoraApplication], so it has already happened by the time anything
     * can ask for a stream.
     */
    fun init(context: Context) {
        if (repository != null) return

        val app = context.applicationContext
        val prefs = app.getSharedPreferences("ytm_player_config", Context.MODE_PRIVATE)
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
        playerDir = File(app.filesDir, "ytm_players").apply { mkdirs() }

        scope.launch {
            // Solving a player is the slow part of a resolution, and it only has to be done once per
            // player version. Kept across processes, so only the first launch after YouTube rotates
            // its player pays for it.
            cipherService.setPreprocessedPlayerCache(::readPlayer, ::writePlayer)
            delay(WARM_DELAY_MS)
            warm()
        }
    }

    private suspend fun warm() {
        val start = SystemClock.elapsedRealtime()
        runCatching { extractor.prewarm() }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { Log.w(TAG, "warm-up failed: ${it.message}") }
            .onSuccess { Log.d(TAG, "warmed in ${SystemClock.elapsedRealtime() - start}ms") }
    }

    private fun readPlayer(key: String): String? =
        playerDir?.let { File(it, key) }?.takeIf { it.isFile }?.readText()

    private fun writePlayer(key: String, value: String?) {
        val dir = playerDir ?: return
        val file = File(dir, key)
        if (value == null) {
            file.delete()
            return
        }
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(value)
        tmp.renameTo(file)
        dir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.sortedByDescending { it.lastModified() }
            ?.drop(KEPT_PLAYERS)?.forEach { it.delete() }
    }

    /**
     * Resolves [videoId] to something the player can actually read to the end, or null when no
     * client would serve it.
     *
     * @throws com.metrolist.innertubex.extraction.StreamResolveException when InnerTubeX tried its
     *   whole catalog and every identity was refused — which is what a bot-check or a region block
     *   looks like from here.
     */
    suspend fun resolve(videoId: String, maxKbps: Int = 0): YtmAudio? = withContext(Dispatchers.IO) {
        repeat(MAX_VERIFY_ATTEMPTS) {
            val audio = resolveOnce(videoId, maxKbps) ?: return@withContext null
            if (verify(audio) == Probe.OK) return@withContext audio
            // A URL that resolves and cannot be read to the end is worth keeping for nothing: it is
            // thrown away and the client that minted it is retired for this track, so the next ask
            // is a different identity rather than the same megabyte again.
            minted.remove(audio.url)
            retire(videoId, audio.profileId)
        }
        Log.w(TAG, "no client would serve $videoId past the first megabyte")
        null
    }

    private suspend fun resolveOnce(videoId: String, maxKbps: Int): YtmAudio? {
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
            excludedClients = retiredFor(videoId),
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
            profileId = stream.profileId,
        )
        minted[audio.url] = audio
        return audio
    }

    private enum class Probe { OK, REFUSED, UNREACHABLE }

    /**
     * Asks the URL for a range past the first megabyte, with the headers its real fetch will use.
     *
     * A refusal dressed as a success is the failure this exists for. A client can answer a `player`
     * request perfectly, hand back a correct URL, serve that URL's opening without complaint, and
     * refuse every byte after the first megabyte — so a check of the opening passes and playback
     * dies about a minute in, which reads as a network problem and is not one.
     *
     * Headers can also arrive long before a body that never does, so the bytes themselves are asked
     * for: a trickle that yields its first byte and stalls is a failure too.
     */
    private suspend fun verify(audio: YtmAudio): Probe {
        val total = StreamRanges.declaredLength(audio.url)
        val limit = StreamRanges.limitFor(audio.url)
        val start = if (total != null && total > VERIFY_BOUNDARY_BYTES + VERIFY_READ_BYTES) {
            VERIFY_BOUNDARY_BYTES
        } else {
            0L
        }
        val end = StreamRanges.end(start, VERIFY_READ_BYTES, total ?: Long.MAX_VALUE, limit) ?: return Probe.OK
        val request = okhttp3.Request.Builder()
            .url(audio.url)
            .header("Range", "bytes=$start-$end")
            .apply { audio.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return try {
            YtmHttp.client.newCall(request).execute().use { response ->
                when {
                    response.code in REFUSAL_CODES -> Probe.REFUSED
                    response.code !in 200..299 -> Probe.UNREACHABLE
                    // A refusal dressed as a success: an error page, or a consent interstitial.
                    response.header("Content-Type")?.startsWith("audio/") != true -> Probe.REFUSED
                    response.body?.source()?.request(VERIFY_READ_BYTES) != true -> Probe.UNREACHABLE
                    else -> Probe.OK
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "${audio.clientName} verification failed: ${e.message}")
            Probe.UNREACHABLE
        }
    }

    private val REFUSAL_CODES = setOf(403, 404, 410)

    /** The headers the media fetch for [url] must carry, when this resolver minted it. */
    fun headersFor(url: String): Map<String, String>? = minted[url]?.headers

    /**
     * Forgets a URL googlevideo refused, and retires the client that minted it.
     *
     * @return the videoId it was minted for, or null when it was not ours.
     */
    fun onRefused(url: String): String? {
        val refused = minted.remove(url) ?: return null
        retire(refused.videoId, refused.profileId)
        // A URL that is refused after resolving cleanly is also what a cipher that has gone stale
        // looks like, and the library keeps the config fresh on its own cooldown.
        scope.launch { runCatching { cipherService.refreshAfterStreamRejection() } }
        return refused.videoId
    }

    private fun retire(videoId: String, profileId: String) {
        Log.w(TAG, "retiring $profileId for $videoId")
        retired.getOrPut(videoId) { ConcurrentHashMap() }[profileId] =
            SystemClock.elapsedRealtime() + RETIRED_MS
    }

    private fun retiredFor(videoId: String): Set<String> {
        val entries = retired[videoId] ?: return emptySet()
        val now = SystemClock.elapsedRealtime()
        entries.entries.removeAll { it.value <= now }
        return entries.keys.toSet()
    }
}
