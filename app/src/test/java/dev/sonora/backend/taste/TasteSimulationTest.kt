package dev.sonora.backend.taste

import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A synthetic listener who stays mostly inside one of three taste clusters, played two thousand
 * times. Each cluster is a handful of artists, so the artists a listener actually plays dominate the
 * top-artist pool — which is what makes the clusters distinct to the engine.
 *
 * This is the test the per-rule tests cannot be: it exercises decay, edge learning, scoring and
 * sampling together and asserts the *outcome* — Autoplay stays where the listener lives, and still
 * reaches for tracks the model has never seen (the radio's own exploration source).
 */
class TasteSimulationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val clusters = 3
    private val artistsPerCluster = 5
    private val tracksPerArtist = 6

    private fun artist(cluster: Int, index: Int) = "C${cluster}A$index"

    private fun track(cluster: Int, artistIndex: Int, index: Int) =
        TrackRef.of("T$cluster-$artistIndex-$index", artist(cluster, artistIndex))

    private fun clusterOf(ref: TrackRef): Int =
        ref.artistKey.removePrefix("c").substringBefore("a").toInt()

    @Test
    fun `autoplay stays in the listener's cluster and still explores`() = runBlocking {
        val engine = TasteEngine(
            TasteStore(File(folder.newFolder(), "taste.json"), debounceMs = 0),
            clock = { 0L },
            random = Random(11),
        )

        val random = Random(42)
        var current = track(0, 0, 0)
        var at = 0L

        repeat(2000) {
            val cluster = clusterOf(current)
            val nextCluster = if (random.nextDouble() < 0.92) {
                cluster
            } else {
                (cluster + 1 + random.nextInt(clusters - 1)) % clusters
            }
            current = track(nextCluster, random.nextInt(artistsPerCluster), random.nextInt(tracksPerArtist))

            engine.recordPlay(
                ref = current,
                listenedMs = 120_000,
                durationMs = 120_000,
                origin = Origin.USER,
                now = at,
            )
            at += 3 * 60_000L
        }

        // End on a track in the listener's home cluster, so Autoplay is asked to continue there.
        current = track(0, random.nextInt(artistsPerCluster), random.nextInt(tracksPerArtist))
        engine.recordPlay(current, listenedMs = 120_000, durationMs = 120_000, origin = Origin.USER, now = at)

        // The exploration source: new tracks by the artist already playing, which is what a radio
        // for that artist answers with — same cluster, nothing the model has heard.
        val radio = (1..40).map { TrackRef.of("Explore $it", current.artist) }
        val radioKeys = radio.mapTo(HashSet()) { it.key }

        val picked = engine.next(current, 30) { radio }

        assertTrue(picked.isNotEmpty())

        val cluster = clusterOf(current)
        val inCluster = picked.count { clusterOf(it) == cluster }
        val ratio = inCluster.toDouble() / picked.size
        val explored = picked.count { it.key in radioKeys }

        assertTrue("stayed in cluster only ${(ratio * 100).toInt()}%", ratio >= 0.8)
        assertTrue("never explored", explored > 0)
    }
}
