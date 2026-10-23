package it.snel.melamur

import android.content.Context
import it.snel.melamur.data.DbManager
import it.snel.melamur.map.Geo
import it.snel.melamur.map.MapData
import it.snel.melamur.map.MapView
import it.snel.melamur.map.WalkGraph

/**
 * Warms up the expensive map assets while the splash animation plays, so the
 * map is already parsed in memory when MainActivity opens and can be shown
 * instantly. Results are memoised; builders are synchronized so a slow start
 * never causes the same work to run twice.
 */
object Preloader {
    @Volatile var mapData: MapData? = null
        private set
    @Volatile var labels: Triple<List<MapView.PlaceLabel>, List<MapView.StreetLabel>, List<MapView.PlaceLabel>>? = null
        private set
    @Volatile var walkGraph: WalkGraph? = null
        private set
    private var walkTried = false

    /** Kick off preparation in the background (idempotent). Call from the splash. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            runCatching { getLabels(app) }   // small: finish first so labels are ready once mapData is
            runCatching { getMapData(app) }  // the heavy parse
            runCatching { getWalkGraph(app) }  // foot-routing graph (absent walk.bin -> null)
            runCatching { if (DbManager.db == null && DbManager.isImported(app)) DbManager.openExisting(app) }
        }.start()
    }

    @Synchronized
    fun getMapData(ctx: Context): MapData =
        mapData ?: MapData(ctx.applicationContext).also { mapData = it }

    @Synchronized
    fun getLabels(ctx: Context): Triple<List<MapView.PlaceLabel>, List<MapView.StreetLabel>, List<MapView.PlaceLabel>> =
        labels ?: loadLabels(ctx.applicationContext).also { labels = it }

    @Synchronized
    fun getWalkGraph(ctx: Context): WalkGraph? {
        if (!walkTried) { walkTried = true; walkGraph = WalkGraph.load(ctx.applicationContext) }
        return walkGraph
    }

    private fun loadLabels(ctx: Context): Triple<List<MapView.PlaceLabel>, List<MapView.StreetLabel>, List<MapView.PlaceLabel>> = try {
        val root = org.json.JSONObject(ctx.assets.open("labels.json").bufferedReader().use { it.readText() })
        val pa = root.getJSONArray("places")
        val pl = List(pa.length()) { i ->
            val o = pa.getJSONObject(i)
            MapView.PlaceLabel(Geo.x(o.getDouble("lon")), Geo.y(o.getDouble("lat")), o.getString("n"), o.getInt("r"))
        }
        val sa = root.getJSONArray("streets")
        val st = List(sa.length()) { i ->
            val o = sa.getJSONObject(i)
            MapView.StreetLabel(Geo.x(o.getDouble("lon")), Geo.y(o.getDouble("lat")),
                Geo.x(o.getDouble("lon2")), Geo.y(o.getDouble("lat2")), o.getString("n"))
        }
        val la = root.optJSONArray("landmarks") ?: org.json.JSONArray()
        val lm = List(la.length()) { i ->
            val o = la.getJSONObject(i)
            MapView.PlaceLabel(Geo.x(o.getDouble("lon")), Geo.y(o.getDouble("lat")), o.getString("n"), 3)
        }
        Triple(pl, st, lm)
    } catch (e: Exception) {
        Triple(emptyList<MapView.PlaceLabel>(), emptyList<MapView.StreetLabel>(), emptyList<MapView.PlaceLabel>())
    }
}
