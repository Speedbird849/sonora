package dev.sonora.lyrics

import dev.sonora.ytm.YtmHttp
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val LYRICS_TIMEOUT_SECONDS = 6L
internal const val LYRICS_AGENT = "Sonora (https://github.com/sonora)"

internal val lyricsJson = Json { ignoreUnknownKeys = true; isLenient = true }

private val client by lazy {
    YtmHttp.client.newBuilder()
        .callTimeout(LYRICS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()
}

/** Body of a successful GET, or null for any failure. */
internal fun lyricsGet(url: String): String? = runCatching {
    val request = Request.Builder().url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "application/json")
        .build()
    client.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }
}.getOrNull()
