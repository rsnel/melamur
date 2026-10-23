package it.snel.melamur.data

import android.content.Context

/** App settings (SharedPreferences). */
object Settings {
    private const val P = "melamur"
    private fun p(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    /** Debug mode: run all three routing engines (with stats) and allow mock travel. */
    fun debug(ctx: Context) = p(ctx).getBoolean("debug", false)
    fun setDebug(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("debug", v).apply()

    /** Routing engine used when debug is off (default RAPTOR — McRAPTOR is slower). */
    fun engine(ctx: Context): Engine =
        try { Engine.valueOf(p(ctx).getString("engine", "RAPTOR")!!) } catch (e: Exception) { Engine.RAPTOR }
    fun setEngine(ctx: Context, e: Engine) = p(ctx).edit().putString("engine", e.name).apply()

    /** Saved startup map view [lat, lon, scale], or null. */
    fun startup(ctx: Context): DoubleArray? {
        val pr = p(ctx)
        if (!pr.contains("map_lat")) return null
        return doubleArrayOf(pr.getFloat("map_lat", 0f).toDouble(), pr.getFloat("map_lon", 0f).toDouble(),
            pr.getFloat("map_scale", 0.035f).toDouble())
    }
    fun setStartup(ctx: Context, lat: Double, lon: Double, scale: Float) =
        p(ctx).edit().putFloat("map_lat", lat.toFloat()).putFloat("map_lon", lon.toFloat())
            .putFloat("map_scale", scale).apply()
    fun clearStartup(ctx: Context) =
        p(ctx).edit().remove("map_lat").remove("map_lon").remove("map_scale").apply()
}
