package it.snel.melamur.map

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.hypot

/**
 * Pedestrian routing graph loaded from assets/walk.bin (walkable OSM ways,
 * un-simplified, int16 metres; written by tools/convert_gpkg.py). Because the
 * ways are un-simplified, a shared OSM junction node has identical coordinates
 * in every way, so merging equal coordinates at load yields a connected graph.
 *
 * route() runs A* (Euclidean heuristic) and returns the path as projected
 * (x, y) points for drawing, plus the network distance in metres. If walk.bin
 * is absent, load() returns null and callers fall back to a straight line.
 */
class WalkGraph private constructor(
    private val xs: FloatArray,
    private val ys: FloatArray,
    private val head: IntArray,   // CSR adjacency: edges of node n are head[n] until head[n+1]
    private val nbr: IntArray,
    private val w: FloatArray,
) {
    val nodeCount get() = xs.size

    // coarse grid for snapping query points to the nearest graph node
    private val cell = 60f
    private val grid = HashMap<Long, MutableList<Int>>()
    init {
        for (i in xs.indices) grid.getOrPut(cellKey(xs[i], ys[i])) { ArrayList() }.add(i)
    }
    private fun cellKey(x: Float, y: Float): Long =
        (Math.floorDiv(x.toInt(), cell.toInt()).toLong() + 100000L) * 300000L +
            (Math.floorDiv(y.toInt(), cell.toInt()).toLong() + 100000L)

    private fun nearest(px: Float, py: Float, radius: Float): Int {
        val cx = Math.floorDiv(px.toInt(), cell.toInt())
        val cy = Math.floorDiv(py.toInt(), cell.toInt())
        var best = -1; var bd = radius
        for (dx in -1..1) for (dy in -1..1) {
            grid[(cx + dx + 100000L) * 300000L + (cy + dy + 100000L)]?.forEach { i ->
                val d = hypot(xs[i] - px, ys[i] - py)
                if (d < bd) { bd = d; best = i }
            }
        }
        return best
    }

    class Path(val pts: FloatArray, val meters: Float)

    private class QN(val f: Float, val node: Int)

    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, snap: Float = 150f): Path? {
        val fx = Geo.x(fromLon); val fy = Geo.y(fromLat)
        val tx = Geo.x(toLon); val ty = Geo.y(toLat)
        val s = nearest(fx, fy, snap); val t = nearest(tx, ty, snap)
        if (s < 0 || t < 0) return null
        if (s == t) return straight(fx, fy, tx, ty)

        // sparse A* (walk routes touch few nodes, so avoid full-size arrays)
        val dist = HashMap<Int, Float>(); val prev = HashMap<Int, Int>()
        val pq = PriorityQueue<QN>(compareBy { it.f })
        dist[s] = 0f
        pq.add(QN(hypot(xs[s] - tx, ys[s] - ty), s))
        var found = false
        while (pq.isNotEmpty()) {
            val cur = pq.poll(); val u = cur.node
            val du = dist[u] ?: continue
            if (u == t) { found = true; break }
            if (cur.f > du + hypot(xs[u] - tx, ys[u] - ty) + 1f) continue  // stale entry
            var e = head[u]
            while (e < head[u + 1]) {
                val v = nbr[e]; val nd = du + w[e]
                if (nd < (dist[v] ?: Float.MAX_VALUE)) {
                    dist[v] = nd; prev[v] = u
                    pq.add(QN(nd + hypot(xs[v] - tx, ys[v] - ty), v))
                }
                e++
            }
        }
        if (!found) return null

        val chain = ArrayList<Int>()
        var n = t
        while (true) { chain.add(n); if (n == s) break; n = prev[n] ?: return null }
        chain.reverse()
        // points: actual origin, the node chain, actual destination
        val pts = FloatArray((chain.size + 2) * 2)
        var k = 0
        pts[k++] = fx; pts[k++] = fy
        for (idx in chain) { pts[k++] = xs[idx]; pts[k++] = ys[idx] }
        pts[k++] = tx; pts[k++] = ty
        val meters = (dist[t] ?: 0f) +
            hypot(xs[s] - fx, ys[s] - fy) + hypot(xs[t] - tx, ys[t] - ty)
        return Path(pts, meters)
    }

    private fun straight(fx: Float, fy: Float, tx: Float, ty: Float) =
        Path(floatArrayOf(fx, fy, tx, ty), hypot(tx - fx, ty - fy))

    companion object {
        /** Load and node walk.bin; null if the asset is absent or unreadable. */
        fun load(ctx: Context): WalkGraph? {
            val bytes = try { ctx.assets.open("walk.bin").use { it.readBytes() } } catch (e: Exception) { return null }
            return try { parse(bytes) } catch (e: Exception) { null }
        }

        private fun parse(bytes: ByteArray): WalkGraph? {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.int != 0x4b574c4d) return null   // "MLWK"
            bb.double; bb.double
            val nWays = bb.int

            val nodeOf = HashMap<Long, Int>()
            val xsL = ArrayList<Float>(); val ysL = ArrayList<Float>()
            val ea = ArrayList<Int>(); val eb = ArrayList<Int>(); val ew = ArrayList<Float>()
            fun node(x: Int, y: Int): Int {
                val key = ((x + 40000).toLong() shl 20) or (y + 40000).toLong()
                nodeOf[key]?.let { return it }
                val id = xsL.size; nodeOf[key] = id; xsL.add(x.toFloat()); ysL.add(y.toFloat()); return id
            }
            repeat(nWays) {
                val np = bb.short.toInt() and 0xffff
                var prev = -1
                for (i in 0 until np) {
                    val cur = node(bb.short.toInt(), bb.short.toInt())
                    if (prev >= 0 && prev != cur) {
                        ea.add(prev); eb.add(cur)
                        ew.add(hypot(xsL[cur] - xsL[prev], ysL[cur] - ysL[prev]))
                    }
                    prev = cur
                }
            }

            val n = xsL.size
            val deg = IntArray(n)
            for (i in ea.indices) { deg[ea[i]]++; deg[eb[i]]++ }
            val head = IntArray(n + 1)
            for (i in 0 until n) head[i + 1] = head[i] + deg[i]
            val m = ea.size * 2
            val nbr = IntArray(m); val w = FloatArray(m)
            val pos = head.copyOf()
            for (i in ea.indices) {
                val u = ea[i]; val v = eb[i]; val ww = ew[i]
                nbr[pos[u]] = v; w[pos[u]] = ww; pos[u]++
                nbr[pos[v]] = u; w[pos[v]] = ww; pos[v]++
            }
            return WalkGraph(xsL.toFloatArray(), ysL.toFloatArray(), head, nbr, w)
        }
    }
}
