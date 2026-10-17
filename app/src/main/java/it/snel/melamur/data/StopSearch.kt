package it.snel.melamur.data

import java.text.Normalizer

/**
 * Bus-stop search over the loaded stops. Matches the official stop number
 * (`code`, once the API/GTFS supplies it), the stop id, and the STOP AREA —
 * the part of the name after the "Locality - " prefix (e.g. the "Baruni" in
 * "Ħaż-Żabbar - Baruni"), diacritic-insensitively so "ghajn" finds "Għajn".
 * The locality prefix is intentionally not searched.
 *
 * Results are ranked: exact code/id, then area prefix, then word-start, then
 * substring; ties by name. Each result is distinguishable in the UI by island
 * (Malta/Gozo) and served direction (BusStop.compass / arrow).
 */
object StopSearch {
    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace('ħ', 'h').replace('Ħ', 'H').replace('ġ', 'g').replace('ż', 'z').replace('ł', 'l')
            .lowercase().trim()

    /** The stop-area portion: everything after the last " - "; whole name if no dash. */
    fun area(name: String): String = name.substringAfterLast(" - ", name)

    fun search(stops: List<BusStop>, query: String, limit: Int = 40): List<BusStop> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        data class Hit(val stop: BusStop, val rank: Int)
        val hits = ArrayList<Hit>()
        for (s in stops) {
            val code = s.code?.lowercase(); val id = s.id.lowercase(); val a = fold(area(s.name))
            val rank = when {
                code == q || id == q -> 0
                code != null && code.startsWith(q) -> 1
                a.startsWith(q) -> 2
                a.split(" ", "-").any { it.startsWith(q) } -> 3
                a.contains(q) -> 4
                else -> continue
            }
            hits.add(Hit(s, rank))
        }
        return hits.sortedWith(compareBy({ it.rank }, { it.stop.name })).map { it.stop }.take(limit)
    }
}
