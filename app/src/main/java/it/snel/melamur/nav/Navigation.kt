package it.snel.melamur.nav

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import it.snel.melamur.data.DbManager
import it.snel.melamur.data.Itinerary
import it.snel.melamur.data.Malta
import it.snel.melamur.data.Place
import it.snel.melamur.data.Planner
import it.snel.melamur.data.Stop
import it.snel.melamur.data.WalkLeg
import java.time.Duration
import java.time.LocalDateTime

/** State shared between NavigationService and the UI. */
data class NavSnapshot(
    val active: Boolean = false, val instruction: String = "", val detail: String = "",
    val distanceM: Int = 0, val pressStop: Boolean = false, val arrived: Boolean = false,
    val lat: Double? = null, val lon: Double? = null, val bearing: Float? = null,
    val simulated: Boolean = false,
    /** A message for the user (e.g. after re-planning); [messageId] changes with each new one. */
    val message: String = "", val messageId: Int = 0,
    val replanning: Boolean = false,
)

object NavHub {
    @Volatile var itinerary: Itinerary? = null
    @Volatile var to: Place? = null                     // where the journey ends (for re-planning)
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
    fun clear() { itinerary = null; to = null; simulate = false; simPoints = emptyList(); publish(NavSnapshot()) }

    /** "The bus left without me": re-plan from here, without that bus. */
    fun busLeft(ctx: Context) {
        ctx.startService(Intent(ctx, NavigationService::class.java).setAction(NavigationService.ACTION_BUS_LEFT))
    }
}

/**
 * Interactive navigation along a planned [Itinerary], driven by GPS.
 *  - Follows you leg by leg (walk to the stop, ride, walk on) and guesses where
 *    you are: a walk leg ends near its target; a bus leg once you near the stop.
 *  - On a bus leg it knows the stops of your trip: once the stop BEFORE yours is
 *    clearly passed (you were near it and are now moving away), it vibrates and
 *    says "press STOP".
 *  - It re-plans from where you are when things go wrong: you are not at the
 *    stop when your bus leaves, you are far off the bus route, or you tell it the
 *    bus left without you (then that bus is left out), with a reassuring note.
 */
class NavigationService : Service(), LocationListener {
    private lateinit var lm: LocationManager
    private var leg = 0
    private var warned = false
    private val handler = Handler(Looper.getMainLooper())
    private var simIndex = 0
    private var last: Location? = null
    // press-STOP tracking for the current bus leg
    private var legStops: List<DoubleArray> = emptyList()   // lat, lon of the trip's stops, board..alight
    private var prevNear = false
    private var lastReplan = 0L
    @Volatile private var replanning = false
    private var messageId = 0
    private var message = ""

    override fun attachBaseContext(base: Context) = super.attachBaseContext(it.snel.melamur.Lang.wrap(base))

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
        enterLeg()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_BUS_LEFT) {
            val missed = NavHub.itinerary?.legs?.drop(leg)?.filterIsInstance<BusLeg>()?.firstOrNull()?.tripId
            replan(getString(R.string.nav_bus_left_comfort), missed, force = true)
        }
        return START_NOT_STICKY
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

    /** New leg: for a bus leg, load the stops of the trip between boarding and alighting. */
    private fun enterLeg() {
        warned = false; prevNear = false; legStops = emptyList()
        val l = NavHub.itinerary?.legs?.getOrNull(leg) as? BusLeg ?: return
        val db = DbManager.db ?: return
        Thread {
            val ts = runCatching { db.tripStops(l.tripId) }.getOrDefault(emptyList())
            val b = ts.indexOfFirst { it.number == l.boardNumber }
            val a = ts.indexOfLast { it.number == l.alightNumber }
            if (b >= 0 && a > b) {
                val pts = ts.subList(b, a + 1).mapNotNull { t -> t.number?.let { NavHub.stops[it] }?.let { doubleArrayOf(it.lat, it.lon) } }
                handler.post { legStops = pts }
            }
        }.start()
    }

    /** Minutes since the itinerary's service day began (Malta time). */
    private fun nowMinutes(it: Itinerary): Long =
        Duration.between(it.serviceDate.atStartOfDay(), LocalDateTime.now(Malta.ZONE)).toMinutes()

    override fun onLocationChanged(loc: Location) {
        last = loc
        val it = NavHub.itinerary ?: return stopSelf()
        if (leg >= it.legs.size) { arrive(); return }
        val l = it.legs[leg]
        val tgt = legTarget(l)
        if (tgt == null) { advance(); return }
        val d = dist(loc.latitude, loc.longitude, tgt[0], tgt[1])
        val reach = if (l is BusLeg) 50f else 30f
        val (instr, detail) = when (l) {
            is WalkLeg -> {
                val next = it.legs.getOrNull(leg + 1) as? BusLeg
                getString(R.string.nav_walk_to, l.toName) to
                    (next?.let { getString(R.string.nav_catch, it.routeCode, Malta.hhmm(it.boardMinutes)) } ?: "${l.meters} m")
            }
            is BusLeg -> getString(R.string.nav_ride_to, l.routeCode, l.toName) to l.towards
            else -> "" to ""
        }
        var press = false
        if (l is BusLeg) press = pressStopNow(loc, l, d)
        if (press && !warned) { warned = true; alert(getString(R.string.press_stop), l.toName) }
        publish(instr, detail, d.toInt(), press || warned && l is BusLeg && d < 400f, loc)
        update(instr, "${d.toInt()} m")
        if (d < reach) { advance(); return }
        if (!NavHub.simulate) checkOffPlan(it, l, loc)
    }

    /**
     * Press STOP once the stop before yours is clearly behind you: you came within
     * 80 m of it and are now 120 m past it, closer to your stop. Without the trip's
     * stops, fall back to 350 m from your stop.
     */
    private fun pressStopNow(loc: Location, l: BusLeg, dAlight: Float): Boolean {
        val pts = legStops
        if (pts.size < 2) return dAlight < PRESS_DIST
        val prev = pts[pts.size - 2]; val stop = pts.last()
        val dPrev = dist(loc.latitude, loc.longitude, prev[0], prev[1])
        val gap = dist(prev[0], prev[1], stop[0], stop[1])
        if (dPrev < 80f) prevNear = true
        if (prevNear && dPrev > 120f && dAlight < gap + 50f) return true
        // GPS missed the previous stop: well past it and getting close to yours
        return dAlight < gap * 0.6f && dAlight < dPrev && dPrev > 150f
    }

    /** Re-plan when you can no longer make it: not at the stop when the bus goes, or far off the bus route. */
    private fun checkOffPlan(it: Itinerary, l: Any, loc: Location) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (replanning || now - lastReplan < 90_000) return
        when (l) {
            is WalkLeg -> {
                val next = it.legs.getOrNull(leg + 1) as? BusLeg ?: return
                val stop = NavHub.stops[next.boardNumber] ?: return
                val away = dist(loc.latitude, loc.longitude, stop.lat, stop.lon)
                if (nowMinutes(it) > next.boardMinutes + 1 && away > 150f)
                    replan(getString(R.string.nav_missed_comfort), next.tripId, force = false)
            }
            is BusLeg -> {
                val pts = legStops; if (pts.isEmpty()) return
                val near = pts.minOf { p -> dist(loc.latitude, loc.longitude, p[0], p[1]) }
                // still waiting at the stop is fine; well away from every stop of the trip is not
                if (near > 700f) replan(getString(R.string.nav_off_route_comfort), null, force = false)
            }
        }
    }

    /** Plan again from where you are, now (leaving out [skipTrip]); keep going with the best option. */
    private fun replan(why: String, skipTrip: Long?, force: Boolean) {
        val loc = last; val to = NavHub.to; val db = DbManager.db
        if (replanning) return
        if (loc == null || to == null || db == null) {
            say(getString(R.string.nav_replan_nowhere)); return
        }
        replanning = true; lastReplan = android.os.SystemClock.elapsedRealtime()
        publish(getString(R.string.nav_replanning), why, 0, false, loc, replan = true)
        Thread {
            val here = Place(getString(R.string.nav_here), loc.latitude, loc.longitude)
            val res = runCatching { Planner.plan(db, NavHub.stops.values.toList(), here, to, LocalDateTime.now(Malta.ZONE)) }
                .getOrDefault(emptyList())
            val pick = res.firstOrNull { r -> skipTrip == null || r.busLegs.none { it.tripId == skipTrip } }
            handler.post {
                replanning = false
                if (pick == null) { say("$why\n\n" + getString(R.string.nav_replan_none)); return@post }
                NavHub.itinerary = pick; leg = 0; enterLeg()
                val first = pick.busLegs.firstOrNull()
                val next = first?.let {
                    val mins = Duration.between(LocalDateTime.now(Malta.ZONE), it.board).toMinutes().coerceAtLeast(0)
                    getString(R.string.nav_replan_next, it.routeCode, Malta.hhmm(it.boardMinutes), mins, it.fromName)
                } ?: getString(R.string.nav_replan_walk)
                say("$why\n\n$next")
                last?.let { onLocationChanged(it) }
            }
        }.start()
    }

    private fun say(text: String) {
        message = text; messageId++
        val s = NavHub.snapshot
        NavHub.publish(s.copy(message = message, messageId = messageId, replanning = false))
    }

    private fun publish(instr: String, detail: String, d: Int, press: Boolean, loc: Location, replan: Boolean = false) {
        NavHub.publish(NavSnapshot(true, instr, detail, d, press,
            lat = loc.latitude, lon = loc.longitude,
            bearing = if (loc.hasBearing()) loc.bearing else null, simulated = NavHub.simulate,
            message = message, messageId = messageId, replanning = replan || replanning))
    }

    private fun advance() {
        leg++
        val it = NavHub.itinerary
        if (it == null || leg >= it.legs.size) arrive() else enterLeg()
    }

    private fun arrive() {
        NavHub.publish(NavSnapshot(active = false, instruction = getString(R.string.nav_arrived), arrived = true,
            message = message, messageId = messageId))
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
        const val ACTION_BUS_LEFT = "it.snel.melamur.BUS_LEFT"
        private const val CH = "nav"; private const val CH_ALERT = "alerts"
        private const val NOTIF = 10; private const val NOTIF_ALERT = 11
        private const val PRESS_DIST = 350f
        private const val SIM_MS = 300L   // debug playback tick
        private val VIBE = longArrayOf(0, 400, 200, 400, 200, 800)
    }
}
