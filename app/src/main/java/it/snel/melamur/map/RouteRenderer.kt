package it.snel.melamur.map

import android.graphics.Path
import it.snel.melamur.data.BusRoute
import it.snel.melamur.data.BusStop
import it.snel.melamur.data.RouteDirection
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns a route into drawable geometry: one line PER direction, each in its own
 * colour (from route_shape.color) and offset to the LEFT of its travel
 * direction (Malta drives on the left), so the two directions sit on opposite
 * sides of the road. Direction arrows are placed along each line.
 */
class DirLine(val path: Path, val color: Int, val arrows: FloatArray /* x,y,bearing */)
class RouteGfx(val route: BusRoute, val lines: List<DirLine>)

object RouteRenderer {
    private const val STEP = 10f
    private const val OFFSET = 6f
    private const val ARROW_EVERY = 250f

    fun build(route: BusRoute, stops: Map<String, BusStop>): RouteGfx =
        RouteGfx(route, route.directions.map { trace(it) })

    private fun trace(d: RouteDirection): DirLine {
        val path = Path(); val arrows = ArrayList<Float>()
        val n = (d.shape.length / STEP).toInt() + 1
        var sinceArrow = ARROW_EVERY / 2
        for (k in 0 until n) {
            val p = d.shape.at(k * STEP)
            val br = Math.toRadians(p[2].toDouble())
            // left normal of travel direction (dx,dy)=(sin,cos) -> (-cos, sin)
            val x = p[0] - OFFSET * cos(br).toFloat(); val y = p[1] + OFFSET * sin(br).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            sinceArrow += STEP
            if (sinceArrow >= ARROW_EVERY) { arrows += x; arrows += y; arrows += p[2]; sinceArrow = 0f }
        }
        return DirLine(path, d.color, arrows.toFloatArray())
    }
}
