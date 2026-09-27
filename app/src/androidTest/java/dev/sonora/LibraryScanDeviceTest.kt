package dev.sonora

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sonora.backend.SonoraBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the library says it is reading, and then stops saying so.
 *
 * The flag exists so an empty library is not reported as an empty folder while it is still being
 * scanned. That only works if it clears, and a flag that got stuck true would hide the library
 * behind placeholders for the rest of the session — a worse failure than the one it prevents, and
 * one no test of the library's contents would catch.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScanDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun scanningClearsAfterARefresh() = runBlocking {
        // Toggled by a refresh, so asserted through the flow rather than read once: the whole point
        // is that it goes up and comes back down, and a single sample can only show one end of it.
        val sawScanning = mutableListOf<Boolean>()

        val watching = CoroutineScope(Dispatchers.Main).launch {
            SonoraBackend.scanning.collect { sawScanning += it }
        }

        SonoraBackend.refreshLibrary(context)
        val settled = withTimeoutOrNull(20_000) { SonoraBackend.scanning.first { !it } }

        watching.cancel()

        assertTrue("the flag never settled to false", settled == false)
        assertTrue("a refresh never reported that it was scanning", sawScanning.any { it })
    }

    @Test
    fun anEmptyLibraryIsEmptyRatherThanStillScanning() = runBlocking {
        SonoraBackend.refreshLibrary(context)
        withTimeoutOrNull(20_000) { SonoraBackend.scanning.first { !it } }

        assertFalse(
            "the library should not be left claiming to still be reading",
            SonoraBackend.scanning.value,
        )
    }
}
