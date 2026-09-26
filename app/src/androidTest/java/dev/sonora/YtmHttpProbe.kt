package dev.sonora

import dev.sonora.ytm.YtmHttp
import okhttp3.Request
import okhttp3.Response

/** Blocking GET for a device test, kept out of the test class so the assertion reads cleanly. */
object YtmHttpProbe {
    fun get(request: Request, report: (Int, Long, String?) -> Unit) {
        YtmHttp.client.newCall(request).execute().use { response: Response ->
            val bytes = response.body?.bytes()?.size?.toLong() ?: 0L
            report(response.code, bytes, response.header("Content-Type"))
        }
    }
}
