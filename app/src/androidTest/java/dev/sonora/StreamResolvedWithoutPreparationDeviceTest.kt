package dev.sonora

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sonora.ytm.YtmSearch
import dev.sonora.ytm.YtmStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a stream resolves in a process which did nothing to prepare it.
 *
 * Every other test in this file calls `YtmStream.init(context)` first, which is what made this
 * worth writing on its own. A test that prepares the thing it is testing cannot tell the difference
 * between "the app sets this up" and "the test sets this up", and those were not the same thing: the
 * app set it up nowhere, so tapping a search result in the real app died on a `NullPointerException`
 * while every test in the suite passed. Instrumentation runs in the app's own process, so the
 * Application has already been created here and the only question is whether it did its job.
 *
 * Nothing in this test initialises anything. If that ever needs a line added, the bug is back.
 */
@RunWith(AndroidJUnit4::class)
class StreamResolvedWithoutPreparationDeviceTest {

    @Test
    fun aStreamResolvesWithNoPreparationByTheCaller() = runBlocking {
        val found = YtmSearch.search("tame impala let it happen").firstOrNull()
        assertNotNull("search returned nothing", found)

        val audio = YtmStream.resolve(found!!.videoId)

        assertNotNull("resolve returned nothing, so nothing prepared the resolver", audio)
        assertTrue("a resolved stream should have a URL", audio!!.url.isNotBlank())
        println("UNPREPARED ${found.videoId} -> ${audio.clientName} ${audio.kbps}kbps")
    }
}
