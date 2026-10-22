package it.snel.melamur

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import it.snel.melamur.data.*
import it.snel.melamur.map.Geo
import it.snel.melamur.map.MapData
import it.snel.melamur.map.MapView
import it.snel.melamur.map.RouteRenderer
import it.snel.melamur.nav.NavHub
import it.snel.melamur.nav.NavigationService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : Activity(), LocationListener {
    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var nextStop: TextView
    private lateinit var destination: TextView
    private lateinit var alert: TextView
    private lateinit var lm: LocationManager
    private val prefs by lazy { getSharedPreferences("melamur", MODE_PRIVATE) }
    private val hasConsent get() = prefs.getLong("consent_at", 0L) > 0L
    private val navListener = { render() }
    private val fmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

    // data
    private var allStops: List<Stop> = emptyList()
    private var busStopsById: Map<String, BusStop> = emptyMap()
    private var places: List<MapView.PlaceLabel> = emptyList()
    private var streets: List<MapView.StreetLabel> = emptyList()
    private var landmarks: List<MapView.PlaceLabel> = emptyList()
    private var pois: MutableList<Poi> = mutableListOf()
    private var searchIndex: SearchIndex.Index? = null

    // planner selections
    private var from: Place? = null
    private var to: Place? = null
    private var departWhen: LocalDateTime? = null

    private val white = Color.WHITE
    private val grey = Color.rgb(176, 176, 176)

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
        findViewById<Button>(R.id.search).setOnClickListener { showSearch { handleMapPick(it) } }
        findViewById<Button>(R.id.menu).setOnClickListener { showMenu() }
        map.onStopTapped = { bs -> stopFor(bs)?.let { showStop(it) } }
        map.onMapLongPress = { lat, lon, poi -> showLongPressMenu(lat, lon, poi) }
        destination.setOnClickListener { if (NavHub.snapshot.active || NavHub.snapshot.arrived) cancelNav() }

        pois = PoiStore.load(this); map.pois = pois; rebuildIndex()

        Thread {
            val data = MapData(this)
            val (pl, st, lm) = loadLabels()
            runOnUiThread { map.data = data; places = pl; streets = st; landmarks = lm; rebuildIndex() }
        }.start()

        if (DbManager.isImported(this)) {
            Thread { DbManager.openExisting(this); loadData() }.start()
        } else {
            promptImport()
        }
        requestLocationPerms()
        render()
    }

    override fun onResume() { super.onResume(); NavHub.listen(navListener); startOwnLocation(); render() }
    override fun onPause() { NavHub.unlisten(navListener); lm.removeUpdates(this); super.onPause() }

    // ---------------- database import (SAF)
    private fun promptImport() {
        AlertDialog.Builder(this).setTitle(R.string.db_pick_title).setMessage(R.string.db_pick_text)
            .setPositiveButton(R.string.db_choose) { _, _ -> pickDatabase() }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun pickDatabase() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        startActivityForResult(i, REQ_DB)
    }

    @Deprecated("plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DB && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Toast.makeText(this, R.string.db_importing, Toast.LENGTH_SHORT).show()
            Thread {
                val r = DbManager.import(this, uri)
                runOnUiThread {
                    r.onSuccess { Toast.makeText(this, R.string.db_imported, Toast.LENGTH_SHORT).show() }
                     .onFailure { Toast.makeText(this, getString(R.string.db_import_failed, it.message), Toast.LENGTH_LONG).show() }
                }
                if (r.isSuccess) loadData()
            }.start()
        }
    }

    /** Load stops from the DB onto the map (background thread). */
    private fun loadData() {
        val db = DbManager.db ?: return
        val v = db.versionFor(LocalDate.now(Malta.ZONE))
        val stops = db.allStops(v)
        val bs = stops.map { it.asBusStop() }
        allStops = stops
        NavHub.stops = stops.associateBy { it.number }
        val first = db.firstValidFrom()
        val today = LocalDate.now(Malta.ZONE).toString()
        runOnUiThread {
            busStopsById = bs.associateBy { it.id }
            map.stops = bs
            rebuildIndex()
            if (first != null && today < first) toast(getString(R.string.db_future, first))
            render()
        }
    }

    private fun rebuildIndex() { searchIndex = SearchIndex.build(allStops, places, streets, landmarks, pois) }

    private fun stopFor(bs: BusStop): Stop? = allStops.firstOrNull { it.number.toString() == bs.id }

    // ---------------- stop → departures board + lines serving it
    private fun showStop(stop: Stop) {
        val db = DbManager.db ?: return
        Thread {
            val now = LocalDateTime.now(Malta.ZONE)
            val v = db.versionFor(now.toLocalDate())
            val lines = db.linesForStop(stop.number, v)
            val colorOf = lines.associate { it.code to it.color }
            val board = db.departuresFrom(stop.number, now, 15)
            runOnUiThread {
                val pad = (12 * resources.displayMetrics.density).toInt()
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
                val header = TextView(this).apply {
                    text = if (lines.isEmpty()) "No lines serve this stop"
                    else "Lines: " + lines.joinToString("  ") { it.code }
                    setTextColor(grey); setPadding(0, 0, 0, pad)
                }
                val list = ListView(this)
                box.addView(header); box.addView(list)
                val adapter = object : ArrayAdapter<Departure>(this, android.R.layout.simple_list_item_2,
                    android.R.id.text1, ArrayList(board)) {
                    override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                        val view = super.getView(pos, cv, parent); val d = getItem(pos)!!
                        view.findViewById<TextView>(android.R.id.text1).apply {
                            text = "●  ${d.routeCode}   →  ${d.towards}"
                            setTextColor(colorOf[d.routeCode] ?: white)
                        }
                        val mins = java.time.Duration.between(now, d.time).toMinutes()
                        val cd = if (mins <= 0) "now" else "in $mins min"
                        view.findViewById<TextView>(android.R.id.text2).apply {
                            text = "${Malta.hhmm(d.minutes)}   ·   $cd"; setTextColor(grey)
                        }
                        return view
                    }
                }
                list.adapter = adapter
                val dlg = AlertDialog.Builder(this)
                    .setTitle(stop.display + "  ·  #${stop.number}")
                    .setView(box)
                    .setPositiveButton(R.string.from_here) { _, _ -> from = Place(stop.display, stop.lat, stop.lon); toast("Start: ${stop.display}") }
                    .setNeutralButton(R.string.to_here) { _, _ -> to = Place(stop.display, stop.lat, stop.lon); toast("Destination: ${stop.display}") }
                    .setNegativeButton(R.string.close, null)
                    .create()
                list.setOnItemClickListener { _, _, p, _ -> adapter.getItem(p)?.let { showRouteOnMap(it.routeCode) }; dlg.dismiss() }
                if (board.isEmpty()) { header.append("\n\nNo more departures today."); }
                dlg.show()
            }
        }.start()
    }

    private fun showRouteOnMap(code: String) {
        val db = DbManager.db ?: return
        Thread {
            val v = db.versionFor(LocalDate.now(Malta.ZONE))
            val dirs = db.directionsForRoute(code, v)
            if (dirs.isEmpty()) return@Thread
            val route = BusRoute(code, code, dirs.first().color, dirs.map {
                RouteDirection(it.directionIndex.toString(), it.name, it.shape, it.stopNumbers)
            })
            val gfx = RouteRenderer.build(route, busStopsById)
            runOnUiThread { map.routes = listOf(gfx); map.activeRouteId = code; map.invalidate() }
        }.start()
    }

    // ---------------- long-press POI menu
    private fun showLongPressMenu(lat: Double, lon: Double, poi: Poi?) {
        if (poi == null) {
            val items = arrayOf(getString(R.string.poi_store), getString(R.string.set_from), getString(R.string.set_to))
            AlertDialog.Builder(this).setItems(items) { _, w ->
                when (w) {
                    0 -> storePoi(lat, lon)
                    1 -> { from = Place("Dropped pin", lat, lon); toast("Start set") }
                    2 -> { to = Place("Dropped pin", lat, lon); toast("Destination set") }
                }
            }.show()
        } else {
            val items = arrayOf(getString(R.string.set_from), getString(R.string.set_to), getString(R.string.poi_delete))
            AlertDialog.Builder(this).setTitle(poi.name).setItems(items) { _, w ->
                when (w) {
                    0 -> { from = Place(poi.name, poi.lat, poi.lon); toast("Start: ${poi.name}") }
                    1 -> { to = Place(poi.name, poi.lat, poi.lon); toast("Destination: ${poi.name}") }
                    2 -> AlertDialog.Builder(this).setMessage(R.string.poi_delete_confirm)
                        .setPositiveButton(R.string.poi_delete) { _, _ ->
                            PoiStore.remove(this, poi.id); pois = PoiStore.load(this); map.pois = pois; rebuildIndex()
                        }.setNegativeButton(R.string.close, null).show()
                }
            }.show()
        }
    }

    private fun storePoi(lat: Double, lon: Double) {
        val input = EditText(this).apply { hint = getString(R.string.poi_name); setTextColor(white); setHintTextColor(grey) }
        AlertDialog.Builder(this).setTitle(R.string.poi_store).setView(input)
            .setPositiveButton(R.string.poi_store) { _, _ ->
                val name = input.text.toString().ifBlank { "POI" }
                PoiStore.add(this, name, lat, lon); pois = PoiStore.load(this); map.pois = pois; rebuildIndex()
            }.setNegativeButton(R.string.close, null).show()
    }

    // ---------------- search (unified)
    private fun showSearch(onPick: (SearchResult) -> Unit) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val input = EditText(this).apply {
            hint = getString(R.string.search_hint); setSingleLine(); setTextColor(white); setHintTextColor(grey)
        }
        val list = ListView(this); box.addView(input); box.addView(list)
        val adapter = object : ArrayAdapter<SearchResult>(this, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList()) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent); val r = getItem(pos)!!
                v.findViewById<TextView>(android.R.id.text1).apply { text = r.label; setTextColor(white) }
                v.findViewById<TextView>(android.R.id.text2).apply { text = r.sublabel; setTextColor(grey) }
                return v
            }
        }
        list.adapter = adapter
        val dlg = AlertDialog.Builder(this).setTitle(R.string.search_title).setView(box)
            .setNegativeButton(R.string.close, null).create()
        val handler = android.os.Handler(mainLooper)
        var pending: Runnable? = null
        fun refresh() {
            val r = searchIndex?.search(input.text.toString()) ?: emptyList()
            adapter.clear(); adapter.addAll(r); adapter.notifyDataSetChanged()
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val run = Runnable { refresh() }; pending = run; handler.postDelayed(run, 140)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, p, _ -> adapter.getItem(p)?.let { onPick(it) }; dlg.dismiss() }
        dlg.show()
    }

    private fun handleMapPick(r: SearchResult) {
        map.centerOn(BusStop(r.stopNumber?.toString() ?: "x", r.label, r.lat, r.lon, null))
        if (r.kind == SearchResult.Kind.STOP && r.stopNumber != null)
            allStops.firstOrNull { it.number == r.stopNumber }?.let { showStop(it) }
    }

    // ---------------- burger menu
    private fun showMenu() {
        val items = arrayOf(getString(R.string.menu_map), getString(R.string.menu_planner),
            getString(R.string.menu_reload), getString(R.string.privacy), getString(R.string.menu_about))
        AlertDialog.Builder(this).setItems(items) { _, w ->
            when (w) {
                0 -> { map.routes = emptyList(); map.activeRouteId = null; map.invalidate() }
                1 -> showPlanner()
                2 -> reloadMenu()
                3 -> showConsent(false)
                4 -> showAbout()
            }
        }.show()
    }

    private fun reloadMenu() {
        AlertDialog.Builder(this).setTitle(R.string.menu_reload).setItems(
            arrayOf(getString(R.string.db_reopen), getString(R.string.db_import_new))
        ) { _, w ->
            if (w == 0) Thread { if (DbManager.reload(this)) loadData() }.start() else pickDatabase()
        }.show()
    }

    private fun showAbout() {
        val extra = DbManager.db?.attribution()?.let { "\n\nDataset attribution:\n$it" } ?: ""
        AlertDialog.Builder(this).setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_text) + extra)
            .setPositiveButton(R.string.close, null).show()
    }

    // ---------------- planner
    private fun showPlanner() {
        if (DbManager.db == null) { toast(getString(R.string.db_none)); return }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val fromBtn = menuButton(); val toBtn = menuButton(); val timeBtn = menuButton(); val searchBtn = Button(this)
        searchBtn.text = getString(R.string.planner_search)
        val list = ListView(this)
        box.addView(fromBtn); box.addView(toBtn); box.addView(timeBtn); box.addView(searchBtn); box.addView(list)

        fun refreshLabels() {
            fromBtn.text = getString(R.string.planner_from, from?.name ?: getString(R.string.planner_choose_from))
            toBtn.text = getString(R.string.planner_to, to?.name ?: getString(R.string.planner_choose_to))
            timeBtn.text = getString(R.string.planner_time, departWhen?.format(fmt) ?: getString(R.string.planner_now))
        }
        refreshLabels()
        fromBtn.setOnClickListener { showSearch { from = it.asPlace(); refreshLabels() } }
        toBtn.setOnClickListener { showSearch { to = it.asPlace(); refreshLabels() } }
        timeBtn.setOnClickListener { pickDateTime { departWhen = it; refreshLabels() } }

        val adapter = object : ArrayAdapter<Itinerary>(this, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList()) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent); val it = getItem(pos)!!
                v.findViewById<TextView>(android.R.id.text1).apply {
                    text = "${hm(it.departMinutes)} → ${hm(it.arriveMinutes)}  (${it.arriveMinutes - it.departMinutes} min)"
                    setTextColor(white)
                }
                v.findViewById<TextView>(android.R.id.text2).apply { text = summary(it); setTextColor(grey) }
                return v
            }
        }
        list.adapter = adapter
        val dlg = AlertDialog.Builder(this).setTitle(R.string.planner_title).setView(box)
            .setNegativeButton(R.string.close, null).create()
        searchBtn.setOnClickListener {
            val f = from; val t = to
            if (f == null || t == null) { toast(getString(R.string.planner_need)); return@setOnClickListener }
            val db = DbManager.db ?: return@setOnClickListener
            val depart = departWhen ?: LocalDateTime.now(Malta.ZONE)
            searchBtn.isEnabled = false
            Thread {
                val res = runCatching { Planner.plan(db, allStops, f, t, depart) }.getOrDefault(emptyList())
                runOnUiThread {
                    searchBtn.isEnabled = true
                    adapter.clear(); adapter.addAll(res); adapter.notifyDataSetChanged()
                    if (res.isEmpty()) toast(getString(R.string.planner_none))
                }
            }.start()
        }
        list.setOnItemClickListener { _, _, p, _ ->
            adapter.getItem(p)?.let { itin -> dlg.dismiss(); confirmNavigate(itin) }
        }
        dlg.show()
    }

    private fun confirmNavigate(itin: Itinerary) {
        AlertDialog.Builder(this).setTitle(R.string.navigate).setMessage(summary(itin))
            .setPositiveButton(R.string.navigate) { _, _ -> startNavigation(itin) }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun summary(it: Itinerary): String = it.legs.joinToString("  ›  ") { leg ->
        when (leg) {
            is WalkLeg -> "walk ${leg.meters}m"
            is BusLeg -> "bus ${leg.routeCode}"
            else -> ""
        }
    }

    private fun hm(m: Int) = Malta.hhmm(m)

    private fun pickDateTime(onPicked: (LocalDateTime) -> Unit) {
        val now = LocalDateTime.now(Malta.ZONE)
        DatePickerDialog(this, { _, y, mo, d ->
            TimePickerDialog(this, { _, h, mi ->
                onPicked(LocalDateTime.of(y, mo + 1, d, h, mi))
            }, now.hour, now.minute, true).show()
        }, now.year, now.monthValue - 1, now.dayOfMonth).show()
    }

    private fun menuButton() = Button(this).apply {
        setBackgroundColor(Color.TRANSPARENT); setTextColor(white)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START; isAllCaps = false
    }

    // ---------------- navigation
    private fun startNavigation(itin: Itinerary) {
        if (!ensureLocation()) { toast("Location permission needed"); return }
        NavHub.itinerary = itin
        if (NavHub.stops.isEmpty()) NavHub.stops = allStops.associateBy { it.number }
        startForegroundService(Intent(this, NavigationService::class.java))
        render()
    }

    private fun cancelNav() {
        stopService(Intent(this, NavigationService::class.java)); NavHub.clear(); render()
    }

    // ---------------- consent (GDPR) — Wi-Fi crowd-GPS is disabled for now
    private fun showConsent(firstRun: Boolean) {
        val b = AlertDialog.Builder(this).setTitle(R.string.consent_title).setMessage(R.string.consent_text)
            .setCancelable(!firstRun)
        if (hasConsent) {
            b.setPositiveButton(R.string.close, null)
            b.setNegativeButton(R.string.consent_withdraw) { _, _ -> prefs.edit().remove("consent_at").apply() }
        } else {
            b.setPositiveButton(R.string.consent_accept) { _, _ -> prefs.edit().putLong("consent_at", System.currentTimeMillis()).apply() }
            b.setNegativeButton(R.string.consent_decline, null)
        }
        b.show()
    }

    // ---------------- permissions / location
    private fun ensureLocation(): Boolean {
        val ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) requestLocationPerms()
        return ok
    }

    private fun requestLocationPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        val missing = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res); startOwnLocation()
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

    override fun onLocationChanged(l: Location) =
        map.setMyLocation(l.latitude, l.longitude, l.accuracy, if (l.hasBearing() && l.speed > 2f) l.bearing else null)

    @Deprecated("needed for API < 29") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    // ---------------- labels for search
    private fun loadLabels(): Triple<List<MapView.PlaceLabel>, List<MapView.StreetLabel>, List<MapView.PlaceLabel>> = try {
        val root = org.json.JSONObject(assets.open("labels.json").bufferedReader().use { it.readText() })
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
    } catch (e: Exception) { Triple(emptyList(), emptyList(), emptyList()) }

    // ---------------- bottom panel
    private fun render() {
        val n = NavHub.snapshot
        if (n.active || n.arrived) {
            status.text = if (n.arrived) "" else "${n.distanceM} m"
            nextStop.text = n.instruction; nextStop.visibility = View.VISIBLE
            destination.text = getString(R.string.nav_cancel); destination.visibility = View.VISIBLE
            alert.text = if (n.pressStop) getString(R.string.press_stop) else n.detail
            alert.visibility = if (n.pressStop || n.detail.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            status.text = if (DbManager.db == null) getString(R.string.db_none) else ""
            nextStop.text = "Tap a stop for its lines · long-press to set start/destination"
            nextStop.visibility = View.VISIBLE
            destination.visibility = View.GONE
            alert.visibility = View.GONE
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object { private const val REQ_DB = 1001 }
}
