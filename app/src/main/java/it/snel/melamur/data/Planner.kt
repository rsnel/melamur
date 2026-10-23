package it.snel.melamur.data

import it.snel.melamur.map.Geo
import java.time.LocalDateTime
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A planner endpoint (a stop, address, landmark or POI). */
data class Place(val name: String, val lat: Double, val lon: Double) {
    val x = Geo.x(lon); val y = Geo.y(lat)
}

/** Routing engine. All three run on the same network; see Raptor/Csa/McRaptor. */
enum class Engine { RAPTOR, CSA, MCRAPTOR }

/** Per-engine result for the comparison view. */
class EngineResult(val engine: Engine, val millis: Double, val itineraries: List<Itinerary>) {
    val bestArrive: Int? get() = itineraries.minOfOrNull { it.arriveMinutes }
}

/**
 * Journey planner over RAPTOR, CSA and McRAPTOR (all with walking footpaths for
 * origin access, inter-stop transfers and destination egress). [compare] runs
 * all three for timing/agreement; [plan] returns one engine's itineraries.
 */
object Planner {
    private const val WALK_SPEED = RaptorData.WALK_SPEED
    private const val MAX_ACCESS_M = 1000.0
    private const val NEAR = 8
    private const val DIRECT_WALK_M = 1500.0

    @Volatile private var cacheKey = ""
    @Volatile private var cache: RaptorData? = null

    private fun data(db: MptDb, allStops: List<Stop>, v: Int, bit: Int): RaptorData {
        val key = "$v/$bit"
        cache?.let { if (cacheKey == key) return it }
        val d = RaptorData.build(db.loadPatterns(v, bit), allStops)
        cache = d; cacheKey = key; return d
    }

    private fun walkSec(m: Double) = (m / WALK_SPEED).roundToInt()
    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot((ax - bx).toDouble(), (ay - by).toDouble())

    private fun near(d: RaptorData, p: Place): List<IntArray> {
        val hits = ArrayList<IntArray>()
        for (i in 0 until d.nStops) {
            if (d.lat[i].isNaN()) continue
            val m = dist(p.x, p.y, Geo.x(d.lon[i]), Geo.y(d.lat[i]))
            if (m <= MAX_ACCESS_M) hits.add(intArrayOf(i, walkSec(m)))
        }
        return hits.sortedBy { it[1] }.take(NEAR)
    }

    private fun runEngine(engine: Engine, d: RaptorData, access: List<IntArray>, egress: Map<Int, Int>, departMin: Int) =
        when (engine) {
            Engine.RAPTOR -> Raptor.query(d, access, egress, departMin)
            Engine.CSA -> Csa.query(d, access, egress, departMin)
            Engine.MCRAPTOR -> McRaptor.query(d, access, egress, departMin)
        }

    fun plan(db: MptDb, allStops: List<Stop>, from: Place, to: Place, departAt: LocalDateTime,
             engine: Engine = Engine.RAPTOR, onProgress: ((String) -> Unit)? = null): List<Itinerary> {
        onProgress?.invoke("Loading timetable…")
        val ctx = prepare(db, allStops, from, to, departAt, onProgress) ?: return directOnly(from, to, departAt)
        onProgress?.invoke("Running ${engine.name} + walking…")
        val res = finalize(ctx, runEngine(engine, ctx.d, ctx.access, ctx.egress, ctx.departMin), from, to)
        onProgress?.invoke("Found ${res.size} option${if (res.size == 1) "" else "s"}")
        return res
    }

    /** Run all three engines, timing each; itineraries are converted identically. */
    fun compare(db: MptDb, allStops: List<Stop>, from: Place, to: Place, departAt: LocalDateTime,
                onProgress: ((String) -> Unit)? = null): List<EngineResult> {
        onProgress?.invoke("Loading timetable…")
        val ctx = prepare(db, allStops, from, to, departAt, onProgress) ?: return Engine.values().map {
            EngineResult(it, 0.0, directOnly(from, to, departAt))
        }
        return Engine.values().map { e ->
            onProgress?.invoke("Running ${e.name}…")
            val t0 = System.nanoTime()
            val journeys = runEngine(e, ctx.d, ctx.access, ctx.egress, ctx.departMin)
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            val r = EngineResult(e, ms, finalize(ctx, journeys, from, to))
            onProgress?.invoke("%s: %.0f ms → %s".format(e.name, ms, r.bestArrive?.let { Malta.hhmm(it) } ?: "—"))
            r
        }
    }

    private class Ctx(val d: RaptorData, val db: MptDb, val v: Int, val access: List<IntArray>,
                      val egress: Map<Int, Int>, val departMin: Int, val date: java.time.LocalDate,
                      val byNum: Map<Int, Stop>)

    private fun prepare(db: MptDb, allStops: List<Stop>, from: Place, to: Place, departAt: LocalDateTime,
                        onProgress: ((String) -> Unit)? = null): Ctx? {
        val date = departAt.toLocalDate()
        val v = db.versionFor(date); val bit = Malta.weekdayBit(date)
        val departMin = departAt.hour * 60 + departAt.minute
        onProgress?.invoke("Building the network…")
        val d = data(db, allStops, v, bit)
        onProgress?.invoke("${d.nStops} stops in service")
        val access = near(d, from); val egress = near(d, to).associate { it[0] to it[1] }
        onProgress?.invoke("${access.size} stops near start · ${egress.size} near destination")
        if (access.isEmpty() || egress.isEmpty()) return null
        return Ctx(d, db, v, access, egress, departMin, date, allStops.associateBy { it.number })
    }

    private fun finalize(ctx: Ctx, journeys: List<RJourney>, from: Place, to: Place): List<Itinerary> {
        val dirNames = HashMap<String, Map<Int, String>>()
        val colors = HashMap<String, Pair<Map<Int, Int>, Int>>()
        fun towards(code: String, dir: Int) = dirNames.getOrPut(code) { ctx.db.directionNames(code, ctx.v) }[dir] ?: ""
        fun colorOf(code: String, dir: Int): Int {
            val (m, fb) = colors.getOrPut(code) { ctx.db.shapeColors(code, ctx.v) to ctx.db.routeColor(code, ctx.v) }
            return m[dir] ?: fb
        }
        val out = ArrayList<Itinerary>()
        for (j in journeys) if (j.segs.isNotEmpty())
            out.add(toItinerary(ctx.d, j, from, to, ctx.byNum, ctx.date, ctx.departMin, ::towards, ::colorOf))
        val wd = dist(from.x, from.y, to.x, to.y)
        if (wd <= DIRECT_WALK_M) out.add(walkItinerary(from, to, ctx.departMin, ctx.date, wd))
        return out.distinctBy { sig(it) }.sortedWith(compareBy({ it.arriveMinutes }, { it.transfers })).take(8)
    }

    private fun directOnly(from: Place, to: Place, departAt: LocalDateTime): List<Itinerary> {
        val wd = dist(from.x, from.y, to.x, to.y)
        return if (wd <= DIRECT_WALK_M * 2) listOf(walkItinerary(from, to, departAt.hour * 60 + departAt.minute, departAt.toLocalDate(), wd)) else emptyList()
    }

    private fun walkItinerary(from: Place, to: Place, departMin: Int, date: java.time.LocalDate, meters: Double): Itinerary {
        val sec = walkSec(meters)
        val leg = WalkLeg(from.name, to.name, meters.roundToInt(), sec, from.lat, from.lon, to.lat, to.lon)
        return Itinerary(listOf(leg), departMin, departMin + (sec + 59) / 60, date)
    }

    private fun toItinerary(
        d: RaptorData, j: RJourney, from: Place, to: Place, byNum: Map<Int, Stop>,
        date: java.time.LocalDate, departMin: Int,
        towards: (String, Int) -> String, colorOf: (String, Int) -> Int,
    ): Itinerary {
        val legs = ArrayList<Leg>()
        fun coord(stopIdx: Int) = doubleArrayOf(d.lat[stopIdx], d.lon[stopIdx])
        fun name(num: Int) = byNum[num]?.display ?: "Stop #$num"

        val accC = coord(j.accessStop)
        legs.add(WalkLeg(from.name, name(d.stopNums[j.accessStop]),
            dist(from.x, from.y, Geo.x(accC[1]), Geo.y(accC[0])).roundToInt(), j.accessSec,
            from.lat, from.lon, accC[0], accC[1]))

        var firstBoardMin = -1
        for (seg in j.segs) when (seg) {
            is SRide -> {
                val (code, dir) = d.routeMeta[seg.route]
                val bNum = d.stopNums[d.routeStops[seg.route][seg.boardPos]]
                val aNum = d.stopNums[d.routeStops[seg.route][seg.alightPos]]
                val bMin = d.routeTripsMin[seg.route][seg.trip][seg.boardPos]
                val aMin = d.routeTripsMin[seg.route][seg.trip][seg.alightPos]
                if (firstBoardMin < 0) firstBoardMin = bMin
                legs.add(BusLeg(code, colorOf(code, dir), towards(code, dir),
                    d.routeTripIds[seg.route][seg.trip], dir,
                    bNum, name(bNum), bMin, aNum, name(aNum), aMin, date, seg.alightPos - seg.boardPos - 1))
            }
            is STransfer -> {
                val f = coord(seg.fromStop); val t = coord(seg.toStop)
                legs.add(WalkLeg(name(d.stopNums[seg.fromStop]), name(d.stopNums[seg.toStop]),
                    dist(Geo.x(f[1]), Geo.y(f[0]), Geo.x(t[1]), Geo.y(t[0])).roundToInt(), seg.sec,
                    f[0], f[1], t[0], t[1]))
            }
        }
        val egC = coord(j.egressStop)
        legs.add(WalkLeg(name(d.stopNums[j.egressStop]), to.name,
            dist(Geo.x(egC[1]), Geo.y(egC[0]), to.x, to.y).roundToInt(), j.egressSec,
            egC[0], egC[1], to.lat, to.lon))

        val dep = if (firstBoardMin >= 0) firstBoardMin - (j.accessSec + 59) / 60 else departMin
        return Itinerary(legs, dep, j.arriveMin, date)
    }

    private fun sig(it: Itinerary): String =
        if (it.busLegs.isEmpty()) "walk"
        else it.busLegs.joinToString("|") { "${it.routeCode}:${it.boardNumber}>${it.alightNumber}@${it.boardMinutes}" }
}
