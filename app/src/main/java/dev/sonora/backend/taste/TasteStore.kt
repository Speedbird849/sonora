package dev.sonora.backend.taste

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Persists the taste model to one JSON document.
 *
 * Writes are **debounced** because the model changes on every play transition and the file can hold
 * tens of thousands of tracks: writing on each change would put a JSON serialization on the play
 * path. They are **atomic** (temp file then rename) because a process killed mid-write would
 * otherwise leave a truncated document, and a model that does not parse is a model that is gone.
 *
 * The previous good document is kept as `taste.json.bak`. A load that finds the main file corrupt
 * falls back to that backup; failing both, it starts empty and preserves whatever was there.
 */
class TasteStore(
    private val file: File,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Mutex()

    private var pending: TasteModel? = null
    private var job: Job? = null

    /** The previous good document, or the corrupt one when no good one was ever written. */
    val backupFile: File get() = File(file.parentFile, "${file.name}.bak")

    /**
     * Reads the model from disk, or an empty one when there is nothing usable.
     *
     * A corrupt main file is not fatal: the backup is tried first, and only when that also fails
     * does the model start empty — with the unreadable file moved aside rather than overwritten, so
     * it is still there to look at.
     */
    fun load(): TasteModel {
        read(file)?.let { return it }
        if (!file.exists()) return TasteModel()

        read(backupFile)?.let { return it }

        runCatching { file.copyTo(backupFile, overwrite = true) }
        return TasteModel()
    }

    private fun read(source: File): TasteModel? {
        if (!source.exists()) return null
        val text = runCatching { source.readText() }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(TasteModel.serializer(), text) }.getOrNull()
    }

    /**
     * Queues [model] to be written after the debounce, or does nothing if a write is already
     * queued — the queued write will pick up the newest value when it runs.
     */
    fun scheduleSave(model: TasteModel) {
        pending = model
        if (job?.isActive == true) return

        job = scope.launch {
            delay(debounceMs)
            drain()
        }
    }

    /** Writes any queued model immediately. Cancels the debounce timer. */
    suspend fun flush() {
        job?.cancel()
        job = null
        drain()
    }

    private suspend fun drain() {
        val model = pending ?: return
        pending = null
        lock.withLock { writeAtomically(model) }
    }

    /** Writes [model] now, synchronously. For callers that already own the write thread. */
    fun writeNow(model: TasteModel) = writeAtomically(model)

    private fun writeAtomically(model: TasteModel) {
        file.parentFile?.mkdirs()

        // The backup is only refreshed from a document that parses, so a corrupt main can never
        // overwrite the last good copy with itself.
        if (read(file) != null) runCatching { file.copyTo(backupFile, overwrite = true) }

        val temporary = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            temporary.writeText(json.encodeToString(TasteModel.serializer(), model))
            // rename(2) replaces the target atomically; Files.move would unlink it first and lose a
            // race against a reader.
            temporary.renameTo(file)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 3_000L
    }
}
