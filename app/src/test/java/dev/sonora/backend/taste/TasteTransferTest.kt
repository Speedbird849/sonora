package dev.sonora.backend.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteTransferTest {

    private fun model(): TasteModel {
        val a = TrackRef.of("One", "Artist", ytmId = "abc")
        val b = TrackRef.of("Two", "Artist", localPath = "/music/two.flac")
        return TasteModel(
            tracks = mapOf(
                a.key to TrackEntry(a, TrackStats(plays = 3, completes = 2, liked = true)),
                b.key to TrackEntry(b),
            ),
            edges = mapOf(a.key to mapOf(b.key to Decayed(4.0, 1_000L))),
            edgeCounts = mapOf(a.key to mapOf(b.key to 4)),
            artists = mapOf(a.artistKey to Decayed(6.0, 1_000L)),
            recent = listOf(a.key, b.key),
            updatedAt = 2_000L,
        )
    }

    @Test
    fun `a model round trips through the document`() {
        val original = model()

        val decoded = TasteTransfer.decode(TasteTransfer.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `the schema version is carried`() {
        val text = TasteTransfer.encode(model())
        assertTrue(text.contains("\"schemaVersion\": ${TasteModel.SCHEMA_VERSION}"))
    }

    @Test
    fun `a document that is not one decodes to null`() {
        assertNull(TasteTransfer.decode("not json at all"))
        assertNull(TasteTransfer.decode("""{"tracks": 3}"""))
    }

    @Test
    fun `migration at the current version is the identity`() {
        val original = model()
        assertEquals(original, TasteTransfer.migrate(original))
    }
}
