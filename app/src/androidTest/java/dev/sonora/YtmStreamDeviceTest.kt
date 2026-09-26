package dev.sonora

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sonora.ytm.YtmStream
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether YouTube will actually serve a stream to this device.
 *
 * Not a unit test because the answer depends on the network, the client identity and the cipher
 * configuration as they are right now — all of which a JVM fixture would only agree with. A green
 * run here means a track plays; a red one says which stage refused.
 */
@RunWith(AndroidJUnit4::class)
class YtmStreamDeviceTest {

    @Test
    fun resolvesAndFetchesRealAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YtmStream.init(context)

        val audio = YtmStream.resolve("PvM79DJ2PmM")
        println("RESOLVED ${audio?.clientName} ${audio?.kbps}kbps ${audio?.sampleRateHz}Hz ${audio?.mimeType}")
        println("URL ${audio?.url?.take(90)}...")
        println("HEADERS ${audio?.headers}")
        assertTrue("no stream resolved", audio != null)
        requireNotNull(audio)

        // The point of resolving: bytes. A URL that 403s on the media fetch is not a stream.
        val request = Request.Builder()
            .url(audio.url)
            .apply { audio.headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-65535")
            .build()
        dev.sonora.ytm.YtmHttp.client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes()?.size?.toLong() ?: 0L
            println("FETCH http=${response.code} bytes=$bytes type=${response.header("Content-Type")}")
            assertTrue("media fetch refused: ${response.code}", response.isSuccessful)
            assertTrue("no bytes came back", bytes > 0)
        }
    }
}
