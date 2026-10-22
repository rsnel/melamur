package it.snel.melamur.data

import it.snel.melamur.map.Geo
import it.snel.melamur.map.Polyline

/**
 * A bus stop. [heading] is the compass direction of travel of the buses that
 * serve it: Malta drives on the left and buses only have doors on the left, so
 * the stop is on the left kerb of that direction (computed in convert_gpkg.py
 * from the road network, or supplied by the API).
 */
data class BusStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val heading: Float?,
    /** Official Tallinja stop number when available (from the API/GTFS); null for OSM-only data. */
    val code: String? = null,
) {
    val x = Geo.x(lon)
    val y = Geo.y(lat)

    /** Which island the stop is on (Gozo lies north of Malta; Comino has no stops). */
    val island: String get() = if (lat >= 36.0) "Gozo" else "Malta"

    /** 8-point compass of the served direction of travel, or null if unknown. */
    val compass: String? get() = heading?.let {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        dirs[(((it + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]
    }

    val arrow: String get() = when (compass) {
        "N" -> "↑"; "NE" -> "↗"; "E" -> "→"; "SE" -> "↘"
        "S" -> "↓"; "SW" -> "↙"; "W" -> "←"; "NW" -> "↖"; else -> "•"
    }
}

data class RouteDirection(
    val id: String,
    val headsign: String,
    val shape: Polyline,
    val stopIds: List<String>,
    val color: Int = 0,
)

/** [color] comes from the operator data (route colour as used by the Tallinja app). */
data class BusRoute(
    val id: String,
    val shortName: String,
    val color: Int,
    val directions: List<RouteDirection>,
)

data class Arrival(val routeId: String, val stopId: String, val etaEpochSec: Long, val realtime: Boolean)
