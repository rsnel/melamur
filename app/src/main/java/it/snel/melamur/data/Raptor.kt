package it.snel.melamur.data

import it.snel.melamur.map.Geo
import kotlin.math.hypot

/** Raw timetable pattern loaded from the DB (one RAPTOR "route" = trips sharing a stop sequence). */
class RawTrip(val tripId: Long, val minutes: IntArray)
class RawRoute(val routeCode: String, val dir: Int, val stops: IntArray /* stop numbers */, val trips: List<RawTrip>)

/**
 * In-memory network for RAPTOR, built once per dataset version/weekday.
 *
 * RAPTOR (Delling, Pajor, Werneck): round-based, no graph. Round k computes the
 * earliest arrival at every stop using at most k trips. Walking is modelled as
 * footpaths (transfers) between nearby stops, plus origin-access and
 * destination-egress footpaths supplied per query — i.e. RAPTOR with walking
 * (what McRAPTOR formalises). The round structure makes the result Pareto over
 * (arrival time, number of trips): each extra trip is offered only if it
 * arrives earlier.
 */
class RaptorData(
    val stopNums: IntArray,
    val idxOf: Map<Int, Int>,
    val lat: DoubleArray, val lon: DoubleArray,            // NaN when unknown
    val routeStops: Array<IntArray>,                       // route -> stop indices
    val routeTripsMin: Array<Array<IntArray>>,             // route -> trip -> minute per position
    val routeTripIds: Array<LongArray>,                    // route -> trip ids
    val routeMeta: Array<Pair<String, Int>>,               // route -> (routeCode, direction)
    val routesAtStop: Array<IntArray>,                     // stop -> flattened [routeIdx, pos, ...]
    val transfers: Array<IntArray>,                        // stop -> flattened [toIdx, walkSec, ...]
    val conn: Connections,                                 // elementary connections, sorted by departure (for CSA)
) {
    val nStops get() = stopNums.size

    /** Elementary connections for CSA: parallel arrays sorted by departure minute. */
    class Connections(
        val depT: IntArray, val arrT: IntArray, val depStop: IntArray, val arrStop: IntArray,
        val route: IntArray, val trip: IntArray, val pos: IntArray,
    ) { val size get() = depT.size }

    companion object {
        const val WALK_SPEED = 1.3
        private const val TRANSFER_M = 250.0
        private const val GRID = 250.0
        private const val MAX_NEIGHBOURS = 8

        fun build(raw: List<RawRoute>, stops: List<Stop>): RaptorData {
            val nums = LinkedHashSet<Int>()
            stops.forEach { nums.add(it.number) }
            raw.forEach { r -> r.stops.forEach { nums.add(it) } }
            val stopNums = nums.toIntArray()
            val idxOf = HashMap<Int, Int>(stopNums.size)
            stopNums.forEachIndexed { i, n -> idxOf[n] = i }
            val n = stopNums.size
            val lat = DoubleArray(n) { Double.NaN }; val lon = DoubleArray(n) { Double.NaN }
            val byNum = stops.associateBy { it.number }
            for (i in 0 until n) byNum[stopNums[i]]?.let { lat[i] = it.lat; lon[i] = it.lon }

            val routeStops = Array(raw.size) { r -> IntArray(raw[r].stops.size) { idxOf[raw[r].stops[it]]!! } }
            val routeTripsMin = Array(raw.size) { r -> Array(raw[r].trips.size) { raw[r].trips[it].minutes } }
            val routeTripIds = Array(raw.size) { r -> LongArray(raw[r].trips.size) { raw[r].trips[it].tripId } }
            val routeMeta = Array(raw.size) { r -> raw[r].routeCode to raw[r].dir }

            val ras = Array(n) { ArrayList<Int>() }
            for (r in routeStops.indices) routeStops[r].forEachIndexed { pos, s -> ras[s].add(r); ras[s].add(pos) }
            val routesAtStop = Array(n) { ras[it].toIntArray() }

            return RaptorData(stopNums, idxOf, lat, lon, routeStops, routeTripsMin, routeTripIds, routeMeta,
                routesAtStop, buildTransfers(n, lat, lon), buildConnections(routeStops, routeTripsMin))
        }

        private fun buildConnections(routeStops: Array<IntArray>, routeTripsMin: Array<Array<IntArray>>): Connections {
            var count = 0
            for (r in routeStops.indices) count += routeTripsMin[r].size * (routeStops[r].size - 1).coerceAtLeast(0)
            val depT = IntArray(count); val arrT = IntArray(count); val depS = IntArray(count); val arrS = IntArray(count)
            val rt = IntArray(count); val tp = IntArray(count); val ps = IntArray(count)
            var i = 0
            for (r in routeStops.indices) {
                val stops = routeStops[r]
                for (t in routeTripsMin[r].indices) {
                    val m = routeTripsMin[r][t]
                    for (p in 0 until stops.size - 1) {
                        depT[i] = m[p]; arrT[i] = m[p + 1]; depS[i] = stops[p]; arrS[i] = stops[p + 1]
                        rt[i] = r; tp[i] = t; ps[i] = p; i++
                    }
                }
            }
            val order = (0 until count).sortedBy { depT[it] }.toIntArray()
            fun reorder(a: IntArray) = IntArray(count) { a[order[it]] }
            return Connections(reorder(depT), reorder(arrT), reorder(depS), reorder(arrS), reorder(rt), reorder(tp), reorder(ps))
        }

        private fun buildTransfers(n: Int, lat: DoubleArray, lon: DoubleArray): Array<IntArray> {
            val x = DoubleArray(n); val y = DoubleArray(n)
            val grid = HashMap<Long, ArrayList<Int>>()
            for (i in 0 until n) if (!lat[i].isNaN()) {
                x[i] = Geo.x(lon[i]).toDouble(); y[i] = Geo.y(lat[i]).toDouble()
                val key = cell(x[i], y[i]); grid.getOrPut(key) { ArrayList() }.add(i)
            }
            return Array(n) { i ->
                if (lat[i].isNaN()) return@Array IntArray(0)
                val cx = (x[i] / GRID).toInt(); val cy = (y[i] / GRID).toInt()
                val found = ArrayList<IntArray>()
                for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1)
                    grid[gx.toLong() * 1_000_000L + gy]?.forEach { j ->
                        if (j != i) {
                            val d = hypot(x[i] - x[j], y[i] - y[j])
                            if (d <= TRANSFER_M) found.add(intArrayOf(j, Math.ceil(d / WALK_SPEED).toInt()))
                        }
                    }
                found.sortBy { it[1] }
                val take = found.take(MAX_NEIGHBOURS)
                IntArray(take.size * 2) { k -> if (k % 2 == 0) take[k / 2][0] else take[k / 2][1] }
            }
        }

        private fun cell(x: Double, y: Double) = (x / GRID).toInt().toLong() * 1_000_000L + (y / GRID).toInt()
    }
}

/** One journey from a RAPTOR query: a sequence of ride and walk-transfer segments. */
sealed class Seg
class SRide(val route: Int, val trip: Int, val boardPos: Int, val alightPos: Int) : Seg()
class STransfer(val fromStop: Int, val toStop: Int, val sec: Int) : Seg()

class RJourney(
    val rounds: Int, val arriveMin: Int,
    val accessStop: Int, val accessSec: Int,
    val egressStop: Int, val egressSec: Int,
    val segs: List<Seg>,
)

object Raptor {
    private const val INF = Int.MAX_VALUE / 4
    private fun ceilMin(sec: Int) = (sec + 59) / 60

    private sealed class Par
    private class Access(val sec: Int) : Par()
    private class Board(val route: Int, val trip: Int, val boardPos: Int, val alightPos: Int) : Par()
    private class Walk(val from: Int, val sec: Int) : Par()

    /**
     * @param access list of [stopIdx, walkSec] from the origin
     * @param egress map stopIdx -> walkSec to the destination
     * @return one journey per Pareto round (fewer trips → later arrival), earliest first
     */
    fun query(d: RaptorData, access: List<IntArray>, egress: Map<Int, Int>,
              departMin: Int, maxRounds: Int = 6): List<RJourney> {
        val n = d.nStops
        val tau = Array(maxRounds + 1) { IntArray(n) { INF } }
        val par = Array(maxRounds + 1) { arrayOfNulls<Par>(n) }
        val best = IntArray(n) { INF }
        var marked = BooleanArray(n)
        var targetBest = INF

        for (a in access) {
            val s = a[0]; val t = departMin + ceilMin(a[1])
            if (t < tau[0][s]) { tau[0][s] = t; best[s] = t; par[0][s] = Access(a[1]); marked[s] = true }
        }
        val out = ArrayList<RJourney>()
        fun checkTarget(k: Int) {
            var bestE = -1; var bestArr = targetBest
            for ((e, w) in egress) { val arr = tau[k][e] + ceilMin(w); if (arr < bestArr) { bestArr = arr; bestE = e } }
            if (bestE >= 0) { targetBest = bestArr; out.add(reconstruct(d, par, k, bestE, egress[bestE]!!, bestArr)) }
        }
        checkTarget(0)

        for (k in 1..maxRounds) {
            System.arraycopy(tau[k - 1], 0, tau[k], 0, n)
            val prev = marked; marked = BooleanArray(n)
            // routes to scan: earliest marked position per route
            val startPos = HashMap<Int, Int>()
            for (s in 0 until n) if (prev[s]) {
                val ra = d.routesAtStop[s]; var i = 0
                while (i < ra.size) { val r = ra[i]; val pos = ra[i + 1]; val c = startPos[r]
                    if (c == null || pos < c) startPos[r] = pos; i += 2 }
            }
            for ((r, sp) in startPos) {
                val stops = d.routeStops[r]; val trips = d.routeTripsMin[r]
                var trip = -1; var boardPos = -1
                for (pos in sp until stops.size) {
                    val s = stops[pos]
                    if (trip >= 0) {
                        val arr = trips[trip][pos]; val bound = minOf(best[s], targetBest)
                        if (arr < bound) {
                            tau[k][s] = arr; best[s] = arr; par[k][s] = Board(r, trip, boardPos, pos); marked[s] = true
                        }
                    }
                    val ready = tau[k - 1][s]
                    if (ready != INF) {
                        val et = earliestTrip(trips, pos, ready)
                        if (et >= 0 && (trip < 0 || trips[et][pos] < trips[trip][pos])) { trip = et; boardPos = pos }
                    }
                }
            }
            // footpaths
            for (s in 0 until n) if (marked[s]) {
                val tr = d.transfers[s]; var i = 0
                while (i < tr.size) {
                    val q = tr[i]; val w = tr[i + 1]; val cand = tau[k][s] + ceilMin(w)
                    if (cand < minOf(best[q], targetBest)) { tau[k][q] = cand; best[q] = cand; par[k][q] = Walk(s, w); marked[q] = true }
                    i += 2
                }
            }
            checkTarget(k)
            if (marked.none { it }) break
        }
        return out
    }

    private fun earliestTrip(trips: Array<IntArray>, pos: Int, ready: Int): Int {
        // trips sorted by departure; first whose time at pos >= ready (FIFO, no overtaking)
        for (t in trips.indices) if (trips[t][pos] >= ready) return t
        return -1
    }

    private fun reconstruct(d: RaptorData, par: Array<Array<Par?>>, k0: Int, e: Int, egressSec: Int, arriveMin: Int): RJourney {
        val rev = ArrayList<Seg>()
        var ck = k0; var cs = e; var accessStop = e; var accessSec = 0
        while (true) {
            val p = par[ck][cs] ?: break
            when (p) {
                is Board -> { rev.add(SRide(p.route, p.trip, p.boardPos, p.alightPos)); cs = d.routeStops[p.route][p.boardPos]; ck -= 1 }
                is Walk -> { rev.add(STransfer(p.from, cs, p.sec)); cs = p.from }
                is Access -> { accessStop = cs; accessSec = p.sec; break }
            }
        }
        return RJourney(k0, arriveMin, accessStop, accessSec, e, egressSec, rev.reversed())
    }
}
