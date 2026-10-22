package it.snel.melamur.data

import it.snel.melamur.map.Geo
import it.snel.melamur.map.MapView
import java.text.Normalizer

/** A unified search hit: a stop, an address/landmark from OSM, or a stored POI. */
data class SearchResult(
    val kind: Kind, val label: String, val sublabel: String,
    val lat: Double, val lon: Double, val stopNumber: Int? = null,
) {
    enum class Kind { STOP, POI, LANDMARK, PLACE, STREET }
    fun asPlace() = Place(label, lat, lon)
}

/**
 * Fast unified search over bus stops (name, locality, number), OpenStreetMap
 * places, streets and landmarks, and the user's POIs.
 *
 * The slow part used to be folding (diacritic-stripping) every candidate on
 * every keystroke. Here each entry's searchable text is folded ONCE when the
 * index is built; a query then only does cheap startsWith/contains on the
 * precomputed strings. Rebuild the index when the data or POIs change.
 */
object SearchIndex {
    /** Normalise for matching: strip diacritics, Maltese letters, separators → spaces. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace('ħ', 'h').replace('Ħ', 'H').replace('ġ', 'g').replace('ż', 'z').replace('ł', 'l')
            .replace('-', ' ').replace('/', ' ').replace("  +".toRegex(), " ")
            .lowercase().trim()

    internal class Entry(val folded: String, val number: Int?, val kindOrder: Int, val result: SearchResult)

    class Index internal constructor(private val entries: List<Entry>) {
        fun search(query: String, limit: Int = 40): List<SearchResult> {
            val q = fold(query)
            if (q.isEmpty()) return emptyList()
            val digits = q.all { it.isDigit() }
            val spaced = " $q"
            data class Hit(val rank: Int, val order: Int, val label: String, val r: SearchResult)
            val hits = ArrayList<Hit>()
            for (e in entries) {
                val rank = when {
                    digits && e.number != null && e.number.toString() == q -> 0
                    e.folded.startsWith(q) -> 1
                    e.folded.contains(spaced) -> 2
                    e.folded.contains(q) -> 3
                    else -> continue
                }
                hits.add(Hit(rank, e.kindOrder, e.result.label, e.result))
            }
            return hits.sortedWith(compareBy({ it.rank }, { it.order }, { it.label }))
                .map { it.r }.take(limit)
        }
    }

    fun build(
        stops: List<Stop>, places: List<MapView.PlaceLabel>, streets: List<MapView.StreetLabel>,
        landmarks: List<MapView.PlaceLabel>, pois: List<Poi>,
    ): Index {
        val e = ArrayList<Entry>(stops.size + places.size + streets.size + landmarks.size + pois.size)
        for (s in stops) e.add(Entry(
            fold("${s.name} ${s.locality ?: ""} ${s.number}"), s.number, 0,
            SearchResult(SearchResult.Kind.STOP, s.display,
                "Stop #${s.number}" + (s.locality?.let { " · $it" } ?: ""), s.lat, s.lon, s.number)))
        for (p in pois) e.add(Entry(fold(p.name), null, 1,
            SearchResult(SearchResult.Kind.POI, p.name, "Saved place", p.lat, p.lon)))
        for (p in landmarks) e.add(Entry(fold(p.name), null, 2,
            SearchResult(SearchResult.Kind.LANDMARK, p.name, "Landmark", Geo.lat(p.y), Geo.lon(p.x))))
        for (p in places) e.add(Entry(fold(p.name), null, 3,
            SearchResult(SearchResult.Kind.PLACE, p.name, "Place", Geo.lat(p.y), Geo.lon(p.x))))
        val seen = HashSet<String>()
        for (s in streets) if (seen.add(s.name)) e.add(Entry(fold(s.name), null, 4,
            SearchResult(SearchResult.Kind.STREET, s.name, "Street", Geo.lat(s.y), Geo.lon(s.x))))
        return Index(e)
    }
}
