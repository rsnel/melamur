package it.snel.melamur.map

import android.graphics.Path
import it.snel.melamur.data.BusStop
import it.snel.melamur.data.MptDb
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A stretch of a line that does not share its road with the other direction:
 * points every [RouteRenderer.STEP] metres as x,y,bearing triples, plus its extent.
 * The map spreads direction arrows over these at a constant density on screen.
 */
class ArrowRun(val pts: FloatArray, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val count get() = pts.size / 3
}

/** One drawn piece of a line: a polyline (x,y pairs) with its arrow stretches. */
class GeomPiece(val pts: FloatArray, val color: Int, val runs: List<ArrowRun>, val branch: Boolean) {
    val path: Path by lazy {
        Path().apply { moveTo(pts[0], pts[1]); var i = 2; while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 } }
    }
}

/** A stop the line calls at: where it is, which way the bus leaves it (NaN if unknown), in which direction. */
class LineStop(val id: String, val x: Float, val y: Float, val name: String, val bearing: Float, val color: Int,
               val label: String = name, val dir: Int = 0)

/**
 * The drawing of a whole line: every variant's own (Tallinja) polyline merged so
 * each stretch of road is drawn once. [stops] are the stops of all variants;
 * [partner] pairs a stop with the stop of the same name served in the other
 * direction nearby (index into [stops], -1 if none), so the map can draw the
 * pair as one when they would overlap. [bounds] = x0, y0, x1, y1 (or null).
 */
class RouteGfx(val id: String, val lines: List<GeomPiece>, val stops: List<LineStop>, val partner: IntArray,
               val bounds: FloatArray?)

/**
 * Builds a line's drawing from the polylines of ALL its variants (route_variant),
 * never from the older direction / route_shape geometry:
 *  - variants are taken main first, then longest first; a stretch another variant
 *    already drew (same road, same way; or a shared two-way stretch) is not drawn
 *    again, so branches only add the roads they alone use;
 *  - where a stretch runs close to one going the other way (same road both ways,
 *    or two carriageways), it is drawn once at their average position without
 *    arrows; elsewhere it is offset to the LEFT of its travel direction (Malta
 *    drives on the left) and carries direction arrows.
 */
object RouteRenderer {
    const val STEP = 10f                  // sample spacing along a shape, metres
    private const val OFFSET = 6f         // one-way parts: offset to the left
    private const val MERGE = 35f         // opposite parts closer than this are drawn as one
    private const val DUP = 12f           // a sample this close to one already drawn is a duplicate
    private const val CELL = 40f
    private const val MIN_SHARED = 5      // samples: shorter shared bits stay separate ...
    private const val MIN_APART = 3       // ... and shorter gaps between shared bits are bridged
    private const val MIN_ARROW_RUN = 4   // samples: one-way stretches shorter than this get no arrows
    private const val MIN_OWN = 3         // samples: a variant's own bits shorter than this are not drawn
    private const val PAIR = 250f         // same-name stops of both directions closer than this can pair

    /** One variant: its geometry and its stops (stop id, departure bearing or NaN) in driving order. */
    class Input(val dir: Int, val main: Boolean, val shape: Polyline, val stops: List<Pair<String, Float>>)

    /** The drawing of line [code] from the database (version [v]). */
    fun fromDb(db: MptDb, code: String, v: Int, stops: Map<String, BusStop>): RouteGfx {
        val vs = db.variants(code, v)
        val vstops = db.variantStops(vs.map { it.id })
        val ins = vs.mapNotNull { x ->
            val shape = db.variantShape(x) ?: return@mapNotNull null
            Input(x.directionIndex ?: 0, x.main, shape, vstops[x.id].orEmpty())
        }
        return build(code, ins, db.routeColor(code, v), stops)
    }

    fun build(code: String, ins: List<Input>, color: Int, stops: Map<String, BusStop>): RouteGfx {
        val nv = ins.size
        val samples = ins.map { d ->
            val n = (d.shape.length / STEP).toInt() + 1
            Array(n) { k -> d.shape.at(k * STEP) }
        }
        fun key(x: Float, y: Float) = (floor(x / CELL).toLong() shl 32) xor (floor(y / CELL).toLong() and 0xffffffffL)
        fun near(grid: HashMap<Long, MutableList<Int>>, x: Float, y: Float, f: (Int) -> Unit) {
            val gx = floor(x / CELL).toLong(); val gy = floor(y / CELL).toLong()
            for (ix in -1..1) for (iy in -1..1) grid[((gx + ix) shl 32) xor ((gy + iy) and 0xffffffffL)]?.forEach(f)
        }
        val all = HashMap<Long, MutableList<Int>>()   // packed (variant shl 20) or sample
        samples.forEachIndexed { vi, ss -> ss.forEachIndexed { k, p -> all.getOrPut(key(p[0], p[1])) { ArrayList() }.add((vi shl 20) or k) } }

        // 1) per sample: does a part going the other way run alongside? then draw at the middle
        val shared = Array(nv) { BooleanArray(samples[it].size) }
        val px = Array(nv) { FloatArray(samples[it].size) }
        val py = Array(nv) { FloatArray(samples[it].size) }
        for (vi in 0 until nv) {
            val ss = samples[vi]; val n = ss.size
            val sh = shared[vi]
            val mx = FloatArray(n); val my = FloatArray(n); val found = BooleanArray(n)
            for (k in 0 until n) {
                val p = ss[k]
                var best = MERGE; var bx = 0f; var by = 0f
                near(all, p[0], p[1]) { e ->
                    val q = samples[e ushr 20][e and 0xfffff]
                    if (angleDiff(p[2], q[2]) >= 135f) {
                        val dist = hypot(q[0] - p[0], q[1] - p[1])
                        if (dist < best) { best = dist; bx = q[0]; by = q[1] }
                    }
                }
                if (best < MERGE) { sh[k] = true; found[k] = true; mx[k] = (p[0] + bx) / 2; my[k] = (p[1] + by) / 2 }
            }
            fillRuns(sh, false, MIN_APART, betweenOnly = true)
            fillRuns(sh, true, MIN_SHARED, betweenOnly = false)
            for (k in 0 until n) if (sh[k] && !found[k]) { mx[k] = ss[k][0]; my[k] = ss[k][1] }
            for (k in 0 until n) {
                if (sh[k]) {
                    var tx = 0f; var ty = 0f; var c = 0
                    for (j in maxOf(0, k - 2)..minOf(n - 1, k + 2)) if (sh[j]) { tx += mx[j]; ty += my[j]; c++ }
                    px[vi][k] = tx / c; py[vi][k] = ty / c
                } else {
                    val br = Math.toRadians(ss[k][2].toDouble())
                    // left normal of travel direction (dx,dy)=(sin,cos) -> (-cos, sin)
                    px[vi][k] = ss[k][0] - OFFSET * cos(br).toFloat(); py[vi][k] = ss[k][1] + OFFSET * sin(br).toFloat()
                }
            }
        }

        // 2) draw each stretch once: main variants first, then longest first
        val order = (0 until nv).sortedWith(compareBy({ !ins[it].main }, { -ins[it].shape.length }))
        val drawn = HashMap<Long, MutableList<Int>>()
        val pieces = ArrayList<GeomPiece>()
        for (vi in order) {
            val ss = samples[vi]; val n = ss.size
            val own = BooleanArray(n)
            for (k in 0 until n) {
                var dup = false
                near(drawn, px[vi][k], py[vi][k]) { e ->
                    if (dup) return@near
                    val vj = e ushr 20; val kj = e and 0xfffff
                    if (hypot(px[vj][kj] - px[vi][k], py[vj][kj] - py[vi][k]) < DUP &&
                        (angleDiff(ss[k][2], samples[vj][kj][2]) < 60f || (shared[vi][k] && shared[vj][kj]))) dup = true
                }
                own[k] = !dup
            }
            fillRuns(own, false, 2, betweenOnly = true)
            fillRuns(own, true, MIN_OWN, betweenOnly = false)
            var k = 0
            while (k < n) {
                if (!own[k]) { k++; continue }
                var e = k
                while (e < n && own[e]) e++
                // join onto what is already drawn at both ends
                val a = maxOf(0, k - 1); val b = minOf(n - 1, e)
                val xy = FloatArray((b - a + 1) * 2)
                for (i in a..b) { xy[2 * (i - a)] = px[vi][i]; xy[2 * (i - a) + 1] = py[vi][i] }
                // arrows on the one-way stretches of this piece
                val runs = ArrayList<ArrowRun>()
                var s = k
                while (s < e) {
                    if (shared[vi][s]) { s++; continue }
                    var t = s
                    while (t < e && !shared[vi][t]) t++
                    if (t - s >= MIN_ARROW_RUN) {
                        val pts = FloatArray((t - s) * 3)
                        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                        for (i in s until t) {
                            val x = px[vi][i]; val y = py[vi][i]; val j = 3 * (i - s)
                            pts[j] = x; pts[j + 1] = y; pts[j + 2] = ss[i][2]
                            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                        }
                        runs += ArrowRun(pts, x0, y0, x1, y1)
                    }
                    s = t
                }
                if (xy.size >= 4) pieces += GeomPiece(simplify(xy, 0.7f), color, runs, branch = !ins[vi].main)
                k = e
            }
            for (i in 0 until n) if (own[i]) drawn.getOrPut(key(px[vi][i], py[vi][i])) { ArrayList() }.add((vi shl 20) or i)
        }

        val (ls, partner) = lineStops(ins, color, stops)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (p in pieces) { var i = 0; while (i < p.pts.size) {
            val x = p.pts[i]; val y = p.pts[i + 1]
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; i += 2 } }
        for (s in ls) { if (s.x < x0) x0 = s.x; if (s.x > x1) x1 = s.x; if (s.y < y0) y0 = s.y; if (s.y > y1) y1 = s.y }
        return RouteGfx(code, pieces, ls, partner, if (x0 <= x1) floatArrayOf(x0, y0, x1, y1) else null)
    }

    /** Stops of all variants, once per stop and direction; departure bearing from the data or the shape. */
    private fun lineStops(ins: List<Input>, color: Int, stops: Map<String, BusStop>): Pair<List<LineStop>, IntArray> {
        val out = ArrayList<LineStop>()
        val seen = HashSet<String>()
        for (d in ins.sortedBy { !it.main }) {
            var along = -1f
            for ((id, given) in d.stops) {
                val s = stops[id] ?: continue
                var bearing = given
                if (bearing.isNaN()) {
                    // the shape's bearing where the bus passes the stop (onwards from the previous one)
                    val p = d.shape.project(s.x, s.y, maxOf(-1f, along - 30f))?.takeIf { it.dist < 80f }
                        ?: d.shape.project(s.x, s.y)?.takeIf { it.dist < 80f }
                    if (p != null) { along = p.along; bearing = p.bearing }
                }
                if (seen.add("$id/${d.dir}")) out += LineStop(id, s.x, s.y, s.name, bearing, color, s.mapName, d.dir)
            }
        }
        val partner = IntArray(out.size) { -1 }
        val byName = out.indices.groupBy { out[it].name.trim().lowercase() }
        for (g in byName.values) {
            if (g.size < 2) continue
            val cand = ArrayList<Triple<Float, Int, Int>>()
            for (a in g) for (b in g) if (a < b && out[a].dir != out[b].dir && out[a].id != out[b].id) {
                val dd = hypot(out[a].x - out[b].x, out[a].y - out[b].y)
                if (dd < PAIR) cand += Triple(dd, a, b)
            }
            for ((_, a, b) in cand.sortedBy { it.first })
                if (partner[a] < 0 && partner[b] < 0) { partner[a] = b; partner[b] = a }
        }
        return out to partner
    }

    // ---------------- storage (for the precalculation file)

    fun write(out: DataOutputStream, g: RouteGfx) {
        out.writeUTF(g.id)
        out.writeInt(g.lines.size)
        for (p in g.lines) {
            out.writeInt(p.color); out.writeBoolean(p.branch)
            writeFloats(out, p.pts)
            out.writeInt(p.runs.size)
            for (r in p.runs) { writeFloats(out, r.pts); out.writeFloat(r.x0); out.writeFloat(r.y0); out.writeFloat(r.x1); out.writeFloat(r.y1) }
        }
        out.writeInt(g.stops.size)
        for ((i, s) in g.stops.withIndex()) {
            out.writeUTF(s.id); out.writeFloat(s.bearing); out.writeInt(s.color); out.writeInt(s.dir); out.writeInt(g.partner[i])
        }
        out.writeBoolean(g.bounds != null); g.bounds?.let { writeFloats(out, it) }
    }

    fun read(inp: DataInputStream, stops: Map<String, BusStop>): RouteGfx {
        val id = inp.readUTF()
        val pieces = List(inp.readInt()) {
            val color = inp.readInt(); val branch = inp.readBoolean()
            val pts = readFloats(inp)
            val runs = List(inp.readInt()) { ArrowRun(readFloats(inp), inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat()) }
            GeomPiece(pts, color, runs, branch)
        }
        val ls = ArrayList<LineStop>(); val partner = ArrayList<Int>()
        repeat(inp.readInt()) {
            val sid = inp.readUTF(); val b = inp.readFloat(); val color = inp.readInt(); val dir = inp.readInt(); val pa = inp.readInt()
            val s = stops[sid]
            // keep indices aligned even if a stop vanished: put it where it was
            ls += if (s != null) LineStop(sid, s.x, s.y, s.name, b, color, s.mapName, dir) else LineStop(sid, Float.NaN, Float.NaN, "", b, color, "", dir)
            partner += pa
        }
        val bounds = if (inp.readBoolean()) readFloats(inp) else null
        return RouteGfx(id, pieces, ls, partner.toIntArray(), bounds)
    }

    private fun writeFloats(out: DataOutputStream, a: FloatArray) { out.writeInt(a.size); a.forEach { out.writeFloat(it) } }
    private fun readFloats(inp: DataInputStream) = FloatArray(inp.readInt()) { inp.readFloat() }

    // ---------------- helpers

    /** Douglas-Peucker on x,y pairs: drop points within [tol] metres of the simplified line. */
    private fun simplify(xy: FloatArray, tol: Float): FloatArray {
        val n = xy.size / 2
        if (n <= 2) return xy
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>(); stack.add(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            val ax = xy[2 * a]; val ay = xy[2 * a + 1]; val bx = xy[2 * b]; val by = xy[2 * b + 1]
            val dx = bx - ax; val dy = by - ay; val l = hypot(dx, dy)
            var far = -1; var fd = tol
            for (i in a + 1 until b) {
                val x = xy[2 * i]; val y = xy[2 * i + 1]
                val d = if (l < 1e-3f) hypot(x - ax, y - ay) else abs(dy * x - dx * y + bx * ay - by * ax) / l
                if (d > fd) { fd = d; far = i }
            }
            if (far >= 0) { keep[far] = true; stack.add(intArrayOf(a, far)); stack.add(intArrayOf(far, b)) }
        }
        val out = FloatArray(keep.count { it } * 2); var j = 0
        for (i in 0 until n) if (keep[i]) { out[j++] = xy[2 * i]; out[j++] = xy[2 * i + 1] }
        return out
    }

    /** Flip runs of [value] shorter than [min]; with [betweenOnly], only runs with the other value on both sides. */
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
