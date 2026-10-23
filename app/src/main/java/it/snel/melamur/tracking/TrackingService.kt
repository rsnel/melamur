package it.snel.melamur.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import it.snel.melamur.MainActivity
import it.snel.melamur.R
import it.snel.melamur.data.BusStop
import it.snel.melamur.data.TallinjaApi
import it.snel.melamur.net.AggregatorClient

/** In-process hub between the service and the UI. */
object TripHub {
    var snapshot = TripSnapshot(); private set
    var status: Int = R.string.status_waiting; private set
    var destination: BusStop? = null
        set(v) { field = v; onDestination?.invoke(v) }
    internal var onDestination: ((BusStop?) -> Unit)? = null
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    internal fun publish(s: TripSnapshot, st: Int) {
        snapshot = s; status = st; main.post { listeners.forEach { it() } }
    }
}

/**
 * Foreground service: watches for the on-board network "Tallinja Wi-Fi"
 * (connected network or scan results). While it is seen we are on a bus: GPS
 * is switched on, fixes are map-matched and uploaded. When it has not been
 * seen for [LEAVE_AFTER_MS] the user has left the bus and GPS is released.
 */
class TrackingService : Service(), LocationListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var lm: LocationManager
    private lateinit var wm: WifiManager
    private lateinit var cm: ConnectivityManager
    private lateinit var agg: AggregatorClient
    @Volatile private var tracker: TripTracker? = null
    private var onBoard = false
    private var lastSeen = 0L
    private var busNetwork: Network? = null
    private var wasOffRoute = false

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LocationManager::class.java)
        wm = applicationContext.getSystemService(WifiManager::class.java)
        cm = getSystemService(ConnectivityManager::class.java)
        agg = AggregatorClient(this)
        channels()
        val n = statusNotification(getString(R.string.status_waiting))
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_STATUS, n)
        Thread {
            val api = TallinjaApi(this)
            tracker = TripTracker(api.stops(), api.routes()).also { it.destination = TripHub.destination }
        }.start()
        TripHub.onDestination = { d -> tracker?.destination = d }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), netCb)
        registerReceiver(scanRx, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        handler.post(tick)
        TripHub.publish(TripSnapshot(destination = TripHub.destination), R.string.status_waiting)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { cm.unregisterNetworkCallback(netCb) }
        runCatching { unregisterReceiver(scanRx) }
        leave()
        TripHub.onDestination = null
        super.onDestroy()
    }

    // ---- Wi-Fi detection
    private val netCb = if (Build.VERSION.SDK_INT >= 31)
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = onWifiCaps(n, c)
            override fun onLost(n: Network) = onWifiLost(n)
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = onWifiCaps(n, c)
            override fun onLost(n: Network) = onWifiLost(n)
        }

    @Suppress("DEPRECATION")
    private fun onWifiCaps(n: Network, c: NetworkCapabilities) {
        val info = if (Build.VERSION.SDK_INT >= 29) c.transportInfo as? WifiInfo else wm.connectionInfo
        if (info?.ssid?.trim('"') == BUS_SSID) handler.post { busNetwork = n; seen() }
    }

    private fun onWifiLost(n: Network) {
        handler.post { if (n == busNetwork) busNetwork = null }
    }

    private val scanRx = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            val found = runCatching {
                wm.scanResults.any { it.SSID == BUS_SSID && it.level > MIN_RSSI }
            }.getOrDefault(false)
            if (found) seen()
        }
    }

    private val tick = object : Runnable {
        @Suppress("DEPRECATION")
        override fun run() {
            if (busNetwork != null) seen()
            runCatching { wm.startScan() } // throttled by Android; best effort
            if (onBoard && System.currentTimeMillis() - lastSeen > LEAVE_AFTER_MS) leave()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun seen() {
        lastSeen = System.currentTimeMillis()
        if (!onBoard) board()
    }

    @SuppressLint("MissingPermission")
    private fun board() {
        onBoard = true
        Log.i(TAG, "boarded: GPS on")
        tracker?.reset(); agg.newTrip(); wasOffRoute = false
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper()) }
        catch (e: SecurityException) { Log.w(TAG, "no location permission", e) }
        notifyStatus(getString(R.string.status_onboard))
        TripHub.publish(TripSnapshot(onBoard = true, destination = TripHub.destination), R.string.status_onboard)
    }

    private fun leave() {
        if (!onBoard) return
        onBoard = false
        Log.i(TAG, "left bus: GPS released")
        lm.removeUpdates(this)
        agg.flush(); tracker?.reset()
        notifyStatus(getString(R.string.status_waiting))
        TripHub.publish(TripSnapshot(destination = TripHub.destination), R.string.status_waiting)
    }

    // ---- GPS
    override fun onLocationChanged(l: Location) {
        val t = tracker ?: return
        val brg = if (l.hasBearing()) l.bearing else null
        val s = t.onFix(l.latitude, l.longitude, l.accuracy, l.speed, brg)
        agg.add(l.time, l.latitude, l.longitude, l.accuracy, l.speed, brg, s.routeId)
        TripHub.publish(s, R.string.status_onboard)
        if (t.shouldAlertPressStop()) alert(getString(R.string.press_stop), s.destination?.name ?: "")
        if (s.offRoute && !wasOffRoute) alert(getString(R.string.off_route), s.routeName?.let { "Route $it" } ?: "")
        wasOffRoute = s.offRoute
        notifyStatus(s.nextStop?.let { "Next stop: ${it.name}" } ?: getString(R.string.status_onboard))
    }

    @Deprecated("needed for API < 29")
    override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    // ---- notifications
    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(true); vibrationPattern = VIBE })
    }

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun statusNotification(text: String): Notification = Notification.Builder(this, CH_STATUS)
        .setSmallIcon(R.drawable.ic_logo).setContentTitle(getString(R.string.app_name)).setContentText(text)
        .setColor(0xFFEE0000.toInt()).setOngoing(true).setContentIntent(openApp()).build()

    private fun notifyStatus(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_STATUS, statusNotification(text))

    @Suppress("DEPRECATION")
    private fun alert(title: String, text: String) {
        val n = Notification.Builder(this, CH_ALERT).setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title).setContentText(text).setColor(0xFFEE0000.toInt())
            .setCategory(Notification.CATEGORY_ALARM).setAutoCancel(true).setContentIntent(openApp()).build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n) }
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(VIBE, -1))
    }

    companion object {
        const val BUS_SSID = "Tallinja Wi-Fi"
        private const val TAG = "Tracking"
        private const val MIN_RSSI = -75
        private const val TICK_MS = 15_000L
        private const val LEAVE_AFTER_MS = 60_000L
        private const val CH_STATUS = "status"; private const val CH_ALERT = "alerts"
        private const val NOTIF_STATUS = 1; private const val NOTIF_ALERT = 2
        private val VIBE = longArrayOf(0, 400, 200, 400, 200, 800)
    }
}
