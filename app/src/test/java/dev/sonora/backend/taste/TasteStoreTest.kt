package dev.sonora.backend.taste

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TasteStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(file: File = File(folder.newFolder(), "taste.json")) =
        TasteStore(file, debounceMs = 0)

    private fun model(vararg titles: String): TasteModel {
        val entries = titles.associate {
            val ref = TrackRef.of(it, "Artist")
            ref.key to TrackEntry(ref)
        }
        return TasteModel(tracks = entries)
    }

    @Test
    fun `round trips a model`() {
        val file = File(folder.newFolder(), "taste.json")
        val written = model("One", "Two")
        store(file).writeNow(written)

        val read = store(file).load()

        assertEquals(written.tracks.keys, read.tracks.keys)
    }

    @Test
    fun `a missing file is an empty model`() {
        assertTrue(store().load().tracks.isEmpty())
    }

    @Test
    fun `a corrupt main file recovers from the backup`() {
        val file = File(folder.newFolder(), "taste.json")
        store(file).writeNow(model("Good"))
        // The second write makes the first the backup.
        store(file).writeNow(model("Good", "Newer"))
        file.writeText("{ this is not json")

        val recovered = store(file).load()

        assertEquals(setOf(TrackRef.of("Good", "Artist").key), recovered.tracks.keys)
    }

    @Test
    fun `a corrupt file with no backup is empty and preserved`() {
        val file = File(folder.newFolder(), "taste.json")
        file.writeText("{ broken")

        val loaded = store(file).load()

        assertTrue(loaded.tracks.isEmpty())
        assertEquals("{ broken", store(file).backupFile.readText())
    }
}
