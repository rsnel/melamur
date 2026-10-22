package it.snel.melamur.data

import android.content.Context
import it.snel.melamur.map.Geo
import org.json.JSONArray
import org.json.JSONObject

/** A user-stored point of interest. */
data class Poi(val id: String, val name: String, val lat: Double, val lon: Double) {
    val x = Geo.x(lon)
    val y = Geo.y(lat)
}

/** Persists POIs in SharedPreferences as JSON. */
object PoiStore {
    private const val PREFS = "melamur"
    private const val KEY = "pois"

    fun load(ctx: Context): MutableList<Poi> {
        val txt = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return mutableListOf()
        return try {
            val a = JSONArray(txt)
            MutableList(a.length()) { i ->
                val o = a.getJSONObject(i)
                Poi(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
            }
        } catch (e: Exception) { mutableListOf() }
    }

    private fun save(ctx: Context, list: List<Poi>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("id", p.id).put("name", p.name).put("lat", p.lat).put("lon", p.lon))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun add(ctx: Context, name: String, lat: Double, lon: Double): Poi {
        val list = load(ctx)
        val poi = Poi("poi-" + System.currentTimeMillis(), name, lat, lon)
        list.add(poi); save(ctx, list); return poi
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, load(ctx).filterNot { it.id == id })
    }
}
