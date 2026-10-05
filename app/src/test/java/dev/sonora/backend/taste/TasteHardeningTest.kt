package dev.sonora.backend.taste

import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The bounds the engine promises a listener's model will stay inside. */
@OptIn(ExperimentalSerializationApi::class)
class TasteHardeningTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun bigModel(count: Int): TasteModel {
        val tracks = HashMap<String, TrackEntry>(count)
        for (index in 0 until count) {
            val ref = TrackRef.of("Track $index", "Artist ${index % 500}")
            tracks[ref.key] = TrackEntry(ref, TrackStats(plays = index % 9, completes = index % 5))
        }
        return TasteModel(tracks = tracks)
    }

    @Test
    fun `a twenty thousand track model saves within the budget`() {
        val file = File(folder.newFolder(), "taste.json")
        val store = TasteStore(file, debounceMs = 0)

        // Warm both the serializer and the file so the measurement is a steady-state save.
        store.writeNow(bigModel(20_000))

        val model = bigModel(20_000)
        val encodingStart = System.nanoTime()
        val sink = java.io.ByteArrayOutputStream()
        Json { encodeDefaults = false; explicitNulls = false }
            .encodeToStream(TasteModel.serializer(), model, sink)
        val encodeMs = (System.nanoTime() - encodingStart) / 1_000_000

        val start = System.nanoTime()
        store.writeNow(model)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(20_000, store.load().tracks.size)
        assertTrue(
            "save took ${elapsedMs}ms (encode ${encodeMs}ms, ${sink.size()} bytes)",
            elapsedMs < 100,
        )
    }
}
