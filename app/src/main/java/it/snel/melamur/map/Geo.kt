package it.snel.melamur.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Local equirectangular projection in metres; must match tools/convert_gpkg.py. */
object Geo {
    const val LAT0 = 35.9
    const val LON0 = 14.4
    private val MX = 111320.0 * cos(Math.toRadians(LAT0))
    private const val MY = 110540.0

    fun x(lon: Double) = ((lon - LON0) * MX).toFloat()
    fun y(lat: Double) = ((lat - LAT0) * MY).toFloat()
    fun lon(x: Float) = x / MX + LON0
    fun lat(y: Float) = y / MY + LAT0

    /** Compass bearing (0 = north, clockwise) of vector (dx, dy). */
    fun bearing(dx: Float, dy: Float): Float =
        ((Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())) + 360.0) % 360.0).toFloat()

    fun angleDiff(a: Float, b: Float): Float {
        val d = ((a - b) % 360f + 540f) % 360f - 180f
        return kotlin.math.abs(d)
    }
}

/** A polyline in projected metres with cumulative length, for map matching. */
class Polyline(val xs: FloatArray, val ys: FloatArray) {
    val cum = FloatArray(xs.size)
    init {
        for (i in 1 until xs.size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }
    val length get() = if (cum.isEmpty()) 0f else cum.last()

    class Proj(val dist: Float, val along: Float, val bearing: Float, val x: Float, val y: Float)

    /** Project (px,py); optionally only consider positions with along >= minAlong. */
    fun project(px: Float, py: Float, minAlong: Float = -1f): Proj? {
        var best: Proj? = null
        for (i in 0 until xs.size - 1) {
            if (cum[i + 1] < minAlong) continue
            val ax = xs[i]; val ay = ys[i]; val dx = xs[i + 1] - ax; val dy = ys[i + 1] - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0f) 0f else max(0f, min(1f, ((px - ax) * dx + (py - ay) * dy) / l2))
            val qx = ax + t * dx; val qy = ay + t * dy
            val d = hypot(px - qx, py - qy)
            if (best == null || d < best.dist)
                best = Proj(d, cum[i] + t * (cum[i + 1] - cum[i]), Geo.bearing(dx, dy), qx, qy)
        }
        return best
    }

    /** Point and bearing at a given distance along the line. */
    fun at(along: Float): FloatArray {
        var i = 0
        while (i < xs.size - 2 && cum[i + 1] < along) i++
        val seg = cum[i + 1] - cum[i]
        val t = if (seg == 0f) 0f else ((along - cum[i]) / seg).coerceIn(0f, 1f)
        val dx = xs[i + 1] - xs[i]; val dy = ys[i + 1] - ys[i]
        return floatArrayOf(xs[i] + t * dx, ys[i] + t * dy, Geo.bearing(dx, dy))
    }
}
