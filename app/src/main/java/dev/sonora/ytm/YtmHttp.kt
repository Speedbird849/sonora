package dev.sonora.ytm

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One OkHttp client for everything that speaks HTTP.
 *
 * The Soulseek protocol does not use this — it has its own framing over raw sockets. What does use
 * it is YouTube Music: the catalogue search, the cipher config, and the googlevideo media fetch
 * that actually delivers the audio.
 *
 * Shared rather than per call site for one concrete reason: googlevideo binds a stream URL to the
 * connection context of the request that minted it, so a client that resolved a URL and a different
 * client that fetched the bytes can be refused with 403. One pool keeps that impossible.
 */
object YtmHttp {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .build()
}
