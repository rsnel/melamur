package it.snel.melamur.data

import android.content.Context
import android.graphics.Color
import android.util.Log
import it.snel.melamur.BuildConfig
import it.snel.melamur.map.Geo
import it.snel.melamur.map.Polyline
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Data source for stops, routes (with their official colours) and arrivals.
 *
 * Talks to the same backend as the official Tallinja app: the Meep MaaS layer
 * in front of an OpenTripPlanner (OTP) routing server (api.meep.me, zone
 * "Malta"). See docs/tallinja-api.md for the reverse-engineered structure.
 *
 * Auth is an anonymous SDK JWT: the app presents its OWN application key
 * (BuildConfig.TALLINJA_API_KEY, set via -PtallinjaApiKey=..., never committed)
 * to /access/sdk/anonymous and uses the returned Bearer token. We do NOT ship
 * the official app's key; without a key the app stays fully offline and uses
 * bundled assets: stops.json (OSM stops, left-kerb corrected) and optional
 * routes.json (build from the open GTFS feed, see tools/build_routes_from_gtfs.py).
 */
class TallinjaApi(private val ctx: Context) {
    private val base = BuildConfig.TALLINJA_BASE_URL.trimEnd('/')
    private val zone = BuildConfig.TALLINJA_ZONE
    private val key = BuildConfig.TALLINJA_API_KEY
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    val online get() = base.isNotEmpty() && key.isNotEmpty()

    @Volatile private var bearer: String? = null

    /** POST /access/sdk/anonymous with X-API-KEY -> short-lived JWT. */
    private fun token(): String? {
        bearer?.let { return it }
        return try {
            val req = Request.Builder().url("$base/access/sdk/anonymous")
                .header("X-API-KEY", key)
                .post("{}".toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                val b = r.body?.string() ?: return null
                val t = JSONObject(b).optString("accessToken").ifEmpty { JSONObject(b).optString("token") }
                t.ifEmpty { null }?.also { bearer = it }
            }
        } catch (e: Exception) { Log.w(TAG, "anonymous token failed", e); null }
    }

    private fun get(path: String): String? = try {
        val t = token() ?: return null
        val req = Request.Builder().url("$base$path").header("Authorization", "Bearer $t").build()
        http.newCall(req).execute().use {
            if (it.code == 401) { bearer = null }
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (e: Exception) { Log.w(TAG, "GET $path failed", e); null }

    private fun otp(suffix: String) = "/api/v1/routers/$zone/index$suffix"

    private fun asset(name: String): String? = try {
        ctx.assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) { null }

    // ---- stops: bundled OSM stops are our corrected (left-kerb) positions, so
    // they stay the base layer even online; live detail/arrivals are per-stop.
    fun stops(): List<BusStop> {
        val txt = asset("stops.json") ?: return emptyList()
        val a = JSONArray(txt)
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            BusStop(o.getString("id"), o.optString("name"), o.getDouble("lat"), o.getDouble("lon"),
                if (o.has("heading")) o.getDouble("heading").toFloat() else null,
                if (o.has("code")) o.optString("code") else null)
        }
    }

    fun routes(): List<BusRoute> {
        if (online) routesOnline()?.let { return it }
        return asset("routes.json")?.let { parseRoutes(it) } ?: emptyList()
    }

    /** OTP: list routes, then one /patterns call per route for shape + stops. */
    private fun routesOnline(): List<BusRoute>? {
        val list = get(otp("/routes")) ?: return null
        val arr = JSONArray(list)
        val out = ArrayList<BusRoute>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val id = r.getString("id")
            val color = parseColor(r.optString("color"), i)
            val pats = get(otp("/routes/$id/patterns")) ?: continue
            val pa = JSONArray(pats)
            val dirs = ArrayList<RouteDirection>()
            for (j in 0 until pa.length()) {
                val pid = pa.getJSONObject(j).getString("id")
                val detail = get(otp("/patterns/$pid")) ?: continue
                dirs += patternToDirection(JSONObject(detail))
            }
            if (dirs.isNotEmpty())
                out += BusRoute(id, r.optString("shortName", id), color, dirs)
        }
        return out
    }

    private fun patternToDirection(p: JSONObject): RouteDirection {
        val geom = p.optJSONObject("geometry")?.optString("points")
        val pts = if (!geom.isNullOrEmpty()) decodePolyline(geom) else run {
            val sa = p.optJSONArray("stops") ?: JSONArray()
            FloatArray(sa.length() * 2).also {
                for (k in 0 until sa.length()) {
                    val s = sa.getJSONObject(k)
                    it[2 * k] = Geo.x(s.getDouble("lon")); it[2 * k + 1] = Geo.y(s.getDouble("lat"))
                }
            }
        }
        val xs = FloatArray(pts.size / 2); val ys = FloatArray(pts.size / 2)
        for (k in xs.indices) { xs[k] = pts[2 * k]; ys[k] = pts[2 * k + 1] }
        val sa = p.optJSONArray("stops") ?: JSONArray()
        val stopIds = List(sa.length()) { sa.getJSONObject(it).getString("id") }
        return RouteDirection(p.getString("id"), p.optString("desc"), Polyline(xs, ys), stopIds)
    }

    fun arrivals(stopId: String): List<Arrival> {
        if (!online) return emptyList()
        val txt = get(otp("/stops/$stopId/stoptimes")) ?: return emptyList()
        val groups = JSONArray(txt); val out = ArrayList<Arrival>()
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            val rid = g.optJSONObject("pattern")?.optString("routeId") ?: g.optString("routeId")
            val times = g.optJSONArray("times") ?: continue
            for (k in 0 until times.length()) {
                val t = times.getJSONObject(k)
                val base = t.optLong("serviceDay")
                val sec = if (t.has("realtimeArrival")) t.getLong("realtimeArrival") else t.optLong("scheduledArrival")
                out += Arrival(rid, stopId, base + sec, t.optBoolean("realtime"))
            }
        }
        return out
    }

    companion object {
        private const val TAG = "TallinjaApi"

        /** Google-encoded polyline -> [x0,y0,x1,y1,...] in projected metres. */
        fun decodePolyline(enc: String): FloatArray {
            val out = ArrayList<Float>(); var i = 0; var lat = 0; var lng = 0
            while (i < enc.length) {
                var shift = 0; var result = 0; var b: Int
                do { b = enc[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
                lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
                shift = 0; result = 0
                do { b = enc[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
                lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
                out.add(Geo.x(lng / 1e5)); out.add(Geo.y(lat / 1e5))
            }
            return out.toFloatArray()
        }

        /** [{"id","short_name","color":"#RRGGBB","directions":[{"id","headsign","stops":[..],"shape":[[lat,lon],..]}]}] */
        fun parseRoutes(txt: String): List<BusRoute> {
            val a = JSONArray(txt)
            return List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val dirs = o.getJSONArray("directions")
                BusRoute(o.getString("id"), o.optString("short_name", o.getString("id")),
                    parseColor(o.optString("color"), i),
                    List(dirs.length()) { j -> parseDirection(dirs.getJSONObject(j)) })
            }
        }

        private fun parseDirection(d: JSONObject): RouteDirection {
            val sh = d.getJSONArray("shape")
            val xs = FloatArray(sh.length()); val ys = FloatArray(sh.length())
            for (k in 0 until sh.length()) {
                val p = sh.getJSONArray(k)
                ys[k] = Geo.y(p.getDouble(0)); xs[k] = Geo.x(p.getDouble(1))
            }
            val st = d.getJSONArray("stops")
            return RouteDirection(d.getString("id"), d.optString("headsign"), Polyline(xs, ys),
                List(st.length()) { st.getString(it) })
        }

        // Only used if the data has no colour.
        private val FALLBACK = intArrayOf(0xFF1F77B4.toInt(), 0xFFFF7F0E.toInt(), 0xFF2CA02C.toInt(),
            0xFF9467BD.toInt(), 0xFF8C564B.toInt(), 0xFFE377C2.toInt(), 0xFF17BECF.toInt())

        private fun parseColor(s: String?, i: Int): Int = try {
            if (s.isNullOrEmpty()) FALLBACK[i % FALLBACK.size]
            else Color.parseColor(if (s.startsWith("#")) s else "#$s")
        } catch (e: Exception) { FALLBACK[i % FALLBACK.size] }
    }
}
