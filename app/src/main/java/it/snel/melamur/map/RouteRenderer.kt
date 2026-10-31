package it.snel.melamur.map

import android.graphics.Path
import it.snel.melamur.data.BusRoute
import it.snel.melamur.data.BusStop
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns a route into drawable geometry, one line per direction (and per variant
 * branch), in its own colour.
 *
 * Where a direction runs close to a part that goes the other way (the same road
 * in both directions, or the two carriageways of one road), both are drawn as a
 * single line at their average position, without arrows: there the direction
 * is obvious from the rest. Where the directions diverge (one-way loops, a
 * different road back), each line is offset to the LEFT of its travel
 * direction (Malta drives on the left) and carries direction arrows, at least
 * one per diverging stretch.
 */
/**
 * A stretch of a line that does not share its road with the other direction:
 * points every [RouteRenderer.STEP] metres as x,y,bearing triples, plus its extent.
 * The map spreads direction arrows over these at a constant density on screen.
 */
class ArrowRun(val pts: FloatArray, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val count get() = pts.size / 3
}
class DirLine(val path: Path, val color: Int, val runs: List<ArrowRun>, val branch: Boolean = false)
/** A stop the line calls at: where it is and which way the bus leaves it (NaN if unknown). */
class LineStop(val id: String, val x: Float, val y: Float, val name: String, val bearing: Float, val color: Int,
               val label: String = name)
/**
 * [stops] of every main direction; [partner] pairs a stop with the stop of the same name
 * served in the other direction nearby (index into [stops], -1 if none), so the
 * map can draw the pair as one when they would overlap.
 */
class RouteGfx(val route: BusRoute, val lines: List<DirLine>,
               val stops: List<LineStop> = emptyList(), val partner: IntArray = IntArray(0))

object RouteRenderer {
    const val STEP = 10f                  // sample spacing along a shape, metres
    private const val OFFSET = 6f         // diverging parts: offset to the left
    private const val MERGE = 35f         // opposite parts closer than this are drawn as one
    private const val CELL = 40f
    private const val MIN_SHARED = 5      // samples: shorter shared bits stay separate ...
    private const val MIN_APART = 3       // ... and shorter gaps between shared bits are bridged
    private const val MIN_ARROW_RUN = 4   // samples: diverging stretches shorter than this get no arrows

    fun build(route: BusRoute, stops: Map<String, BusStop>): RouteGfx {
        val dirs = route.directions
        // samples per direction: x, y, bearing
        val samples = dirs.map { d ->
            val n = (d.shape.length / STEP).toInt() + 1
            Array(n) { k -> d.shape.at(k * STEP) }
        }
        // grid over all samples, to find nearby samples of other directions fast
        val grid = HashMap<Long, MutableList<Int>>()   // packed (dir shl 20) or index
        fun key(x: Float, y: Float) = (floor(x / CELL).toLong() shl 32) xor (floor(y / CELL).toLong() and 0xffffffffL)
        samples.forEachIndexed { di, ss -> ss.forEachIndexed { k, p -> grid.getOrPut(key(p[0], p[1])) { ArrayList() }.add((di shl 20) or k) } }

        val lines = dirs.mapIndexed { di, d ->
            val ss = samples[di]
            val n = ss.size
            val shared = BooleanArray(n)
            val mx = FloatArray(n); val my = FloatArray(n); val found = BooleanArray(n)
            for (k in 0 until n) {
                val p = ss[k]
                val gx = floor(p[0] / CELL).toLong(); val gy = floor(p[1] / CELL).toLong()
                var best = MERGE; var bx = 0f; var by = 0f
                for (ix in -1..1) for (iy in -1..1) {
                    val cell = grid[((gx + ix) shl 32) xor ((gy + iy) and 0xffffffffL)] ?: continue
                    for (e in cell) {
                        val dj = e ushr 20
                        if (dj == di) continue
                        val q = samples[dj][e and 0xfffff]
                        if (angleDiff(p[2], q[2]) < 135f) continue   // only parts going the other way
                        val dist = hypot(q[0] - p[0], q[1] - p[1])
                        if (dist < best) { best = dist; bx = q[0]; by = q[1] }
                    }
                }
                if (best < MERGE) { shared[k] = true; found[k] = true; mx[k] = (p[0] + bx) / 2; my[k] = (p[1] + by) / 2 }
            }
            // smooth the on/off pattern: bridge short gaps, then drop short shared bits
            fillRuns(shared, false, MIN_APART, betweenOnly = true)
            fillRuns(shared, true, MIN_SHARED, betweenOnly = false)
            // shared samples that were bridged have no partner: interpolate their midpoints
            for (k in 0 until n) if (shared[k] && !found[k]) {
                mx[k] = ss[k][0]; my[k] = ss[k][1]
            }
            // smooth the midpoints a little (they come from 10 m samples on both lines)
            val sx = mx.copyOf(); val sy = my.copyOf()
            for (k in 0 until n) if (shared[k]) {
                var tx = 0f; var ty = 0f; var c = 0
                for (j in maxOf(0, k - 2)..minOf(n - 1, k + 2)) if (shared[j]) { tx += mx[j]; ty += my[j]; c++ }
                sx[k] = tx / c; sy[k] = ty / c
            }

            val path = Path(); val runs = ArrayList<ArrowRun>()
            val xs = FloatArray(n); val ys = FloatArray(n)
            for (k in 0 until n) {
                val p = ss[k]
                if (shared[k]) { xs[k] = sx[k]; ys[k] = sy[k] }
                else {
                    val br = Math.toRadians(p[2].toDouble())
                    // left normal of travel direction (dx,dy)=(sin,cos) -> (-cos, sin)
                    xs[k] = p[0] - OFFSET * cos(br).toFloat(); ys[k] = p[1] + OFFSET * sin(br).toFloat()
                }
                if (k == 0) path.moveTo(xs[k], ys[k]) else path.lineTo(xs[k], ys[k])
            }
            // the diverging stretches, where the map puts direction arrows
            var k = 0
            while (k < n) {
                if (shared[k]) { k++; continue }
                var e = k
                while (e < n && !shared[e]) e++
                val len = e - k
                if (len >= MIN_ARROW_RUN) {
                    val pts = FloatArray(len * 3)
                    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                    for (i in 0 until len) {
                        val x = xs[k + i]; val y = ys[k + i]
                        pts[3 * i] = x; pts[3 * i + 1] = y; pts[3 * i + 2] = ss[k + i][2]
                        if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                    }
                    runs += ArrowRun(pts, x0, y0, x1, y1)
                }
                k = e
            }
            DirLine(path, d.color, runs, branch = d.id.startsWith("v"))
        }
        val (ls, partner) = lineStops(route, stops)
        return RouteGfx(route, lines, ls, partner)
    }

    private const val PAIR = 250f   // same-name stops of both directions closer than this can pair

    private fun lineStops(route: BusRoute, stops: Map<String, BusStop>): Pair<List<LineStop>, IntArray> {
        val out = ArrayList<LineStop>(); val dirOf = ArrayList<Int>()
        route.directions.forEachIndexed { di, d ->
            var along = -1f
            for (id in d.stopIds) {
                val s = stops[id] ?: continue
                // departure direction: the shape's bearing where the bus passes the stop
                // (searching onwards from the previous stop, so loops come out right)
                val p = d.shape.project(s.x, s.y, maxOf(-1f, along - 30f))?.takeIf { it.dist < 80f }
                    ?: d.shape.project(s.x, s.y)?.takeIf { it.dist < 80f }
                if (p != null) along = p.along
                out += LineStop(id, s.x, s.y, s.name, p?.bearing ?: Float.NaN, d.color, s.mapName)
                dirOf += di
            }
        }
        // pair each stop with the nearest same-name stop of another direction
        val partner = IntArray(out.size) { -1 }
        val byName = out.indices.groupBy { out[it].name.trim().lowercase() }
        for (g in byName.values) {
            if (g.size < 2) continue
            val cand = ArrayList<Triple<Float, Int, Int>>()
            for (a in g) for (b in g) if (a < b && dirOf[a] != dirOf[b] && out[a].id != out[b].id) {
                val dd = hypot(out[a].x - out[b].x, out[a].y - out[b].y)
                if (dd < PAIR) cand += Triple(dd, a, b)
            }
            for ((_, a, b) in cand.sortedBy { it.first })
                if (partner[a] < 0 && partner[b] < 0) { partner[a] = b; partner[b] = a }
        }
        return out to partner
    }

    /**
     * Flip runs of [value] shorter than [min] to the opposite value. With
     * [betweenOnly], only runs that have the opposite value on both sides.
     */
    private fun fillRuns(a: BooleanArray, value: Boolean, min: Int, betweenOnly: Boolean) {
        var k = 0
        while (k < a.size) {
            if (a[k] != value) { k++; continue }
            var e = k
            while (e < a.size && a[e] == value) e++
            val inside = k > 0 && e < a.size
            if (e - k < min && (!betweenOnly || inside)) for (j in k until e) a[j] = !value
            k = e
        }
    }

    private fun angleDiff(a: Float, b: Float): Float {
        val d = abs(((a - b) % 360f + 360f) % 360f)
        return if (d > 180f) 360f - d else d
    }
}
