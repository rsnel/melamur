package it.snel.melamur.map

import android.content.Context
import android.graphics.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Vector base map from assets/map.bin (written by tools/convert_gpkg.py).
 * Features are merged into one [Path] per (style, 1 km cell) so that drawing is
 * a few hundred drawPath calls with cheap viewport culling.
 */
class MapData(ctx: Context) {
    class Bucket(val style: Int, val path: Path, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    val buckets: Map<Int, List<Bucket>>

    init {
        val bytes = ctx.assets.open("map.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.int == 0x524d4c4d) { "bad map.bin" } // "MLMR"
        bb.double; bb.double
        val n = bb.int
        class Acc(val path: Path = Path(), var x0: Float = Float.MAX_VALUE, var y0: Float = Float.MAX_VALUE,
                  var x1: Float = -Float.MAX_VALUE, var y1: Float = -Float.MAX_VALUE)
        val acc = HashMap<Long, Acc>()
        var big = 0L
        repeat(n) {
            bb.get() // layer
            val style = bb.get().toInt()
            val np = bb.short.toInt() and 0xffff
            val xs = FloatArray(np); val ys = FloatArray(np)
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in 0 until np) {
                xs[i] = bb.short.toFloat(); ys[i] = bb.short.toFloat()
                if (xs[i] < x0) x0 = xs[i]; if (xs[i] > x1) x1 = xs[i]
                if (ys[i] < y0) y0 = ys[i]; if (ys[i] > y1) y1 = ys[i]
            }
            val key = if (x1 - x0 > CELL || y1 - y0 > CELL) (1L shl 40) + big++ else
                ((((x0 + x1) / 2 / CELL).toLong() + 100) shl 16) or (((y0 + y1) / 2 / CELL).toLong() + 100)
            val a = acc.getOrPut((style.toLong() shl 48) or key) { Acc() }
            a.path.moveTo(xs[0], ys[0])
            for (i in 1 until np) a.path.lineTo(xs[i], ys[i])
            if (style < 10) a.path.close()
            a.x0 = minOf(a.x0, x0); a.y0 = minOf(a.y0, y0); a.x1 = maxOf(a.x1, x1); a.y1 = maxOf(a.y1, y1)
        }
        buckets = acc.entries.map { (k, a) -> Bucket((k ushr 48).toInt(), a.path, a.x0, a.y0, a.x1, a.y1) }
            .groupBy { it.style }
    }

    companion object {
        const val CELL = 1000f
        // style ids as written by convert_gpkg.py
        const val LAND = 1; const val URBAN = 2; const val GREEN = 3; const val FARM = 4
        const val WATER = 5; const val BUILDING = 6
        const val TRUNK = 10; const val PRIMARY = 11; const val SECONDARY = 12; const val TERTIARY = 13
        const val MINOR = 14; const val SERVICE = 15; const val FOOT = 16
    }
}
