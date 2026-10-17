package it.snel.melamur.data

import android.content.Context
import android.graphics.Color
import android.util.Log
import it.snel.melamur.BuildConfig
import it.snel.melamur.map.Geo
import it.snel.melamur.map.Polyline
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Data source for stops, routes (with their official colours) and arrivals.
 *
 * Intended to talk to the same backend the official Tallinja app uses, once
 * Malta Public Transport grants an API key (set tallinjaBaseUrl / tallinjaApiKey
 * in ~/.gradle/gradle.properties). The endpoint paths below are PLACEHOLDERS:
 * the APK we were given was an Uptodown installer wrapper, so the real paths
 * still have to be taken from the official API documentation.
 *
 * Until then everything falls back to bundled data: OSM bus stops (corrected
 * for left-hand traffic) from assets/stops.json and optional routes from
 * assets/routes.json (same JSON format as [parseRoutes]).
 */
class TallinjaApi(private val ctx: Context) {
    private val base = BuildConfig.TALLINJA_BASE_URL.trimEnd('/')
    private val key = BuildConfig.TALLINJA_API_KEY
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    val online get() = base.isNotEmpty() && key.isNotEmpty()

    private fun get(path: String): String? = try {
        val req = Request.Builder().url("$base$path").header("Authorization", "Bearer $key").build()
        http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) {
        Log.w(TAG, "GET $path failed", e); null
    }

    private fun asset(name: String): String? = try {
        ctx.assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) { null }

    /** Blocking; call from a background thread. */
    fun stops(): List<BusStop> {
        val txt = (if (online) get("/stops") else null) ?: asset("stops.json") ?: return emptyList()
        val a = JSONArray(txt)
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            BusStop(o.getString("id"), o.optString("name"), o.getDouble("lat"), o.getDouble("lon"),
                if (o.has("heading")) o.getDouble("heading").toFloat() else null)
        }
    }

    fun routes(): List<BusRoute> {
        val txt = (if (online) get("/routes?include=shapes,stops") else null) ?: asset("routes.json")
            ?: return emptyList()
        return parseRoutes(txt)
    }

    fun arrivals(stopId: String): List<Arrival> {
        if (!online) return emptyList() // stub
        val txt = get("/stops/$stopId/arrivals") ?: return emptyList()
        val a = JSONArray(txt)
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Arrival(o.getString("route"), stopId, o.getLong("eta"), o.optBoolean("realtime"))
        }
    }

    companion object {
        private const val TAG = "TallinjaApi"

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

        // Only used if the API does not supply a colour.
        private val FALLBACK = intArrayOf(0xFF1F77B4.toInt(), 0xFFFF7F0E.toInt(), 0xFF2CA02C.toInt(),
            0xFF9467BD.toInt(), 0xFF8C564B.toInt(), 0xFFE377C2.toInt(), 0xFF17BECF.toInt())

        private fun parseColor(s: String, i: Int): Int = try {
            Color.parseColor(if (s.startsWith("#")) s else "#$s")
        } catch (e: Exception) { FALLBACK[i % FALLBACK.size] }
    }
}
