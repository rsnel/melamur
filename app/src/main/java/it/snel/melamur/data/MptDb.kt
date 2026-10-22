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
        "SELECT stop_number,name,locality,lat,lon FROM stop " +
            "WHERE from_version<=? AND (to_version IS NULL OR to_version>?) AND lat IS NOT NULL AND lon IS NOT NULL",
        arrayOf("$v", "$v")
    ) { Stop(it.getInt(0), it.getString(1) ?: "", it.getStringOrNull(2), it.getDouble(3), it.getDouble(4)) }

    /** Routes that serve [stopNumber], with MPT's drawing colour. */
    fun linesForStop(stopNumber: Int, v: Int): List<Line> = query(
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

    /** All stops a trip serves, in driving order with wall-clock minutes (§6.2). */
    fun tripStops(tripId: Long): List<TripStop> = query(
        "SELECT ts.seq, ts.stop_number, ts.name, (tr.first_minute + po.delta) AS minutes " +
            "FROM trip tr JOIN pattern_offset po ON po.pattern_id=tr.pattern_id " +
            "JOIN timetable_stop ts ON ts.timetable_id=tr.timetable_id AND ts.seq=po.seq " +
            "WHERE tr.id=? ORDER BY ts.seq",
        arrayOf("$tripId")
    ) { TripStop(it.getInt(0), if (it.isNull(1)) null else it.getInt(1), it.getStringOrNull(2) ?: "", it.getInt(3)) }

    /** Directions of a route with geometry and ordered stop numbers, for drawing. */
    fun directionsForRoute(code: String, v: Int): List<DirectionShape> {
        val color = routeColor(code, v)
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
            DirectionShape(code, dir.toInt(), name, color, Polyline(xs, ys), stopNums)
        }
    }

    fun routeColor(code: String, v: Int): Int = query(
        "SELECT kml_color FROM route WHERE code=? AND from_version<=? AND (to_version IS NULL OR to_version>?)",
        arrayOf(code, "$v", "$v")
    ) { it.getStringOrNull(0) }.firstOrNull().let { parseColor(it, code) }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    companion object {
        fun open(file: File): MptDb =
            MptDb(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY))

        private val FALLBACK = intArrayOf(0xFF1F77B4.toInt(), 0xFFFF7F0E.toInt(), 0xFF2CA02C.toInt(),
            0xFF9467BD.toInt(), 0xFF8C564B.toInt(), 0xFFE377C2.toInt(), 0xFF17BECF.toInt())

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
