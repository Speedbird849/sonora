package dev.sonora.backend.taste

/** One named value for the Taste screen. */
data class TasteRow(val name: String, val value: String)

/** Everything the Taste screen shows, computed from the model. */
data class TasteOverview(
    val trackCount: Int,
    val totalPlays: Int,
    val likedCount: Int,
    val artists: List<TasteRow>,
    val tags: List<TasteRow>,
    val skipped: List<TasteRow>,
    val transitions: List<TasteRow>,
    val updatedAt: Long,
)

/**
 * Summarises a model for display.
 *
 * Pure, so the ordering and formatting can be tested without a screen. It is deliberately the only
 * place the raw keys are turned into names: the model stores normalized keys, and a screen that
 * printed `|` and lowercase would look broken.
 */
object TasteSummary {

    fun of(model: TasteModel, now: Long = System.currentTimeMillis(), limit: Int = 12): TasteOverview {
        val names = displayNames(model)

        val artists = model.artists.entries
            .mapNotNull { (artist, affinity) ->
                val weight = affinity.at(now, TasteEngine.HALF_LIFE_ARTIST)
                if (weight <= 0.0) null else TasteRow(names[artist] ?: artist, format(weight))
            }
            .sortedByDescending { it.value.toDoubleOrNull() ?: 0.0 }
            .take(limit)

        val tags = model.tags.entries
            .mapNotNull { (tag, affinity) ->
                val weight = affinity.at(now, TasteEngine.HALF_LIFE_TAG)
                if (weight <= 0.0) null else TasteRow(tag, format(weight))
            }
            .sortedByDescending { it.value.toDoubleOrNull() ?: 0.0 }
            .take(limit)

        val skipped = model.tracks.values
            .filter { it.stats.skips > 0 }
            .sortedWith(compareByDescending<TrackEntry> { it.stats.skips }.thenBy { it.ref.title })
            .take(limit)
            .map { TasteRow(it.ref.title, "${it.stats.skips}") }

        val transitions = model.edges.flatMap { (from, nodes) ->
            nodes.mapNotNull { (to, weight) ->
                val decayed = weight.at(now, TasteEngine.HALF_LIFE_EDGE)
                if (decayed <= 0.0) null
                else Triple(from, to, decayed) to (model.edgeCounts[from]?.get(to) ?: 1)
            }
        }
            .sortedByDescending { (pair, _) -> pair.third }
            .take(limit)
            .map { (pair, count) ->
                val from = model.tracks[pair.first]?.ref?.title ?: pair.first.substringAfter('|')
                val to = model.tracks[pair.second]?.ref?.title ?: pair.second.substringAfter('|')
                TasteRow("$from \u2192 $to", "\u00d7$count")
            }

        return TasteOverview(
            trackCount = model.tracks.size,
            totalPlays = model.tracks.values.sumOf { it.stats.plays },
            likedCount = model.tracks.values.count { it.stats.liked },
            artists = artists,
            tags = tags,
            skipped = skipped,
            transitions = transitions,
            updatedAt = model.updatedAt,
        )
    }

    private fun displayNames(model: TasteModel): Map<String, String> {
        val names = mutableMapOf<String, String>()
        for (entry in model.tracks.values) {
            names.putIfAbsent(entry.ref.artistKey, entry.ref.artist)
        }
        return names
    }

    private fun format(value: Double): String =
        if (value >= 10.0) "%.0f".format(value) else "%.1f".format(value)
}
