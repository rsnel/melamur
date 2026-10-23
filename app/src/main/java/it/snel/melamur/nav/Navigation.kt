package it.snel.melamur.nav

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import it.snel.melamur.MainActivity
import it.snel.melamur.R
import it.snel.melamur.data.BusLeg
import it.snel.melamur.data.Itinerary
import it.snel.melamur.data.Stop
import it.snel.melamur.data.WalkLeg

/** State shared between NavigationService and the UI. */
data class NavSnapshot(
    val active: Boolean = false, val instruction: String = "", val detail: String = "",
    val distanceM: Int = 0, val pressStop: Boolean = false, val arrived: Boolean = false,
    val lat: Double? = null, val lon: Double? = null, val bearing: Float? = null,
    val simulated: Boolean = false,
)

object NavHub {
    @Volatile var itinerary: Itinerary? = null
    @Volatile var stops: Map<Int, Stop> = emptyMap()   // stop number -> coords
    // debug simulation: a pre-built path of [lat, lon, bearing] points to replay as GPS
    @Volatile var simulate = false
    @Volatile var simPoints: List<DoubleArray> = emptyList()
    @Volatile var snapshot = NavSnapshot(); private set
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    internal fun publish(s: NavSnapshot) { snapshot = s; main.post { listeners.forEach { it() } } }
    fun clear() { itinerary = null; simulate = false; simPoints = emptyList(); publish(NavSnapshot()) }
}

/**
 * Turn-by-turn-ish navigation along a planned [Itinerary], driven by GPS only
 * (no Wi-Fi). It guides the walk legs, and on a bus leg it fires a vibrating
 * "press STOP" alert as the alight stop approaches, then advances when reached.
 *
 * Simplified: it tracks the END of each leg (not every intermediate stop); the
 * press-STOP warning triggers on distance to the alight stop.
 */
class NavigationService : Service(), LocationListener {
    private lateinit var lm: LocationManager
    private var leg = 0
    private var warned = false
    private val handler = Handler(Looper.getMainLooper())
    private var simIndex = 0

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LocationManager::class.java)
        channels()
        val n = note(getString(R.string.nav_starting), "")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF, n)
        if (NavHub.simulate && NavHub.simPoints.isNotEmpty()) {
            handler.post(simTick)  // debug: replay a synthesized journey instead of GPS
        } else {
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper()) }
            catch (e: SecurityException) { stopSelf() }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(simTick)
        runCatching { lm.removeUpdates(this) }
        super.onDestroy()
    }

    private val simTick = object : Runnable {
        override fun run() {
            val pts = NavHub.simPoints
            if (NavHub.itinerary == null || simIndex >= pts.size) { arrive(); return }
            val p = pts[simIndex++]
            val loc = Location("sim").apply {
                latitude = p[0]; longitude = p[1]; accuracy = 5f; bearing = p[2].toFloat()
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            }
            onLocationChanged(loc)
            if (NavHub.itinerary != null && simIndex < pts.size) handler.postDelayed(this, SIM_MS)
        }
    }

    private fun legTarget(l: Any): DoubleArray? = when (l) {
        is WalkLeg -> doubleArrayOf(l.toLat, l.toLon)
        is BusLeg -> NavHub.stops[l.alightNumber]?.let { doubleArrayOf(it.lat, it.lon) }
        else -> null
    }

    override fun onLocationChanged(loc: Location) {
        val it = NavHub.itinerary ?: return stopSelf()
        if (leg >= it.legs.size) { arrive(); return }
        val l = it.legs[leg]
        val tgt = legTarget(l)
        if (tgt == null) { advance(); return }
        val d = dist(loc.latitude, loc.longitude, tgt[0], tgt[1])
        val reach = if (l is BusLeg) 50f else 30f
        val (instr, detail) = when (l) {
            is WalkLeg -> getString(R.string.nav_walk_to, l.toName) to "${l.meters} m"
            is BusLeg -> getString(R.string.nav_ride_to, l.routeCode, l.toName) to
                (l.towards.ifEmpty { "" })
            else -> "" to ""
        }
        var press = false
        if (l is BusLeg && d < PRESS_DIST) {
            press = true
            if (!warned) { warned = true; alert(getString(R.string.press_stop), l.toName) }
        }
        NavHub.publish(NavSnapshot(true, instr, detail, d.toInt(), press,
            lat = loc.latitude, lon = loc.longitude,
            bearing = if (loc.hasBearing()) loc.bearing else null, simulated = NavHub.simulate))
        update(instr, "${d.toInt()} m")
        if (d < reach) advance()
    }

    private fun advance() {
        leg++; warned = false
        val it = NavHub.itinerary
        if (it == null || leg >= it.legs.size) arrive()
    }

    private fun arrive() {
        NavHub.publish(NavSnapshot(active = false, instruction = getString(R.string.nav_arrived), arrived = true))
        update(getString(R.string.nav_arrived), "")
        stopSelf()
    }

    @Deprecated("API < 29") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    // ---- notifications
    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.channel_nav), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(true); vibrationPattern = VIBE })
    }
    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun note(title: String, text: String): Notification = Notification.Builder(this, CH)
        .setSmallIcon(R.drawable.ic_logo).setContentTitle(title).setContentText(text)
        .setColor(0xFFEE0000.toInt()).setOngoing(true).setContentIntent(openApp()).build()
    private fun update(title: String, text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF, note(title, text))
    @SuppressLint("MissingPermission")
    private fun alert(title: String, text: String) {
        val n = Notification.Builder(this, CH_ALERT).setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title).setContentText(text).setColor(0xFFEE0000.toInt())
            .setCategory(Notification.CATEGORY_ALARM).setAutoCancel(true).setContentIntent(openApp()).build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n) }
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(VIBE, -1))
    }

    private fun dist(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val r = FloatArray(1); Location.distanceBetween(aLat, aLon, bLat, bLon, r); return r[0]
    }

    companion object {
        private const val CH = "nav"; private const val CH_ALERT = "alerts"
        private const val NOTIF = 10; private const val NOTIF_ALERT = 11
        private const val PRESS_DIST = 350f
        private const val SIM_MS = 300L   // debug playback tick
        private val VIBE = longArrayOf(0, 400, 200, 400, 200, 800)
    }
}
