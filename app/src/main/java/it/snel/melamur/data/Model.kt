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
) {
    val x = Geo.x(lon)
    val y = Geo.y(lat)
}

data class RouteDirection(
    val id: String,
    val headsign: String,
    val shape: Polyline,
    val stopIds: List<String>,
)

/** [color] comes from the operator data (route colour as used by the Tallinja app). */
data class BusRoute(
    val id: String,
    val shortName: String,
    val color: Int,
    val directions: List<RouteDirection>,
)

data class Arrival(val routeId: String, val stopId: String, val etaEpochSec: Long, val realtime: Boolean)
