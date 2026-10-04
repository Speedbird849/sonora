package dev.sonora.backend.taste

import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TasteEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val minute = 60_000L

    private fun store(): TasteStore =
        TasteStore(File(folder.newFolder(), "taste.json"), debounceMs = 0)

    private fun engine(store: TasteStore = store()): TasteEngine =
        TasteEngine(store, clock = { 0L }, random = Random(1))

    private fun ref(title: String, artist: String = "Artist", tags: List<String> = emptyList()) =
        TrackRef.of(title = title, artist = artist, tags = tags)

    private suspend fun TasteEngine.play(
        ref: TrackRef,
        at: Long,
        ratio: Double = 1.0,
        durationMs: Long = 100_000L,
        origin: Origin = Origin.USER,
        liked: Boolean = false,
    ) = recordPlay(
        ref = ref,
        listenedMs = (durationMs * ratio).toLong(),
        durationMs = durationMs,
        origin = origin,
        liked = liked,
        now = at,
    )

    @Test
    fun `records a play and the transition that led to it`() = runBlocking {
        val engine = engine()
        val a = ref("A")
        val b = ref("B")

        engine.play(a, at = 0)
        engine.play(b, at = minute)

        val model = engine.snapshot()
        assertEquals(1, model.tracks.getValue(a.key).stats.plays)
        assertTrue(engine.edgeWeight(a.key, b.key, now = minute) > 0.5)
    }

    @Test
    fun `no edge forms across a session gap`() = runBlocking {
        val engine = engine()
        val a = ref("A")
        val b = ref("B")

        engine.play(a, at = 0)
        engine.play(b, at = 31 * minute)

        assertEquals(0.0, engine.edgeWeight(a.key, b.key, now = 31 * minute), 1e-9)
    }

    @Test
    fun `an autoplay-originated positive counts half`() = runBlocking {
        val user = engine()
        val a = ref("A")
        val b = ref("B")
        user.play(a, at = 0)
        user.play(b, at = minute)

        val auto = engine()
        auto.play(a, at = 0)
        auto.play(b, at = minute, origin = Origin.AUTOPLAY)

        val userEdge = user.edgeWeight(a.key, b.key, now = minute)
        val autoEdge = auto.edgeWeight(a.key, b.key, now = minute)

        assertEquals(userEdge / 2.0, autoEdge, 1e-9)
    }

    @Test
    fun `a skipped pick pushes its own edge negative`() = runBlocking {
        val engine = engine()
        val a = ref("A")
        val b = ref("B")

        engine.play(a, at = 0)
        engine.play(b, at = 30_000, ratio = 0.05, durationMs = 200_000)

        assertTrue(engine.edgeWeight(a.key, b.key, now = 30_000) < 0.0)
    }

    @Test
    fun `a liked play is recorded as liked`() = runBlocking {
        val engine = engine()
        val a = ref("A")

        engine.play(a, at = 0, liked = true)

        assertTrue(engine.snapshot().tracks.getValue(a.key).stats.liked)
    }

    @Test
    fun `edges are capped per node`() = runBlocking {
        val engine = engine()
        val origin = ref("Origin")

        engine.play(origin, at = 0)
        for (index in 1..40) {
            engine.play(ref("Song $index"), at = index.toLong() * minute)
            engine.play(origin, at = index.toLong() * minute + 1_000)
        }

        assertTrue(engine.snapshot().edges.getValue(origin.key).size <= TasteEngine.MAX_EDGES_PER_NODE)
    }

    @Test
    fun `the model survives a reload`() = runBlocking {
        val shared = store()
        val first = engine(shared)
        val a = ref("A")
        first.play(a, at = 0)
        first.flush()

        assertTrue("the store persisted a track", shared.load().tracks.containsKey(a.key))

        val reloaded = engine(shared)

        assertEquals(1, reloaded.snapshot().tracks.getValue(a.key).stats.plays)
    }
}
