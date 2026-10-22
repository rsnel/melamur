package it.snel.melamur.data

import it.snel.melamur.map.Geo
import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A planner endpoint (a stop, address, landmark or POI). */
data class Place(val name: String, val lat: Double, val lon: Double) {
    val x = Geo.x(lon); val y = Geo.y(lat)
}

/**
 * Earliest-arrival journey planner over the MPT timetable.
 *
 * Heuristic and bounded (it runs on a phone, off the main thread): it walks
 * from the origin to nearby stops, takes the timetable to stops near the
 * destination, and considers at most ONE interchange (boarding again at the
 * stop where you alighted). It is not a full optimal RAPTOR/CSA search — good
 * enough to suggest realistic journeys; see docs for the limitations.
 */
object Planner {
    private const val WALK_SPEED = 1.3           // m/s
    private const val MAX_WALK_M = 1000.0
    private const val NEAR_STOPS = 6
    private const val WINDOW_MIN = 120           // only trips within 2 h of readiness
    private const val MIN_TRANSFER_MIN = 2
    private const val MAX_TRANSFER_POINTS = 12
    private const val MAX_RESULTS = 6

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot((ax - bx).toDouble(), (ay - by).toDouble())
    private fun walkMin(meters: Double) = ceil(meters / WALK_SPEED / 60.0).toInt()

    private class Near(val stop: Stop, val meters: Int, val walk: Int)

    private fun near(place: Place, stops: List<Stop>): List<Near> =
        stops.map { Near(it, dist(place.x, place.y, it.x, it.y).roundToInt(), 0) }
            .filter { it.meters <= MAX_WALK_M }
            .sortedBy { it.meters }.take(NEAR_STOPS)
            .map { Near(it.stop, it.meters, walkMin(it.meters.toDouble())) }

    fun plan(db: MptDb, allStops: List<Stop>, from: Place, to: Place, departAt: LocalDateTime): List<Itinerary> {
        val date = departAt.toLocalDate()
        val v = db.versionFor(date)
        val departMin = departAt.hour * 60 + departAt.minute
        val origins = near(from, allStops)
        val dests = near(to, allStops).associateBy { it.stop.number }
        if (origins.isEmpty() || dests.isEmpty()) return emptyList()

        val results = ArrayList<Itinerary>()
        val transferPoints = HashMap<Int, Triple<Int, TripStop, FirstLeg>>() // stop# -> (arrMin, stop, first leg)

        for (o in origins) {
            val ready = departMin + o.walk
            val deps = db.departures(o.stop.number, date, v).filter { it.minutes in ready..(ready + WINDOW_MIN) }
            // earliest trip per route+direction is enough for a first pass
            val seen = HashSet<String>()
            for (dep in deps) {
                if (!seen.add(dep.routeCode + "/" + dep.directionIndex)) continue
                val trip = db.tripStops(dep.tripId)
                val bi = trip.indexOfFirst { it.number == o.stop.number && it.minutes == dep.minutes }
                if (bi < 0) continue
                val color = db.routeColor(dep.routeCode, v)
                for (j in bi + 1 until trip.size) {
                    val s = trip[j]; val num = s.number ?: continue
                    val d = dests[num]
                    if (d != null) {
                        val arrive = s.minutes + d.walk
                        results.add(directItinerary(o, dep, color, trip, bi, j, d, from, to, date))
                    }
                    // remember the earliest time we can be at this stop, for one transfer
                    val cur = transferPoints[num]
                    if (cur == null || s.minutes < cur.first)
                        transferPoints[num] = Triple(s.minutes, s, FirstLeg(o, dep, color, trip, bi, j))
                }
            }
        }

        // one interchange: board again where we alighted
        transferPoints.entries.sortedBy { it.value.first }.take(MAX_TRANSFER_POINTS).forEach { (num, tp) ->
            val (arrMin, tStop, first) = tp
            if (dests.containsKey(num)) return@forEach // already a direct destination
            val deps2 = db.departures(num, date, v).filter { it.minutes in (arrMin + MIN_TRANSFER_MIN)..(arrMin + WINDOW_MIN) }
            val seen2 = HashSet<String>()
            for (dep2 in deps2) {
                if (!seen2.add(dep2.routeCode + "/" + dep2.directionIndex)) continue
                if (dep2.routeCode == first.dep.routeCode) continue
                val trip2 = db.tripStops(dep2.tripId)
                val bi2 = trip2.indexOfFirst { it.number == num && it.minutes == dep2.minutes }
                if (bi2 < 0) continue
                val color2 = db.routeColor(dep2.routeCode, v)
                for (j in bi2 + 1 until trip2.size) {
                    val s = trip2[j]; val n2 = s.number ?: continue
                    val d = dests[n2] ?: continue
                    results.add(transferItinerary(first, dep2, color2, trip2, bi2, j, tStop, d, from, to, date))
                    break
                }
            }
        }

        return results.distinctBy { sig(it) }
            .sortedWith(compareBy({ it.arriveMinutes }, { it.transfers }, { it.departMinutes.let { d -> -d } }))
            .take(MAX_RESULTS)
    }

    private class FirstLeg(val o: Near, val dep: Departure, val color: Int,
                           val trip: List<TripStop>, val bi: Int, val ai: Int)

    private fun walkLeg(from: Place, name: String, lat: Double, lon: Double): WalkLeg {
        val m = dist(from.x, from.y, Geo.x(lon), Geo.y(lat)).roundToInt()
        return WalkLeg(from.name, name, m, (m / WALK_SPEED).roundToInt(), from.lat, from.lon, lat, lon)
    }

    private fun busLeg(dep: Departure, color: Int, trip: List<TripStop>, bi: Int, ai: Int, date: java.time.LocalDate): BusLeg {
        val b = trip[bi]; val a = trip[ai]
        return BusLeg(dep.routeCode, color, dep.towards, dep.tripId,
            b.number ?: 0, b.name, b.minutes, a.number ?: 0, a.name, a.minutes, date, ai - bi - 1)
    }

    private fun directItinerary(o: Near, dep: Departure, color: Int, trip: List<TripStop>, bi: Int, ai: Int,
                                d: Near, from: Place, to: Place, date: java.time.LocalDate): Itinerary {
        val b = trip[bi]; val a = trip[ai]
        val legs = listOf(
            walkLeg(from, b.name, o.stop.lat, o.stop.lon),
            busLeg(dep, color, trip, bi, ai, date),
            WalkLeg(a.name, to.name, d.meters, d.walk * 60, d.stop.lat, d.stop.lon, to.lat, to.lon),
        )
        return Itinerary(legs, dep.minutes - o.walk, a.minutes + d.walk, date)
    }

    private fun transferItinerary(first: FirstLeg, dep2: Departure, color2: Int, trip2: List<TripStop>,
                                  bi2: Int, ai2: Int, tStop: TripStop, d: Near, from: Place, to: Place,
                                  date: java.time.LocalDate): Itinerary {
        val b1 = first.trip[first.bi]
        val a2 = trip2[ai2]
        val legs = listOf(
            walkLeg(from, b1.name, first.o.stop.lat, first.o.stop.lon),
            busLeg(first.dep, first.color, first.trip, first.bi, first.ai, date),
            busLeg(dep2, color2, trip2, bi2, ai2, date),
            WalkLeg(a2.name, to.name, d.meters, d.walk * 60, d.stop.lat, d.stop.lon, to.lat, to.lon),
        )
        return Itinerary(legs, first.dep.minutes - first.o.walk, a2.minutes + d.walk, date)
    }

    private fun sig(it: Itinerary): String =
        it.busLegs.joinToString("|") { "${it.routeCode}:${it.boardNumber}>${it.alightNumber}@${it.boardMinutes}" }
}
