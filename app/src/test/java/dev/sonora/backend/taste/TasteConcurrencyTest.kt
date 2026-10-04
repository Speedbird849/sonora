package dev.sonora.backend.taste

import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TasteConcurrencyTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a thousand parallel records and picks stay consistent`() = runBlocking {
        val engine = TasteEngine(
            TasteStore(File(folder.newFolder(), "taste.json"), debounceMs = 0),
            clock = { 0L },
            random = Random(0),
        )

        val refs = (1..20).map { TrackRef.of("Track $it", "Artist ${it % 5}") }

        val jobs = (1..1000).map { index ->
            launch(Dispatchers.Default) {
                val ref = refs[index % refs.size]
                engine.recordPlay(
                    ref = ref,
                    listenedMs = 60_000,
                    durationMs = 100_000,
                    now = (index % 30).toLong() * 60_000L,
                )
                if (index % 7 == 0) {
                    engine.next(ref, 3) { refs }
                }
            }
        }
        jobs.joinAll()

        val model = engine.snapshot()
        assertEquals(refs.size, model.tracks.size)
        assertTrue(model.recent.size <= TasteModel.RECENT_CAP)
        assertTrue(model.recent.all { it in model.tracks })
    }
}
