package it.snel.melamur.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import it.snel.melamur.map.Geo
import it.snel.melamur.map.Polyline
import java.io.File
import java.time.LocalDate

/**
 * Read-only access to a Malta Public Transport dataset (SQLite, schema 1; see
 * docs/SQL-FORMAT.txt). All versioned reads resolve ONE version id for the
 * service date and reuse it, as the format document requires.
 */
class MptDb private constructor(private val db: SQLiteDatabase) {

    fun close() = db.close()

    private fun <T> query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(map(c)) }
        return out
    }

    /** A line's official style from route_style (Tallinja); any field may be null. */
    class RouteStyle(val color: Int?, val textColor: Int?, val longName: String?)

    // route_style is not versioned and may be absent in older datasets of the same schema
    private val routeStyles: Map<String, RouteStyle> by lazy {
        val present = query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='route_style'", emptyArray()) { 1 }
        if (present.isEmpty()) emptyMap()
        else query("SELECT route_code, color, text_color, long_name FROM route_style", emptyArray()) {
            it.getString(0) to RouteStyle(hexColor(it.getStringOrNull(1)), hexColor(it.getStringOrNull(2)), it.getStringOrNull(3))
        }.toMap()
    }

    fun routeStyle(code: String): RouteStyle? = routeStyles[code]
    /** The real line colour (route_style), when the dataset has one for [code]. */
    private fun styleColor(code: String): Int? = routeStyles[code]?.color

    fun meta(key: String): String? =
        query("SELECT value FROM meta WHERE key=?", arrayOf(key)) { it.getString(0) }.firstOrNull()

    fun attribution(): String =
        meta("attribution") ?: "Timetables © Malta Public Transport. Map data © OpenStreetMap contributors."

    fun latestVersion(): Int = meta("latest_version")?.toIntOrNull()
        ?: query("SELECT MAX(version_id) FROM dataset_version", emptyArray()) { it.getInt(0) }.firstOrNull() ?: 1

    /** Version id applicable to [date]; falls back to the oldest version. */
    fun versionFor(date: LocalDate): Int {
        query("SELECT version_id FROM dataset_version WHERE valid_from<=? ORDER BY valid_from DESC, version_id DESC LIMIT 1",
            arrayOf(date.toString())) { it.getInt(0) }.firstOrNull()?.let { return it }
        return query("SELECT MIN(version_id) FROM dataset_version", emptyArray()) { it.getInt(0) }.firstOrNull() ?: 1
    }

    /** Earliest service date any version applies to, or null if none. */
    fun firstValidFrom(): String? =
        query("SELECT MIN(valid_from) FROM dataset_version", emptyArray()) { if (it.isNull(0)) null else it.getString(0) }.firstOrNull()

    fun allStops(v: Int): List<Stop> = query(
        "SELECT stop_number,name,locality,lat,lon," + (if (hasStopShortName) "short_name" else "NULL") + " FROM stop " +
            "WHERE from_version<=? AND (to_version IS NULL OR to_version>?) AND lat IS NOT NULL AND lon IS NOT NULL",
        arrayOf("$v", "$v")
    ) { Stop(it.getInt(0), it.getString(1) ?: "", it.getStringOrNull(2), it.getDouble(3), it.getDouble(4), it.getStringOrNull(5)) }

    /** Older datasets have no stop.short_name. */
    private val hasStopShortName: Boolean by lazy {
        query("PRAGMA table_info(stop)", emptyArray()) { it.getString(1) }.any { it == "short_name" }
    }

    /** Routes that serve [stopNumber], in their line colour (route_style, else route_shape). */
    fun linesForStop(stopNumber: Int, v: Int): List<Line> = linesForStopRaw(stopNumber, v).map { l ->
        styleColor(l.code)?.let { return@map l.copy(color = it) }
        val sc = shapeColors(l.code, v)
        sc.keys.minOrNull()?.let { l.copy(color = sc.getValue(it)) } ?: l
    }

    private fun linesForStopRaw(stopNumber: Int, v: Int): List<Line> = query(
        "SELECT DISTINCT vsr.route_code, r.name, r.kml_color FROM v_stop_routes vsr " +
            "JOIN route r ON r.code=vsr.route_code AND r.from_version<=? AND (r.to_version IS NULL OR r.to_version>?) " +
            "WHERE vsr.stop_number=? AND vsr.from_version<=? AND (vsr.to_version IS NULL OR vsr.to_version>?) " +
            "ORDER BY vsr.route_code",
        arrayOf("$v", "$v", "$stopNumber", "$v", "$v")
    ) { Line(it.getString(0), it.getStringOrNull(1) ?: it.getString(0), parseColor(it.getStringOrNull(2), it.getString(0))) }

    /** Scheduled departures from a stop for one service date (SQL-FORMAT §6.1). */
    fun departures(stopNumber: Int, date: LocalDate, v: Int): List<Departure> {
        val bit = Malta.weekdayBit(date)
        return query(
            "SELECT tt.route_code, tt.direction_index, d.name, tr.id, (tr.first_minute + po.delta) AS minutes " +
                "FROM timetable tt " +
                "JOIN timetable_stop ts ON ts.timetable_id=tt.id " +
                "JOIN trip tr ON tr.timetable_id=tt.id " +
                "JOIN pattern_offset po ON po.pattern_id=tr.pattern_id AND po.seq=ts.seq " +
                "JOIN direction d ON d.route_code=tt.route_code AND d.direction_index=tt.direction_index " +
                "  AND d.from_version<=? AND (d.to_version IS NULL OR d.to_version>?) " +
                "WHERE ts.stop_number=? AND tt.from_version<=? AND (tt.to_version IS NULL OR tt.to_version>?) " +
                "  AND (tt.weekdays & ?)<>0 AND ts.seq < tt.stop_count-1 " +
                "ORDER BY minutes",
            arrayOf("$v", "$v", "$stopNumber", "$v", "$v", "$bit")
        ) { Departure(it.getString(0), it.getStringOrNull(2) ?: "", it.getLong(3), it.getInt(1), it.getInt(4), date) }
    }

    /**
     * Upcoming departures from a stop from [now] onwards, merging today's trips
     * with the previous service day's after-midnight trips (SQL-FORMAT §6.1),
     * sorted by real departure time.
     */
    fun departuresFrom(stopNumber: Int, now: java.time.LocalDateTime, limit: Int = 15): List<Departure> {
        val today = now.toLocalDate()
        val nowMin = now.hour * 60 + now.minute
        val out = ArrayList<Departure>()
        out += departures(stopNumber, today, versionFor(today)).filter { it.minutes >= nowMin }
        val prev = today.minusDays(1)
        out += departures(stopNumber, prev, versionFor(prev))
            .filter { it.minutes >= 1440 && Malta.at(prev, it.minutes) >= now }
        return out.sortedBy { it.time }.take(limit)
    }

    /** All stops a trip serves, in driving order with wall-clock minutes (§6.2). */
    fun tripStops(tripId: Long): List<TripStop> = query(
        "SELECT ts.seq, ts.stop_number, ts.name, (tr.first_minute + po.delta) AS minutes " +
            "FROM trip tr JOIN pattern_offset po ON po.pattern_id=tr.pattern_id " +
            "JOIN timetable_stop ts ON ts.timetable_id=tr.timetable_id AND ts.seq=po.seq " +
            "WHERE tr.id=? ORDER BY ts.seq",
        arrayOf("$tripId")
    ) { TripStop(it.getInt(0), if (it.isNull(1)) null else it.getInt(1), it.getStringOrNull(2) ?: "", it.getInt(3)) }

    /**
     * Colour per direction_index from route_shape (MPT's drawn line colour;
     * the real per-direction line colour). Falls back to kml_color then palette.
     */
    fun shapeColors(code: String, v: Int): Map<Int, Int> {
        // a real line colour from route_style wins; callers then fall back to routeColor()
        if (styleColor(code) != null) return emptyMap()
        val out = HashMap<Int, Int>()
        query(
            "SELECT rs.direction_index, rs.color FROM route_shape rs " +
                "JOIN route r ON r.id=rs.route_id AND r.from_version<=? AND (r.to_version IS NULL OR r.to_version>?) " +
                "WHERE r.code=? AND rs.direction_index IS NOT NULL AND rs.color IS NOT NULL ORDER BY rs.shape_no",
            arrayOf("$v", "$v", code)
        ) { it.getInt(0) to it.getStringOrNull(1) }.forEach { (dir, col) ->
            if (!out.containsKey(dir) && col != null) out[dir] = parseColor(col, "$code/$dir")
        }
        return out
    }

    // ---- route variants (several versions of a line in one direction); absent in older datasets
    private val hasVariants: Boolean by lazy {
        query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('route_variant','trip_variant')",
            emptyArray()) { it.getInt(0) }.firstOrNull() == 2
    }

    /** All variants of a line valid in [v] (both directions); empty for datasets without variants. */
    fun variants(code: String, v: Int): List<Variant> = if (!hasVariants) emptyList() else query(
        "SELECT id, route_code, direction_index, variant, name, description, origin, destination, via, express, " +
            "headsign, polyline, main FROM route_variant " +
            "WHERE route_code=? AND from_version<=? AND (to_version IS NULL OR to_version>?) ORDER BY direction_index, variant",
        arrayOf(code, "$v", "$v")
    ) {
        Variant(it.getLong(0), it.getString(1), if (it.isNull(2)) null else it.getInt(2), it.getStringOrNull(3) ?: "",
            it.getStringOrNull(4), it.getStringOrNull(5), it.getStringOrNull(6), it.getStringOrNull(7),
            it.getStringOrNull(8), !it.isNull(9) && it.getInt(9) != 0, it.getStringOrNull(10),
            it.getStringOrNull(11), !it.isNull(12) && it.getInt(12) != 0)
    }

    /** Which variant each trip runs (trip.id -> route_variant.id); trips without a match are absent. */
    fun tripVariants(tripIds: Collection<Long>): Map<Long, Long> {
        if (!hasVariants || tripIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, Long>()
        for (chunk in tripIds.distinct().chunked(500)) {
            val qs = chunk.joinToString(",") { "?" }
            query("SELECT trip_id, variant_id FROM trip_variant WHERE trip_id IN ($qs)",
                chunk.map { it.toString() }.toTypedArray()) { it.getLong(0) to it.getLong(1) }
                .forEach { (t, vid) -> out[t] = vid }
        }
        return out
    }

    /** A line's variants and, per variant, a short label of how it differs from its siblings. */
    class LineVariants(val byId: Map<Long, Variant>, private val labels: Map<Long, String>) {
        fun label(variantId: Long?): String = variantId?.let { labels[it] }.orEmpty()
        /** The distinct labels of one direction, e.g. ["from Marsaskala", "from Bianco, via Universita"]. */
        fun labelsFor(dir: Int): List<String> =
            byId.values.filter { it.directionIndex == dir }.mapNotNull { labels[it.id]?.takeIf { l -> l.isNotEmpty() } }.distinct()
    }
    private val variantCache = HashMap<String, LineVariants>()
    fun lineVariants(code: String, v: Int): LineVariants = synchronized(variantCache) {
        variantCache.getOrPut("$code/$v") {
            val vs = variants(code, v)
            LineVariants(vs.associateBy { it.id }, variantLabels(vs, variantStopNames(vs.map { it.id })))
        }
    }

    /** Stop names of each variant in driving order (route_variant_stop), for telling variants apart. */
    private fun variantStopNames(ids: List<Long>): Map<Long, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        val present = query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='route_variant_stop'", emptyArray()) { 1 }
        if (present.isEmpty()) return emptyMap()
        val out = HashMap<Long, ArrayList<String>>()
        query("SELECT variant_id, name FROM route_variant_stop WHERE variant_id IN (${ids.joinToString(",") { "?" }}) " +
            "ORDER BY variant_id, seq", ids.map { it.toString() }.toTypedArray()) { it.getLong(0) to (it.getStringOrNull(1) ?: "") }
            .forEach { (id, n) -> if (n.isNotEmpty()) out.getOrPut(id) { ArrayList() }.add(n) }
        return out
    }

    /** A variant's own geometry, or null if it has none. */
    fun variantShape(x: Variant): Polyline? = x.polyline?.takeIf { it.isNotEmpty() }?.let { poly ->
        val pts = TallinjaApi.decodePolyline(poly)
        val xs = FloatArray(pts.size / 2); val ys = FloatArray(pts.size / 2)
        for (k in xs.indices) { xs[k] = pts[2 * k]; ys[k] = pts[2 * k + 1] }
        if (xs.size >= 2) Polyline(xs, ys) else null
    }

    /** All trips of a line in one direction on [date], by their first time: every variant, wherever it starts. */
    fun tripsOf(code: String, dir: Int, date: LocalDate, v: Int): List<Pair<Long, Int>> = query(
        "SELECT tr.id, tr.first_minute FROM timetable tt JOIN trip tr ON tr.timetable_id=tt.id " +
            "WHERE tt.route_code=? AND tt.direction_index=? AND tt.from_version<=? " +
            "AND (tt.to_version IS NULL OR tt.to_version>?) AND (tt.weekdays & ?)<>0 ORDER BY tr.first_minute",
        arrayOf(code, "$dir", "$v", "$v", "${Malta.weekdayBit(date)}")
    ) { it.getLong(0) to it.getInt(1) }

    /** All routes valid in version [v], with name and (direction-0) colour. */
    fun allLines(v: Int): List<Line> = query(
        "SELECT code, name FROM route WHERE from_version<=? AND (to_version IS NULL OR to_version>?) ORDER BY code",
        arrayOf("$v", "$v")
    ) { it.getString(0) to (it.getStringOrNull(1) ?: it.getString(0)) }
        .map { (code, name) -> Line(code, name, shapeColors(code, v)[0] ?: routeColor(code, v)) }

    /**
     * All directions of all routes valid in [v], with the per-direction drawing
     * colour and the first/last stop numbers — for the Explore line list. One
     * query; stop display names are resolved by the caller from its stop map.
     */
    fun allLineDirections(v: Int): List<LineDir> = query(
        "SELECT d.route_code, d.direction_index, " +
            "COALESCE((SELECT rs.color FROM route_shape rs WHERE rs.route_id=r.id " +
            "  AND rs.direction_index=d.direction_index AND rs.color IS NOT NULL ORDER BY rs.shape_no LIMIT 1), r.kml_color) AS color, " +
            "(SELECT stop_number FROM direction_stop WHERE direction_id=d.id ORDER BY pos ASC LIMIT 1) AS first_stop, " +
            "(SELECT stop_number FROM direction_stop WHERE direction_id=d.id ORDER BY pos DESC LIMIT 1) AS last_stop, " +
            "d.name " +
            "FROM direction d " +
            "JOIN route r ON r.code=d.route_code AND r.from_version<=? AND (r.to_version IS NULL OR r.to_version>?) " +
            "WHERE d.from_version<=? AND (d.to_version IS NULL OR d.to_version>?) " +
            "ORDER BY d.route_code, d.direction_index",
        arrayOf("$v", "$v", "$v", "$v")
    ) {
        LineDir(
            it.getString(0), it.getInt(1),
            styleColor(it.getString(0)) ?: parseColor(it.getStringOrNull(2), "${it.getString(0)}/${it.getInt(1)}"),
            if (it.isNull(3)) null else it.getInt(3),
            if (it.isNull(4)) null else it.getInt(4),
            it.getStringOrNull(5) ?: ""
        )
    }

    /** Directions of a route with geometry and ordered stop numbers, for drawing. */
    fun directionsForRoute(code: String, v: Int): List<DirectionShape> {
        val fallback = routeColor(code, v)
        val byDir = shapeColors(code, v)
        val dirs = query(
            "SELECT id, direction_index, name, polyline FROM direction " +
                "WHERE route_code=? AND from_version<=? AND (to_version IS NULL OR to_version>?) ORDER BY direction_index",
            arrayOf(code, "$v", "$v")
        ) { arrayOf(it.getLong(0).toString(), it.getInt(1).toString(), it.getStringOrNull(2) ?: "", it.getStringOrNull(3) ?: "") }
        return dirs.mapNotNull { (id, dir, name, poly) ->
            if (poly.isEmpty()) return@mapNotNull null
            val pts = TallinjaApi.decodePolyline(poly)
            val xs = FloatArray(pts.size / 2); val ys = FloatArray(pts.size / 2)
            for (k in xs.indices) { xs[k] = pts[2 * k]; ys[k] = pts[2 * k + 1] }
            val stopNums = query("SELECT stop_number FROM direction_stop WHERE direction_id=? ORDER BY pos",
                arrayOf(id)) { if (it.isNull(0)) null else it.getInt(0).toString() }.filterNotNull()
            DirectionShape(code, dir.toInt(), name, byDir[dir.toInt()] ?: fallback, Polyline(xs, ys), stopNums)
        }
    }

    fun routeColor(code: String, v: Int): Int = styleColor(code) ?: query(
        "SELECT kml_color FROM route WHERE code=? AND from_version<=? AND (to_version IS NULL OR to_version>?)",
        arrayOf(code, "$v", "$v")
    ) { it.getStringOrNull(0) }.firstOrNull().let { parseColor(it, code) }

    /** direction_index -> headsign (destination) for a route. */
    fun directionNames(code: String, v: Int): Map<Int, String> = query(
        "SELECT direction_index, COALESCE(destination, name) FROM direction " +
            "WHERE route_code=? AND from_version<=? AND (to_version IS NULL OR to_version>?)",
        arrayOf(code, "$v", "$v")
    ) { it.getInt(0) to (it.getStringOrNull(1) ?: "") }.toMap()

    /**
     * Load all RAPTOR routes (patterns) valid for version [v] and weekday [bit]:
     * trips grouped by the exact stop sequence they serve, with per-stop minutes.
     */
    fun loadPatterns(v: Int, bit: Int): List<RawRoute> {
        val tts = query(
            "SELECT id, route_code, direction_index FROM timetable " +
                "WHERE from_version<=? AND (to_version IS NULL OR to_version>?) AND (weekdays & ?)<>0",
            arrayOf("$v", "$v", "$bit")
        ) { Triple(it.getLong(0), it.getString(1), it.getInt(2)) }
        val patCache = HashMap<Long, List<IntArray>>()
        val out = ArrayList<RawRoute>()
        for ((ttId, code, dir) in tts) {
            val seqStop = HashMap<Int, Int>()
            query("SELECT seq, stop_number FROM timetable_stop WHERE timetable_id=?", arrayOf("$ttId")) {
                if (!it.isNull(1)) seqStop[it.getInt(0)] = it.getInt(1); 0
            }
            val trips = query("SELECT id, first_minute, pattern_id FROM trip WHERE timetable_id=?", arrayOf("$ttId")) {
                Triple(it.getLong(0), it.getInt(1), it.getLong(2))
            }
            val groups = LinkedHashMap<String, Pair<IntArray, ArrayList<RawTrip>>>()
            for ((tid, first, pid) in trips) {
                val pat = patCache.getOrPut(pid) {
                    query("SELECT seq, delta FROM pattern_offset WHERE pattern_id=? ORDER BY seq", arrayOf("$pid")) {
                        intArrayOf(it.getInt(0), it.getInt(1))
                    }
                }
                val stopsL = ArrayList<Int>(); val minsL = ArrayList<Int>()
                for (sd in pat) { val sn = seqStop[sd[0]] ?: continue; stopsL.add(sn); minsL.add(first + sd[1]) }
                if (stopsL.size < 2) continue
                val g = groups.getOrPut(stopsL.joinToString(",")) { stopsL.toIntArray() to ArrayList() }
                g.second.add(RawTrip(tid, minsL.toIntArray()))
            }
            for ((_, g) in groups) out.add(RawRoute(code, dir, g.first, g.second.sortedBy { it.minutes.firstOrNull() ?: 0 }))
        }
        return out
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        fun open(file: File): MptDb =
            MptDb(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY))

        private val FALLBACK = intArrayOf(0xFF1F77B4.toInt(), 0xFFFF7F0E.toInt(), 0xFF2CA02C.toInt(),
            0xFF9467BD.toInt(), 0xFF8C564B.toInt(), 0xFFE377C2.toInt(), 0xFF17BECF.toInt())

        /**
         * How each variant differs from the others in the same direction, briefly:
         * only the fields that are not the same for all of them ("from Marsaskala",
         * "via Universita", "to Mater Dei", "express"). Empty for a direction with
         * one variant, and for the plain variant when the others add something.
         * Variants that still look alike are told apart by their stops (a stop only
         * that variant serves: "via X"; one it leaves out: "skips X"), else by
         * their own description. [stops]: variant id -> its stop names in order.
         */
        fun variantLabels(vs: List<Variant>, stops: Map<Long, List<String>> = emptyMap()): Map<Long, String> {
            val out = HashMap<Long, String>()
            fun norm(s: String?) = s?.trim()?.lowercase().orEmpty()
            fun clean(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
            for ((_, group) in vs.groupBy { it.directionIndex }) {
                if (group.size < 2) continue
                fun differs(f: (Variant) -> String?) = group.map { norm(f(it)) }.toSet().size > 1
                val dFrom = differs { it.origin }; val dVia = differs { it.via }; val dTo = differs { it.destination }
                val dExpress = group.map { it.express }.toSet().size > 1
                for (x in group) {
                    val parts = ArrayList<String>()
                    if (dFrom) clean(x.origin)?.let { parts += "from $it" }
                    if (dVia) clean(x.via)?.let { parts += "via $it" }
                    if (dTo) clean(x.destination)?.let { parts += "to $it" }
                    if (dExpress && x.express) parts += "express"
                    out[x.id] = parts.joinToString(", ")
                }
                for ((_, same) in group.groupBy { out[it.id] }) if (same.size > 1) {
                    val resolved = same.associate { x ->
                        val mine = stops[x.id].orEmpty()
                        val others = same.filter { it !== x }.flatMap { stops[it.id].orEmpty() }
                        val otherKeys = others.map { norm(it) }.toSet(); val mineKeys = mine.map { norm(it) }.toSet()
                        val only = mine.firstOrNull { norm(it) !in otherKeys }
                        val skips = others.firstOrNull { norm(it) !in mineKeys }
                        x.id to (only?.let { "via $it" } ?: skips?.let { "skips $it" }
                            ?: clean(x.description) ?: clean(x.name) ?: "variant ${x.variant}")
                    }
                    for (x in same) out[x.id] = listOfNotNull(out[x.id]?.takeIf { it.isNotEmpty() }, resolved[x.id]).joinToString(", ")
                }
            }
            return out
        }

        /** "#RRGGBB" (route_style) -> colour, or null if absent/invalid. */
        fun hexColor(s: String?): Int? = s?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try { Color.parseColor(if (it.startsWith("#")) it else "#$it") } catch (e: Exception) { null }
        }

        /** kml_color is KML aabbggrr (8 hex) or "#rrggbb"; fall back by key hash. */
        fun parseColor(s: String?, key: String): Int {
            val t = s?.trim()?.removePrefix("#") ?: return FALLBACK[Math.floorMod(key.hashCode(), FALLBACK.size)]
            return try {
                if (t.length == 8) { // aabbggrr
                    val a = t.substring(0, 2).toInt(16); val b = t.substring(2, 4).toInt(16)
                    val g = t.substring(4, 6).toInt(16); val r = t.substring(6, 8).toInt(16)
                    Color.argb(if (a == 0) 255 else a, r, g, b)
                } else Color.parseColor(if (t.length == 6) "#$t" else "#$t")
            } catch (e: Exception) { FALLBACK[Math.floorMod(key.hashCode(), FALLBACK.size)] }
        }
    }
}
