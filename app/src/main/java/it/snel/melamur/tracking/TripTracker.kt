package it.snel.melamur.tracking

import it.snel.melamur.data.BusRoute
import it.snel.melamur.data.BusStop
import it.snel.melamur.data.RouteDirection
import it.snel.melamur.map.Geo
import kotlin.math.hypot
import kotlin.math.max

/** Snapshot published to the UI and the notification. */
data class TripSnapshot(
    val onBoard: Boolean = false,
    val routeId: String? = null,
    val routeName: String? = null,
    val headsign: String? = null,
    val nextStop: BusStop? = null,
    val nextStopDist: Float? = null,
    val destination: BusStop? = null,
    val pressStop: Boolean = false,
    val offRoute: Boolean = false,
)

/**
 * Map-matches GPS fixes taken on board.
 *  - With route data: infers route+direction from the last fixes, then follows
 *    progress along the shape to find the next stop and detect deviations.
 *  - Without route data (stub API): next stop = nearest stop ahead whose served
 *    heading matches ours, i.e. a stop on the LEFT kerb of our direction.
 */
class TripTracker(private val stops: List<BusStop>, private val routes: List<BusRoute>) {
    private val byId = stops.associateBy { it.id }
    private val recent = ArrayDeque<FloatArray>() // x, y
    private var dir: RouteDirection? = null
    private var route: BusRoute? = null
    private var along = -1f
    private var offCount = 0
    private var warnedFor: String? = null
    var destination: BusStop? = null
        set(v) { field = v; warnedFor = null }
    var snapshot = TripSnapshot(); private set

    fun reset() {
        recent.clear(); dir = null; route = null; along = -1f; offCount = 0; warnedFor = null
        snapshot = TripSnapshot(destination = destination)
    }

    fun onFix(lat: Double, lon: Double, acc: Float, speed: Float, gpsBearing: Float?): TripSnapshot {
        val x = Geo.x(lon); val y = Geo.y(lat)
        if (acc > 50f) return snapshot
        val prev = recent.lastOrNull()
        recent.addLast(floatArrayOf(x, y)); while (recent.size > 8) recent.removeFirst()
        val bearing = gpsBearing?.takeIf { speed > 2f }
            ?: prev?.takeIf { hypot(x - it[0], y - it[1]) > 5f }?.let { Geo.bearing(x - it[0], y - it[1]) }

        if (dir == null && recent.size >= 4) inferRoute()
        var next: BusStop? = null; var dist: Float? = null; var off = false
        val d = dir
        if (d != null) {
            val p = d.shape.project(x, y, along - 50f)
            if (p != null) {
                if (p.dist > OFF_ROUTE_M && acc < 30f) offCount++ else if (p.dist < BACK_ON_ROUTE_M) offCount = 0
                off = offCount >= 3
                if (!off) along = max(along, p.along)
                for (sid in d.stopIds) {
                    val s = byId[sid] ?: continue
                    val sa = d.shape.project(s.x, s.y)?.along ?: continue
                    if (sa > along + 10f) { next = s; dist = sa - along; break }
                }
            }
        } else if (bearing != null) {
            next = stops.asSequence()
                .filter { s -> s.heading != null && Geo.angleDiff(s.heading, bearing) < 45f }
                .map { s -> s to hypot(s.x - x, s.y - y) }
                .filter { (s, dd) -> dd in 15f..800f && Geo.angleDiff(Geo.bearing(s.x - x, s.y - y), bearing) < 40f }
                .minByOrNull { it.second }?.also { dist = it.second }?.first
        }
        val dest = destination
        var press = false
        if (dest != null && next?.id == dest.id && dist != null && dist!! < max(PRESS_STOP_M, speed * 30f)) {
            press = true
        }
        snapshot = TripSnapshot(true, route?.id, route?.shortName, dir?.headsign, next, dist, dest, press, off)
        return snapshot
    }

    /** True exactly once per destination, when the user must press the stop button. */
    fun shouldAlertPressStop(): Boolean {
        val dest = snapshot.destination ?: return false
        if (!snapshot.pressStop || warnedFor == dest.id) return false
        warnedFor = dest.id; return true
    }

    private fun inferRoute() {
        var best: Pair<BusRoute, RouteDirection>? = null; var bestScore = Float.MAX_VALUE
        for (r in routes) for (d in r.directions) {
            val ps = recent.map { d.shape.project(it[0], it[1]) ?: return@map null }
            if (ps.any { it == null }) continue
            val mean = ps.sumOf { it!!.dist.toDouble() }.toFloat() / ps.size
            val forward = ps.last()!!.along > ps.first()!!.along
            if (forward && mean < MATCH_M && mean < bestScore) { bestScore = mean; best = r to d }
        }
        best?.let { (r, d) -> route = r; dir = d; along = -1f }
    }

    companion object {
        const val MATCH_M = 35f
        const val OFF_ROUTE_M = 70f
        const val BACK_ON_ROUTE_M = 35f
        const val PRESS_STOP_M = 250f
    }
}
