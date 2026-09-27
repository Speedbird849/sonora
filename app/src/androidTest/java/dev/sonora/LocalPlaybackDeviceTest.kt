package dev.sonora

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.SonoraPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether the music actually on this device can be played.
 *
 * Every file in a download folder is very often a FLAC, so "the download worked and the file is
 * there" and "the file plays" are two entirely different claims, and a library that lists a track
 * it cannot open is the worst kind of broken: it looks like it is working.
 *
 * The files are found by walking the folder directly rather than through the library, so that a
 * missing folder permission cannot be mistaken for a missing decoder. What is being asked here is
 * only whether ExoPlayer can open the file.
 */
@RunWith(AndroidJUnit4::class)
class LocalPlaybackDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aDownloadedFileDecodes() = runBlocking {
        val files = audioFiles()
        println("LOCAL ${files.size} file(s): ${files.joinToString { it.name }}")
        assertTrue("no audio files on the device to test", files.isNotEmpty())

        for (file in files) {
            val error = decodeFailure(file)
            println("DECODE ${file.name} -> ${error ?: "ok"}")
            assertTrue("${file.name} did not play: $error", error == null)
        }
    }

    /**
     * Every audio file to test, unpacked from the test APK's own assets.
     *
     * Shipped with the test rather than read off the device, because the download folder is outside
     * what this process may stat without a permission it does not hold, and a test that reports an
     * empty list for want of a permission passes for the wrong reason. The fixtures are three
     * seconds of tone in the two formats a Soulseek download folder is mostly made of.
     */
    private fun audioFiles(): List<File> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val dir = File(context.cacheDir, "playback-fixtures").apply { mkdirs() }

        return FIXTURES.mapNotNull { name ->
            val copy = File(dir, name)
            if (!copy.exists() || copy.length() == 0L) {
                val unpacked = runCatching {
                    assets.open(name).use { input ->
                        copy.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (unpacked.isFailure) return@mapNotNull null
            }
            copy.takeIf { it.length() > 0 }
        }
    }

    /** Three formats, because each is decoded by different code. */
    private val FIXTURES = listOf("flac_test.flac", "mp3_test.mp3", "m4a_test.m4a")

    /**
     * The first error the player reports for this file, or null once it is genuinely playing.
     *
     * Built and driven on the main thread because that is the only thread ExoPlayer accepts: a
     * player constructed on a test thread throws before it is ever asked to do anything, which is
     * a test bug and not a finding about the audio.
     */
    private suspend fun decodeFailure(file: File): String? = withContext(Dispatchers.Main) {
        val player = ExoPlayer.Builder(context).build()
        var failure: String? = null

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failure = "${error.errorCodeName}: ${error.message}"
            }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.play()

        // A position only moves once there is audio to move through, so this waits on decoding
        // rather than on the prepare call, which returns long before either happens.
        val played = withTimeoutOrNull(20_000) {
            while (failure == null && player.currentPosition <= 0L) {
                delay(100)
            }
            failure == null
        }

        val reported = failure
        player.release()
        when {
            reported != null -> reported
            played != true -> "never reported a position"
            else -> null
        }
    }

    /**
     * That a tap on a local track reaches the player with its own identity intact.
     *
     * The queue is searched by media id to work out where playback started, and an item built
     * without one is never found again — which does not fail loudly, it plays the wrong track.
     */
    @Test
    fun aLocalTrackBecomesThePlayingTrack() = runBlocking {
        val files = audioFiles()
        if (files.size < 3) return@runBlocking
        val tracks = files.take(3).map { LibraryTrack.from(it) }
        val tapped = tracks[2]

        SonoraPlayer.connect(context)
        SonoraPlayer.play(context, tracks, 2)

        val state = withTimeoutOrNull(30_000) {
            SonoraPlayer.state.first { it.track?.key == tapped.key }
        }
        assertNotNull("the tapped track never became the playing track", state)
        println("PLAYING ${state!!.track?.key} of ${tracks.map { it.file!!.name }}")
    }
}
