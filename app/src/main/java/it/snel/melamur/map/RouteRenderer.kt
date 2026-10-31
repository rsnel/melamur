package it.snel.melamur.map

import android.graphics.Path
import it.snel.melamur.data.BusRoute
import it.snel.melamur.data.BusStop
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
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
class DirLine(val path: Path, val color: Int, val arrows: FloatArray /* x,y,bearing */, val branch: Boolean = false)
class RouteGfx(val route: BusRoute, val lines: List<DirLine>)

object RouteRenderer {
    private const val STEP = 10f          // sample spacing along a shape, metres
    private const val OFFSET = 6f         // diverging parts: offset to the left
    private const val ARROW_EVERY = 250f
    private const val MERGE = 35f         // opposite parts closer than this are drawn as one
    private const val CELL = 40f
    private const val MIN_SHARED = 5      // samples: shorter shared bits stay separate ...
    private const val MIN_APART = 3       // ... and shorter gaps between shared bits are bridged
    private const val MIN_ARROW_RUN = 4   // samples: diverging stretches shorter than this get no arrow

    fun build(route: BusRoute, @Suppress("UNUSED_PARAMETER") stops: Map<String, BusStop>): RouteGfx {
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

            val path = Path(); val arrows = ArrayList<Float>()
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
            // arrows on the diverging stretches only: about one per ARROW_EVERY, at least one each
            var k = 0
            while (k < n) {
                if (shared[k]) { k++; continue }
                var e = k
                while (e < n && !shared[e]) e++
                val len = e - k
                if (len >= MIN_ARROW_RUN) {
                    val cnt = maxOf(1, (len * STEP / ARROW_EVERY).roundToInt())
                    for (m in 0 until cnt) {
                        val i = k + ((m + 0.5f) * len / cnt).toInt().coerceIn(0, len - 1)
                        arrows += xs[i]; arrows += ys[i]; arrows += ss[i][2]
                    }
                }
                k = e
            }
            DirLine(path, d.color, arrows.toFloatArray(), branch = d.id.startsWith("v"))
        }
        return RouteGfx(route, lines)
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
