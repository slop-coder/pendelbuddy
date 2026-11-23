package io.github.slop_coder.pendelbuddy.core

/** Short station names for the tabs at the bottom; each can be overridden in Settings ([Station.short]). */
object ShortNames {
    /** Common short forms of large stations and of the town part of stop names. */
    private val TOWNS = mapOf(
        "Zürich" to "ZH", "Bern" to "BE", "Basel" to "BS", "Luzern" to "LU", "Genève" to "GE", "Lausanne" to "LS",
        "St. Gallen" to "SG", "Winterthur" to "W'thur", "Biel/Bienne" to "Biel", "Fribourg/Freiburg" to "FR",
        "Lugano" to "LG", "Olten" to "OL", "Aarau" to "AA", "Zug" to "ZG", "Chur" to "Chur", "Thun" to "Thun",
    )

    /** Suffixes that only say "main station". */
    private val MAIN = listOf(" HB", " SBB", " Hauptbahnhof", ", Bahnhof", " Bahnhof", ", Hauptbahnhof", " CFF", " FFS")

    fun of(s: Station): String = s.short?.takeIf { it.isNotBlank() } ?: auto(s.name)

    /**
     * "Zürich HB" → "ZH", "Zürich Oerlikon" → "ZH Oerlikon", "Basel SBB" → "BS"; a stop "Town, Street" keeps
     * only the stop part ("Musterdorf, Post" → "Post"). Other names stay as they are.
     */
    fun auto(name: String): String {
        val n = name.trim()
        if (n.contains(", ")) {
            val (town, stop) = n.split(", ", limit = 2)
            if (stop.equals("Bahnhof", ignoreCase = true) || stop.equals("Hauptbahnhof", ignoreCase = true)) {
                return TOWNS[town] ?: town
            }
            return stop
        }
        val main = MAIN.firstOrNull { n.endsWith(it) }?.let { n.removeSuffix(it) }
        if (main != null) return TOWNS[main] ?: main
        TOWNS[n]?.let { return it }
        val town = TOWNS.keys.firstOrNull { n.startsWith("$it ") } ?: return n
        return TOWNS.getValue(town) + n.removePrefix(town)
    }
}
