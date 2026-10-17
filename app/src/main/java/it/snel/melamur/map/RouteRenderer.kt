package it.snel.melamur.map

import android.graphics.Path
import it.snel.melamur.data.BusRoute
import it.snel.melamur.data.BusStop
import it.snel.melamur.data.RouteDirection
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns a route into drawable geometry. Where both directions use the same
 * road (and every stop has an opposite stop nearby) one line is drawn. Where
 * the directions deviate, either because the shapes are more than
 * [SPLIT_DIST] apart or because a stop has no opposite stop within
 * [OPPOSITE_STOP_DIST], the line splits into one line per direction, offset to
 * the left (left-hand traffic) and decorated with direction arrows.
 */
class RouteGfx(val route: BusRoute, val shared: Path, val split: Path, val arrows: FloatArray /* x,y,bearing */)

object RouteRenderer {
    const val SPLIT_DIST = 30f
    const val OPPOSITE_STOP_DIST = 120f
    private const val STEP = 10f
    private const val OFFSET = 6f
    private const val ARROW_EVERY = 250f

    fun build(route: BusRoute, stops: Map<String, BusStop>): RouteGfx {
        val shared = Path(); val split = Path(); val arrows = ArrayList<Float>()
        val dirs = route.directions
        dirs.forEachIndexed { i, a ->
            val b = if (dirs.size == 2) dirs[1 - i] else null
            val flags = splitFlags(a, b, stops)
            trace(a, flags, drawShared = i == 0, shared, split, arrows)
        }
        return RouteGfx(route, shared, split, arrows.toFloatArray())
    }

    private fun splitFlags(a: RouteDirection, b: RouteDirection?, stops: Map<String, BusStop>): BooleanArray {
        val n = (a.shape.length / STEP).toInt() + 1
        if (b == null) return BooleanArray(n) { true }  // loop / one direction only
        val f = BooleanArray(n) { k ->
            val p = a.shape.at(k * STEP)
            (b.shape.project(p[0], p[1])?.dist ?: Float.MAX_VALUE) > SPLIT_DIST
        }
        val opposite = b.stopIds.mapNotNull { stops[it] }
        for (sid in a.stopIds) {
            val s = stops[sid] ?: continue
            if (opposite.any { hypot(it.x - s.x, it.y - s.y) < OPPOSITE_STOP_DIST }) continue
            val along = a.shape.project(s.x, s.y)?.along ?: continue
            val k0 = ((along - 60f) / STEP).toInt().coerceAtLeast(0)
            val k1 = ((along + 60f) / STEP).toInt().coerceAtMost(n - 1)
            for (k in k0..k1) f[k] = true
        }
        return f
    }

    private fun trace(d: RouteDirection, f: BooleanArray, drawShared: Boolean,
                      shared: Path, split: Path, arrows: ArrayList<Float>) {
        var prev: Boolean? = null
        var sinceArrow = ARROW_EVERY / 2
        for (k in f.indices) {
            val p = d.shape.at(k * STEP)
            val isSplit = f[k]
            if (isSplit) {
                val br = Math.toRadians(p[2].toDouble())
                // left normal of travel direction (dx,dy)=(sin,cos) -> (-cos, sin)
                val x = p[0] - OFFSET * cos(br).toFloat(); val y = p[1] + OFFSET * sin(br).toFloat()
                if (prev != true) split.moveTo(x, y) else split.lineTo(x, y)
                sinceArrow += STEP
                if (sinceArrow >= ARROW_EVERY) { arrows += x; arrows += y; arrows += p[2]; sinceArrow = 0f }
            } else if (drawShared) {
                if (prev != false) shared.moveTo(p[0], p[1]) else shared.lineTo(p[0], p[1])
            }
            prev = isSplit
        }
    }
}
