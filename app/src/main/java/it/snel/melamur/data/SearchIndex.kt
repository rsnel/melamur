package it.snel.melamur.data

import it.snel.melamur.map.Geo
import it.snel.melamur.map.MapView
import java.text.Normalizer

/** A unified search hit: a stop, an address/landmark from OSM, or a stored POI. */
data class SearchResult(
    val kind: Kind, val label: String, val sublabel: String,
    val lat: Double, val lon: Double, val stopNumber: Int? = null,
) {
    enum class Kind { STOP, POI, PLACE, STREET }
    fun asPlace() = Place(label, lat, lon)
}

/**
 * Searches bus stops (name, locality, stop number), OpenStreetMap places and
 * streets (from labels.json), and the user's POIs. Diacritic-insensitive.
 */
object SearchIndex {
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace('ħ', 'h').replace('Ħ', 'H').replace('ġ', 'g').replace('ż', 'z').replace('ł', 'l')
            .lowercase().trim()

    private fun rank(hay: String, q: String): Int = when {
        hay.startsWith(q) -> 1
        hay.split(" ", " - ", "-").any { it.startsWith(q) } -> 2
        hay.contains(q) -> 3
        else -> -1
    }

    fun search(
        stops: List<Stop>, places: List<MapView.PlaceLabel>, streets: List<MapView.StreetLabel>,
        pois: List<Poi>, query: String, limit: Int = 40,
    ): List<SearchResult> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        data class Hit(val r: SearchResult, val rank: Int, val kindOrder: Int)
        val hits = ArrayList<Hit>()

        for (s in stops) {
            val rk = if (s.number.toString() == q) 0
            else maxOf(rank(fold(s.name), q), rank(fold(s.locality ?: ""), q)).let { if (it < 0) -1 else it }
            if (rk >= 0) hits.add(Hit(SearchResult(SearchResult.Kind.STOP, s.display,
                "Stop #${s.number}" + (s.locality?.let { " · $it" } ?: ""), s.lat, s.lon, s.number), rk, 0))
        }
        for (p in pois) {
            val rk = rank(fold(p.name), q); if (rk >= 0)
                hits.add(Hit(SearchResult(SearchResult.Kind.POI, p.name, "Saved place", p.lat, p.lon), rk, 1))
        }
        for (p in places) {
            val rk = rank(fold(p.name), q); if (rk >= 0)
                hits.add(Hit(SearchResult(SearchResult.Kind.PLACE, p.name, "Place",
                    Geo.lat(p.y).toDouble(), Geo.lon(p.x).toDouble()), rk, 2))
        }
        val streetSeen = HashSet<String>()
        for (s in streets) {
            val rk = rank(fold(s.name), q)
            if (rk >= 0 && streetSeen.add(s.name))
                hits.add(Hit(SearchResult(SearchResult.Kind.STREET, s.name, "Street",
                    Geo.lat(s.y).toDouble(), Geo.lon(s.x).toDouble()), rk, 3))
        }
        return hits.sortedWith(compareBy({ it.rank }, { it.kindOrder }, { it.r.label }))
            .map { it.r }.take(limit)
    }
}
