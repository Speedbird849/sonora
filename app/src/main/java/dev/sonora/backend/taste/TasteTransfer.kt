package dev.sonora.backend.taste

import kotlinx.serialization.json.Json

/**
 * Reads and writes the model as a document that can leave the device.
 *
 * The schema version is the whole reason this is separate from [TasteStore]: a backup is written
 * today and restored next year, so the format has to be able to change without the old file becoming
 * unreadable. [migrate] is the hook that makes that possible, and is currently the identity.
 */
object TasteTransfer {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(model: TasteModel): String = json.encodeToString(TasteModel.serializer(), model)

    /** Parses an exported document, or null when it is not one. */
    fun decode(text: String): TasteModel? =
        runCatching { migrate(json.decodeFromString(TasteModel.serializer(), text)) }.getOrNull()

    /**
     * Brings an older document up to [TasteModel.SCHEMA_VERSION].
     *
     * Empty at version 1 because there is nothing older to bring up. A future change appends a step
     * here rather than adding a field with a default that silently misreads the old shape.
     */
    internal fun migrate(model: TasteModel): TasteModel = model
}
