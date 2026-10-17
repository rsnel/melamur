package it.snel.melamur

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import it.snel.melamur.data.TallinjaApi
import it.snel.melamur.map.MapData
import it.snel.melamur.map.MapView
import it.snel.melamur.map.RouteRenderer
import it.snel.melamur.tracking.TrackingService
import it.snel.melamur.tracking.TripHub

class MainActivity : Activity(), LocationListener {
    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var nextStop: TextView
    private lateinit var destination: TextView
    private lateinit var alert: TextView
    private lateinit var lm: LocationManager
    private val prefs by lazy { getSharedPreferences("melamur", MODE_PRIVATE) }
    private val hasConsent get() = prefs.getLong("consent_at", 0L) > 0L
    private val hubListener = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        status = findViewById(R.id.status); nextStop = findViewById(R.id.nextStop)
        destination = findViewById(R.id.destination); alert = findViewById(R.id.alert)
        lm = getSystemService(LocationManager::class.java)

        findViewById<Button>(R.id.center).setOnClickListener {
            if (!map.centerOnMe()) Toast.makeText(this, "Waiting for your location…", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.privacy).setOnClickListener { showConsent(firstRun = false) }
        findViewById<Button>(R.id.search).setOnClickListener { showSearch() }
        map.onStopTapped = { s ->
            AlertDialog.Builder(this).setTitle(s.name.ifEmpty { "Bus stop" })
                .setMessage("Warn me to press STOP before this stop?")
                .setPositiveButton("Set destination") { _, _ -> TripHub.destination = s; render() }
                .setNeutralButton("Clear destination") { _, _ -> TripHub.destination = null; render() }
                .setNegativeButton(R.string.close, null).show()
        }

        Thread {
            val api = TallinjaApi(this)
            val data = MapData(this)
            val stops = api.stops()
            val byId = stops.associateBy { it.id }
            val routes = api.routes().map { RouteRenderer.build(it, byId) }
            val (places, streets) = loadLabels()
            runOnUiThread {
                map.data = data; map.stops = stops; map.routes = routes
                map.places = places; map.streets = streets
            }
        }.start()

        if (!hasConsent && !prefs.getBoolean("declined", false)) showConsent(firstRun = true)
        else if (hasConsent) requestPerms()
        render()
    }

    override fun onResume() {
        super.onResume()
        TripHub.listen(hubListener)
        startOwnLocation()
        render()
    }

    override fun onPause() {
        TripHub.unlisten(hubListener)
        lm.removeUpdates(this) // the UI only uses location while visible
        super.onPause()
    }

    // ---------------- stop search
    private fun showSearch() {
        val d = (resources.displayMetrics.density)
        val pad = (16 * d).toInt()
        val white = android.graphics.Color.WHITE
        val grey = android.graphics.Color.rgb(176, 176, 176)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0)
        }
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.search_hint); setSingleLine()
            setTextColor(white); setHintTextColor(grey)
        }
        val list = android.widget.ListView(this)
        box.addView(input); box.addView(list)
        val adapter = object : android.widget.ArrayAdapter<it.snel.melamur.data.BusStop>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList()
        ) {
            override fun getView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(pos, cv, parent)
                val s = getItem(pos)!!
                v.findViewById<android.widget.TextView>(android.R.id.text1).apply {
                    text = s.name; setTextColor(white)
                }
                v.findViewById<android.widget.TextView>(android.R.id.text2).apply {
                    val dir = s.compass?.let { " · ${s.arrow} $it" } ?: ""
                    text = "${s.island}$dir" + (s.code?.let { "  ·  #$it" } ?: "")
                    setTextColor(grey)
                }
                return v
            }
        }
        list.adapter = adapter
        val dialog = AlertDialog.Builder(this).setTitle(R.string.search_title).setView(box)
            .setNegativeButton(R.string.close, null).create()
        fun refresh() {
            val r = it.snel.melamur.data.StopSearch.search(map.stops, input.text.toString())
            adapter.clear(); adapter.addAll(r); adapter.notifyDataSetChanged()
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val s = adapter.getItem(pos) ?: return@setOnItemClickListener
            TripHub.destination = s; map.centerOn(s); render(); dialog.dismiss()
        }
        dialog.show()
    }

    // ---------------- consent (GDPR)
    private fun showConsent(firstRun: Boolean) {
        val b = AlertDialog.Builder(this).setTitle(R.string.consent_title).setMessage(R.string.consent_text)
            .setCancelable(!firstRun)
        if (hasConsent) {
            b.setPositiveButton(R.string.close, null)
            b.setNegativeButton(R.string.consent_withdraw) { _, _ ->
                prefs.edit().remove("consent_at").putBoolean("declined", true).apply()
                stopService(Intent(this, TrackingService::class.java)); render()
            }
        } else {
            b.setPositiveButton(R.string.consent_accept) { _, _ ->
                prefs.edit().putLong("consent_at", System.currentTimeMillis()).remove("declined").apply()
                requestPerms()
            }
            b.setNegativeButton(R.string.consent_decline) { _, _ ->
                prefs.edit().putBoolean("declined", true).apply(); render()
            }
        }
        b.show()
    }

    // ---------------- permissions
    private fun requestPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.POST_NOTIFICATIONS; p += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        val missing = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startTracking() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startTracking(); startOwnLocation()
        } else Toast.makeText(this, "Without location MelaMur cannot detect your bus ride.", Toast.LENGTH_LONG).show()
    }

    private fun startTracking() {
        if (!hasConsent) return
        startForegroundService(Intent(this, TrackingService::class.java))
    }

    @SuppressLint("MissingPermission")
    private fun startOwnLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val providers = if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER))
            listOf(LocationManager.FUSED_PROVIDER) else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (pr in providers) runCatching {
            lm.getLastKnownLocation(pr)?.let { onLocationChanged(it) }
            lm.requestLocationUpdates(pr, 2000L, 0f, this)
        }
    }

    private fun loadLabels(): Pair<List<MapView.PlaceLabel>, List<MapView.StreetLabel>> = try {
        val root = org.json.JSONObject(assets.open("labels.json").bufferedReader().use { it.readText() })
        val pa = root.getJSONArray("places")
        val places = List(pa.length()) { i ->
            val o = pa.getJSONObject(i)
            MapView.PlaceLabel(it.snel.melamur.map.Geo.x(o.getDouble("lon")),
                it.snel.melamur.map.Geo.y(o.getDouble("lat")), o.getString("n"), o.getInt("r"))
        }
        val sa = root.getJSONArray("streets")
        val streets = List(sa.length()) { i ->
            val o = sa.getJSONObject(i)
            MapView.StreetLabel(it.snel.melamur.map.Geo.x(o.getDouble("lon")),
                it.snel.melamur.map.Geo.y(o.getDouble("lat")),
                it.snel.melamur.map.Geo.x(o.getDouble("lon2")),
                it.snel.melamur.map.Geo.y(o.getDouble("lat2")), o.getString("n"))
        }
        places to streets
    } catch (e: Exception) { emptyList<MapView.PlaceLabel>() to emptyList() }

    override fun onLocationChanged(l: Location) =
        map.setMyLocation(l.latitude, l.longitude, l.accuracy, if (l.hasBearing() && l.speed > 2f) l.bearing else null)

    @Deprecated("needed for API < 29")
    override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    // ---------------- panel
    private fun render() {
        val s = TripHub.snapshot
        status.setText(if (!hasConsent) R.string.status_off else TripHub.status)
        if (s.onBoard && s.routeName != null) status.append("  ·  Route ${s.routeName} → ${s.headsign ?: ""}")
        nextStop.text = s.nextStop?.let { n ->
            "Next stop: ${n.name.ifEmpty { "—" }}" + (s.nextStopDist?.let { " (${it.toInt()} m)" } ?: "")
        } ?: if (s.onBoard) "Locating bus…" else "Tap a stop to set your destination"
        nextStop.visibility = View.VISIBLE
        val d = TripHub.destination
        destination.text = d?.let { "Destination: ${it.name}" } ?: ""
        destination.visibility = if (d == null) View.GONE else View.VISIBLE
        val msg = when {
            s.pressStop -> getString(R.string.press_stop)
            s.offRoute -> getString(R.string.off_route)
            else -> null
        }
        alert.text = msg; alert.visibility = if (msg == null) View.GONE else View.VISIBLE
        map.activeRouteId = s.routeId; map.nextStopId = s.nextStop?.id; map.destinationStopId = d?.id
        map.invalidate()
    }
}
