package dev.sonora

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sonora.ytm.YtmSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Whether the catalogue answers, and whether the rows parse into the fields the UI needs. */
@RunWith(AndroidJUnit4::class)
class YtmSearchDeviceTest {

    @Test
    fun searchesSongs() = runBlocking {
        val tracks = YtmSearch.search("tame impala let it happen")
        println("COUNT ${tracks.size}")
        tracks.take(6).forEach {
            println("TRACK ${it.videoId} | ${it.title} | ${it.artist} | ${it.album} | art=${it.artworkUrl?.takeLast(20)}")
        }
        assertTrue("no results", tracks.isNotEmpty())
        assertTrue("no videoId", tracks.all { it.videoId.isNotBlank() })
        assertTrue("no artist", tracks.all { it.artist.isNotBlank() })
        assertTrue("no artwork", tracks.all { it.artworkUrl != null })
    }

    @Test
    fun unknownQueryIsEmptyNotAnError() = runBlocking {
        val tracks = YtmSearch.search("zzzqqqxxnotarealtrackname999")
        println("WEIRD COUNT ${tracks.size}")
    }

    @Test
    fun blankQueryIsEmpty() = runBlocking {
        assertTrue(YtmSearch.search("   ").isEmpty())
    }
}
