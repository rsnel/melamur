package it.snel.melamur.map

import android.content.Context
import it.snel.melamur.data.BusStop
import it.snel.melamur.data.MptDb
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Everything about the (static) dataset the map can work out once instead of
 * on the fly, kept on disk next to the database:
 *  - per stop, the directions buses leave it in (from every route direction that
 *    calls there, projected on its shape; nearly equal directions merged);
 *  - the stop groups ("circles with numbers") for every zoom level where stops
 *    are grouped.
 * Recomputed when the database, the dataset version in use, the screen density
 * or [FORMAT] changes.
 */
class Precalc(
    val bearings: Map<String, FloatArray>,
    val clusters: Map<Int, List<StopCluster>>,
) {
    companion object {
        private const val FORMAT = 2
        private const val FILE = "precalc.bin"

        /** Identity of what the results depend on. */
        fun key(ctx: Context, version: Int, density: Float): String {
            val db = File(ctx.filesDir, "mpt.sqlite")
            return "$FORMAT/${db.length()}/${db.lastModified()}/$version/$density"
        }

        fun load(ctx: Context, key: String, stops: Map<String, BusStop>): Precalc? = try {
            DataInputStream(File(ctx.filesDir, FILE).inputStream().buffered()).use { inp ->
                if (inp.readUTF() != key) return null
                val nb = inp.readInt()
                val bearings = HashMap<String, FloatArray>(nb * 2)
                repeat(nb) {
                    val id = inp.readUTF(); val n = inp.readByte().toInt()
                    bearings[id] = FloatArray(n) { inp.readFloat() }
                }
                val nl = inp.readInt()
                val clusters = HashMap<Int, List<StopCluster>>()
                repeat(nl) {
                    val level = inp.readInt(); val nc = inp.readInt()
                    clusters[level] = List(nc) {
                        val x = inp.readFloat(); val y = inp.readFloat()
                        val x0 = inp.readFloat(); val y0 = inp.readFloat(); val x1 = inp.readFloat(); val y1 = inp.readFloat()
                        val n = inp.readInt()
                        val members = List(n) { inp.readUTF() }.mapNotNull { stops[it] }
                        StopCluster(x, y, members, x0, y0, x1, y1)
                    }
                }
                Precalc(bearings, clusters)
            }
        } catch (e: Exception) { null }

        fun save(ctx: Context, key: String, p: Precalc) {
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeUTF(key)
                out.writeInt(p.bearings.size)
                for ((id, b) in p.bearings) { out.writeUTF(id); out.writeByte(b.size); b.forEach { out.writeFloat(it) } }
                out.writeInt(p.clusters.size)
                for ((level, cs) in p.clusters) {
                    out.writeInt(level); out.writeInt(cs.size)
                    for (c in cs) {
                        out.writeFloat(c.x); out.writeFloat(c.y)
                        out.writeFloat(c.x0); out.writeFloat(c.y0); out.writeFloat(c.x1); out.writeFloat(c.y1)
                        out.writeInt(c.stops.size); c.stops.forEach { out.writeUTF(it.id) }
                    }
                }
            }
            tmp.renameTo(File(ctx.filesDir, FILE))
        }

        /** Work it all out; [progress] gets a short status line now and then. */
        fun compute(db: MptDb, v: Int, stops: List<BusStop>, density: Float, progress: (String) -> Unit): Precalc {
            val byId = stops.associateBy { it.id }
            // 1) departure directions per stop
            val raw = HashMap<String, MutableList<Float>>()
            val lines = db.allLines(v)
            for ((i, l) in lines.withIndex()) {
                if (i % 10 == 0) progress("Stop directions: line ${i + 1} of ${lines.size}")
                for (d in db.directionsForRoute(l.code, v)) {
                    var along = -1f
                    for (id in d.stopNumbers) {
                        val s = byId[id] ?: continue
                        val p = d.shape.project(s.x, s.y, maxOf(-1f, along - 30f))?.takeIf { it.dist < 80f }
                            ?: d.shape.project(s.x, s.y)?.takeIf { it.dist < 80f } ?: continue
                        along = p.along
                        raw.getOrPut(id) { ArrayList() }.add(p.bearing)
                    }
                }
            }
            val bearings = raw.mapValues { (_, bs) -> mergeBearings(bs) }
            // 2) stop groups for every zoom level that shows them
            progress("Grouping stops for the zoomed-out map")
            val clusters = HashMap<Int, List<StopCluster>>()
            for (level in StopCluster.levels(density)) clusters[level] = StopCluster.compute(stops, level)
            return Precalc(bearings, clusters)
        }

        /** Directions within [TOL] degrees of each other become one (their circular mean). */
        private const val TOL = 35f
        fun mergeBearings(bs: List<Float>): FloatArray {
            val groups = ArrayList<MutableList<Float>>()
            for (b in bs.sorted()) {
                val g = groups.firstOrNull { g -> angleDiff(mean(g), b) < TOL }
                if (g != null) g.add(b) else groups.add(mutableListOf(b))
            }
            return groups.map { mean(it) }.toFloatArray()
        }

        fun mean(bs: List<Float>): Float {
            var sx = 0.0; var sy = 0.0
            for (b in bs) { val r = Math.toRadians(b.toDouble()); sx += sin(r); sy += cos(r) }
            return ((Math.toDegrees(atan2(sx, sy)) + 360) % 360).toFloat()
        }

        fun angleDiff(a: Float, b: Float): Float {
            val d = abs(((a - b) % 360f + 360f) % 360f)
            return if (d > 180f) 360f - d else d
        }
    }
}

/** A group of nearby stops drawn as one circle with their count, at their average position. */
class StopCluster(val x: Float, val y: Float, val stops: List<BusStop>,
                  val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    companion object {
        /** Target cell size on screen. */
        const val CELL_DP = 48f

        /** Zoom level (cell = 2^level metres) used at map [scale] (px per metre). */
        fun levelFor(scale: Float, density: Float): Int =
            ceil(ln((CELL_DP * density / scale).toDouble()) / ln(2.0)).toInt()

        /** Every level used between whole-island zoom and the zoom where stops appear. */
        fun levels(density: Float): IntRange =
            levelFor(MapView.MIN_STOP_SCALE, density)..levelFor(MapView.MIN_SCALE_ALL, density)

        /**
         * Grid buckets of 2^[level] metres, then buckets whose centres ended up closer
         * than one cell merged (biggest first), so circles do not overlap. In world
         * space, so they stay put while panning.
         */
        fun compute(stops: List<BusStop>, level: Int): List<StopCluster> {
            val cell = Math.pow(2.0, level.toDouble()).toFloat()
            val buckets = HashMap<Long, MutableList<BusStop>>()
            for (st in stops) {
                val k = (floor((st.x / cell).toDouble()).toLong() shl 32) xor (floor((st.y / cell).toDouble()).toLong() and 0xffffffffL)
                buckets.getOrPut(k) { ArrayList() }.add(st)
            }
            val groups = buckets.values.sortedByDescending { it.size }
            fun cx(g: List<BusStop>) = g.sumOf { it.x.toDouble() }.toFloat() / g.size
            fun cy(g: List<BusStop>) = g.sumOf { it.y.toDouble() }.toFloat() / g.size
            val out = ArrayList<MutableList<BusStop>>()
            val centres = ArrayList<FloatArray>()
            for (g in groups) {
                val gx = cx(g); val gy = cy(g)
                val j = centres.indexOfFirst { hypot(it[0] - gx, it[1] - gy) < cell }
                if (j >= 0) { out[j].addAll(g); centres[j] = floatArrayOf(cx(out[j]), cy(out[j])) }
                else { out.add(g.toMutableList()); centres.add(floatArrayOf(gx, gy)) }
            }
            return out.mapIndexed { i, g ->
                StopCluster(centres[i][0], centres[i][1], g,
                    g.minOf { it.x }, g.minOf { it.y }, g.maxOf { it.x }, g.maxOf { it.y })
            }
        }
    }
}
