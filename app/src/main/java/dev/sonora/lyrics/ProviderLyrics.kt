package dev.sonora.lyrics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Turns the wire formats used by the providers into Sonora lyrics. */
internal object ProviderLyrics {

    fun parse(raw: String): List<LyricLine>? {
        val content = unescapeTtml(unwrap(raw) ?: return null)
        val lines = when {
            content.contains(Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)) ||
                content.contains("http://www.w3.org/ns/ttml", ignoreCase = true) -> TtmlLyrics.parse(content)
            content.trimStart().startsWith("<") -> emptyList()
            else -> EnhancedLrc.parse(content).ifEmpty { Lrc.parse(content) }
                .ifEmpty { plain(content) }
        }
        return lines.takeIf { found -> found.any { it.text.isNotBlank() } }
    }

    private fun plain(content: String): List<LyricLine> {
        if (content.contains(Regex("""(?i)\b(?:lyrics? (?:not found|unavailable)|error)\b"""))) return emptyList()
        return content.lineSequence().map(String::trim).filter(String::isNotEmpty)
            .filterNot { it.matches(Regex("""\[[A-Za-z]+:.*]""")) }
            .map { LyricLine(0L, it) }.toList()
    }

    /** Providers sometimes wrap the same lyric string in one or two JSON envelopes. */
    internal fun unwrap(raw: String): String? {
        var value = raw.replace("\uFEFF", "").trim()
        if (value.startsWith("```")) {
            value = value.lineSequence().drop(1).toList()
                .let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }
                .joinToString("\n").trim()
        }
        if (value.isBlank()) return null
        val json = runCatching { lyricsJson.parseToJsonElement(value) }.getOrNull() ?: return value
        return extract(json)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extract(element: JsonElement): String? = when (element) {
        JsonNull -> null
        is JsonPrimitive -> if (element.isString) {
            val text = element.content.trim()
            val nested = runCatching { lyricsJson.parseToJsonElement(text) }.getOrNull()
            if (nested != null && nested !is JsonPrimitive) extract(nested) else text
        } else null
        is JsonArray -> element.mapNotNull(::extract).joinToString("\n").takeIf { it.isNotBlank() }
        is JsonObject -> {
            if (element["isError"]?.toString() == "true" || element["ok"]?.toString() == "false" ||
                element["error"]?.let { it !is JsonNull && it.toString() !in setOf("false", "\"\"") } == true
            ) {
                null
            } else {
                CONTENT_KEYS.asSequence().mapNotNull { element[it]?.let(::extract) }.firstOrNull()
                    ?: (element["metadata"] as? JsonObject)?.let(::extract)
                    ?: element["words"]?.let(::extract)
            }
        }
    }

    private fun unescapeTtml(value: String): String = if (value.contains("&lt;tt", ignoreCase = true)) {
        value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
    } else value

    private val CONTENT_KEYS = listOf(
        "ttml", "ttmlContent", "lyrics", "lrc", "content", "text",
        "plainLyrics", "syncedLyrics", "line", "lines", "lyric",
        "data", "result", "response",
    )
}
