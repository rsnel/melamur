package it.snel.melamur

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import it.snel.melamur.data.*
import it.snel.melamur.map.Geo
import it.snel.melamur.map.MapView
import it.snel.melamur.map.Polyline
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
    private lateinit var searchBar: EditText
    private lateinit var searchResults: ListView
    private lateinit var searchAdapter: ArrayAdapter<SearchResult>
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var navInputs: View
    private enum class SearchTarget { MAP, START, END }
    private var searchTarget = SearchTarget.MAP
    private var suppressSearch = false

    // planner selections
    private var from: Place? = null
    private var to: Place? = null
    private var departWhen: LocalDateTime? = null
    private var lastItinerary: Itinerary? = null

    private val white = Color.WHITE
    private val grey = Color.rgb(176, 176, 176)

    // modes (role) × views, orthogonal
    enum class Mode { EXPLORE, NAVIGATE }
    enum class ViewKind { MAP, TEXT }
    private var mode = Mode.EXPLORE
    private var viewKind = ViewKind.MAP
    private var selectedLine: String? = null         // Explore: the line shown
    private var selectedDir = 0
    private var currentItinerary: Itinerary? = null  // Navigate: the planned route
    private var lineTextTime: LocalDateTime = LocalDateTime.now(Malta.ZONE)
    private lateinit var panel: View
    private lateinit var textPanel: android.widget.ScrollView
    private lateinit var textContent: LinearLayout
    private lateinit var centerBtn: Button
    private lateinit var tabExplore: Button
    private lateinit var tabNavigate: Button
    private lateinit var tabMap: ImageButton
    private lateinit var tabText: ImageButton
    private lateinit var tabMisc: ImageButton
    private lateinit var timeBtn: Button
    private lateinit var planBtn: Button
    private lateinit var navBusy: View
    private lateinit var navStatus: TextView
    private lateinit var navCancel: Button
    private var searching = false
    private var showLegStops = false
    private val tabOn = Color.rgb(238, 0, 0)
    private val tabOff = Color.rgb(60, 60, 60)
    private val iconOff = Color.rgb(150, 150, 150)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        status = findViewById(R.id.status); nextStop = findViewById(R.id.nextStop)
        destination = findViewById(R.id.destination); alert = findViewById(R.id.alert)
        panel = findViewById(R.id.panel)
        lm = getSystemService(LocationManager::class.java)

        centerBtn = findViewById(R.id.center)
        centerBtn.setOnClickListener {
            if (!map.centerOnMe()) Toast.makeText(this, "Waiting for your location…", Toast.LENGTH_SHORT).show()
        }
        searchBar = findViewById(R.id.searchBar); searchResults = findViewById(R.id.searchResults)
        startInput = findViewById(R.id.startInput); endInput = findViewById(R.id.endInput)
        navInputs = findViewById(R.id.navInputs)
        setupSearchInputs()
        tabExplore = findViewById(R.id.tabExplore); tabNavigate = findViewById(R.id.tabNavigate)
        tabMap = findViewById(R.id.tabMap); tabText = findViewById(R.id.tabText); tabMisc = findViewById(R.id.tabMisc)
        textPanel = findViewById(R.id.textPanel); textContent = findViewById(R.id.textContent)
        timeBtn = findViewById(R.id.timeBtn); planBtn = findViewById(R.id.planBtn)
        navBusy = findViewById(R.id.navBusy); navStatus = findViewById(R.id.navStatus)
        navCancel = findViewById(R.id.navCancel)
        navCancel.setOnClickListener { cancelNav() }
        tabExplore.setOnClickListener { setMode(Mode.EXPLORE) }
        tabNavigate.setOnClickListener { setMode(Mode.NAVIGATE) }
        tabMap.setOnClickListener { setView(ViewKind.MAP) }
        tabText.setOnClickListener { setView(ViewKind.TEXT) }
        tabMisc.setOnClickListener { showMisc() }
        tabMisc.imageTintList = ColorStateList.valueOf(iconOff)
        findViewById<Button>(R.id.swapEnds).setOnClickListener { swapEndpoints() }
        timeBtn.setOnClickListener { pickDateTime { departWhen = it; refreshTimeLabel() } }
        planBtn.setOnClickListener { runPlan() }
        refreshTimeLabel()
        map.onStopTapped = { bs -> stopFor(bs)?.let { showStop(it) } }
        map.onMapLongPress = { lat, lon, poi -> showLongPressMenu(lat, lon, poi) }
        destination.setOnClickListener { if (NavHub.snapshot.active || NavHub.snapshot.arrived) cancelNav() }

        pois = PoiStore.load(this); map.pois = pois; rebuildIndex()
        Settings.startup(this)?.let { map.setView(it[0], it[1], it[2].toFloat()) }

        // Use the assets the splash warmed up: if ready, show the map instantly;
        // otherwise finish loading in the background (Preloader de-dupes the work).
        Preloader.start(applicationContext)
        val warm = Preloader.mapData
        if (warm != null) {
            map.data = warm
            Preloader.labels?.let { places = it.first; streets = it.second; landmarks = it.third }
            rebuildIndex()
        } else {
            Thread {
                val data = Preloader.getMapData(this)
                val lbl = Preloader.getLabels(this)
                runOnUiThread { map.data = data; places = lbl.first; streets = lbl.second; landmarks = lbl.third; rebuildIndex() }
            }.start()
        }

        if (DbManager.isImported(this)) {
            Thread { if (DbManager.db == null) DbManager.openExisting(this); loadData() }.start()
        } else {
            promptImport()
        }
        requestLocationPerms()
        refreshView()
    }

    // ---------------- modes (Explore/Navigate) × views (Map/Text)
    private fun setMode(m: Mode) { mode = m; hideSearch(); updateMapForMode(); refreshView() }
    private fun setView(v: ViewKind) { viewKind = v; hideSearch(); refreshView() }

    private fun refreshTabs() {
        tabExplore.backgroundTintList = ColorStateList.valueOf(if (mode == Mode.EXPLORE) tabOn else tabOff)
        tabNavigate.backgroundTintList = ColorStateList.valueOf(if (mode == Mode.NAVIGATE) tabOn else tabOff)
        tabMap.imageTintList = ColorStateList.valueOf(if (viewKind == ViewKind.MAP) tabOn else iconOff)
        tabText.imageTintList = ColorStateList.valueOf(if (viewKind == ViewKind.TEXT) tabOn else iconOff)
        // center-me lives just above the bottom bar; only useful on the map
        centerBtn.visibility = if (viewKind == ViewKind.MAP) View.VISIBLE else View.GONE
        refreshNavBar()
    }

    /**
     * Top bar for the current mode: Explore shows the search bar; Navigate shows
     * the planner inputs, the busy panel while planning, or a single cancel
     * button while a route is being navigated.
     */
    private fun refreshNavBar() {
        if (!::navInputs.isInitialized) return
        val nav = mode == Mode.NAVIGATE
        val active = NavHub.snapshot.active || NavHub.snapshot.arrived
        searchBar.visibility = if (nav) View.GONE else View.VISIBLE
        navCancel.visibility = if (nav && active) View.VISIBLE else View.GONE
        navBusy.visibility = if (nav && !active && searching) View.VISIBLE else View.GONE
        navInputs.visibility = if (nav && !active && !searching) View.VISIBLE else View.GONE
        if (navInputs.visibility == View.VISIBLE) syncNavInputs()
    }

    private fun refreshTimeLabel() {
        if (::timeBtn.isInitialized)
            timeBtn.text = getString(R.string.planner_time, departWhen?.format(fmt) ?: getString(R.string.planner_now))
    }

    private fun swapEndpoints() { val f = from; setFrom(to); setTo(f) }

    /** Sort key so line codes order numerically (1, 2, 13); non-numeric codes come last. */
    private fun codeKey(code: String): Int = code.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

    /** Reflect the current from/to selection in the navigate inputs without re-triggering search. */
    private fun syncNavInputs() {
        if (!::startInput.isInitialized) return
        suppressSearch = true
        startInput.setText(from?.name ?: "")
        endInput.setText(to?.name ?: "")
        suppressSearch = false
    }

    private fun setFrom(p: Place?) {
        from = p
        if (::startInput.isInitialized) { suppressSearch = true; startInput.setText(p?.name ?: ""); suppressSearch = false }
        updateEndpoints()
    }

    private fun setTo(p: Place?) {
        to = p
        if (::endInput.isInitialized) { suppressSearch = true; endInput.setText(p?.name ?: ""); suppressSearch = false }
        updateEndpoints()
    }

    /** Reflect the selected start/destination on the map as start/finish markers (both modes). */
    private fun updateEndpoints() {
        if (!::map.isInitialized) return
        map.setEndpoints(from?.let { doubleArrayOf(it.lat, it.lon) }, to?.let { doubleArrayOf(it.lat, it.lon) })
    }

    private fun refreshView() {
        if (viewKind == ViewKind.TEXT) { textPanel.visibility = View.VISIBLE; buildTextPanel() }
        else textPanel.visibility = View.GONE
        refreshTabs(); render()
    }

    private fun clearRoutes() {
        map.routes = emptyList(); map.activeRouteId = null; map.itinPaths = emptyList(); map.invalidate()
    }

    private fun updateMapForMode() {
        when (mode) {
            Mode.EXPLORE -> selectedLine?.let { showRouteOnMap(it) } ?: clearRoutes()
            Mode.NAVIGATE -> currentItinerary?.let { drawItinerary(it) } ?: clearRoutes()
        }
    }

    private fun selectLine(code: String, dir: Int = 0) {
        selectedLine = code; selectedDir = dir; mode = Mode.EXPLORE
        lineTextTime = LocalDateTime.now(Malta.ZONE)
        updateMapForMode(); refreshView()
    }

    private fun drawItinerary(itin: Itinerary) {
        val db = DbManager.db ?: return
        Thread {
            val v = db.versionFor(itin.serviceDate)
            val byNum = allStops.associateBy { it.number }
            val shapeCache = HashMap<String, Polyline?>()
            fun shapeFor(code: String, dir: Int): Polyline? = shapeCache.getOrPut("$code/$dir") {
                db.directionsForRoute(code, v).firstOrNull { it.directionIndex == dir }?.shape
            }
            val paths = ArrayList<MapView.ItinPath>()
            for (leg in itin.legs) when (leg) {
                is WalkLeg -> {
                    val p = Path()
                    p.moveTo(Geo.x(leg.fromLon), Geo.y(leg.fromLat)); p.lineTo(Geo.x(leg.toLon), Geo.y(leg.toLat))
                    paths.add(MapView.ItinPath(p, 0, true))
                }
                is BusLeg -> {
                    val p = Path()
                    val shape = shapeFor(leg.routeCode, leg.directionIndex)
                    val b = byNum[leg.boardNumber]; val a = byNum[leg.alightNumber]
                    if (shape != null && b != null && a != null) {
                        val b0 = shape.project(b.x, b.y)?.along
                        val a0 = if (b0 != null) shape.project(a.x, a.y, b0)?.along else null
                        if (b0 != null && a0 != null && a0 > b0) {
                            var along = b0; var first = true
                            while (along < a0) {
                                val q = shape.at(along)
                                if (first) { p.moveTo(q[0], q[1]); first = false } else p.lineTo(q[0], q[1])
                                along += 25f
                            }
                            val end = shape.at(a0); p.lineTo(end[0], end[1])
                        }
                    }
                    if (p.isEmpty) { b?.let { p.moveTo(it.x, it.y) }; a?.let { p.lineTo(it.x, it.y) } }
                    paths.add(MapView.ItinPath(p, leg.color, false))
                }
                else -> {}
            }
            runOnUiThread { map.routes = emptyList(); map.activeRouteId = null; map.itinPaths = paths; map.invalidate() }
        }.start()
    }

    // ---------------- text view
    private fun tv(s: String, color: Int = white, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(color); textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        val p = (4 * resources.displayMetrics.density).toInt(); setPadding(0, p, 0, p)
    }
    private fun smallBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(white); backgroundTintList = ColorStateList.valueOf(tabOff)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = (6 * resources.displayMetrics.density).toInt() }
    }
    private fun clickRow(colors: List<Int>, line1: String, line2: String, onClick: () -> Unit): View =
        colorRow(colors, line1, line2).apply { setOnClickListener { onClick() } }

    private fun buildTextPanel() {
        textContent.removeAllViews()
        if (DbManager.db == null) { textContent.addView(tv(getString(R.string.db_none))); return }
        when (mode) { Mode.EXPLORE -> buildExploreText(); Mode.NAVIGATE -> buildNavigateText() }
    }

    private fun buildExploreText() {
        val db = DbManager.db ?: return
        val code = selectedLine
        if (code == null) {
            textContent.addView(tv(getString(R.string.text_select_line), white, 17f, true))
            Thread {
                val v = db.versionFor(LocalDate.now(Malta.ZONE))
                val dirs = db.allLineDirections(v)
                val byNum = allStops.associateBy { it.number }
                val sorted = dirs.sortedWith(compareBy({ codeKey(it.code) }, { it.code }, { it.directionIndex }))
                runOnUiThread {
                    for (d in sorted) {
                        val start = byNum[d.firstStop]?.display ?: "?"
                        val end = byNum[d.lastStop]?.display ?: d.name.ifBlank { "?" }
                        textContent.addView(clickRow(listOf(d.color), d.code, "$start  →  $end") {
                            selectLine(d.code, d.directionIndex)
                        })
                    }
                }
            }.start()
            return
        }
        val c1 = LinearLayout(this)
        c1.addView(smallBtn(getString(R.string.text_earlier)) { rollLine(-1) })
        c1.addView(smallBtn(getString(R.string.text_later)) { rollLine(1) })
        c1.addView(smallBtn(getString(R.string.text_flip)) { selectedDir = 1 - selectedDir; updateMapForMode(); buildTextPanel() })
        textContent.addView(c1)
        val c2 = LinearLayout(this)
        c2.addView(smallBtn(getString(R.string.text_all_lines)) { selectedLine = null; clearRoutes(); buildTextPanel() })
        c2.addView(smallBtn(getString(R.string.text_show_map)) { setView(ViewKind.MAP) })
        textContent.addView(c2)
        Thread {
            val v = db.versionFor(lineTextTime.toLocalDate())
            val dirs = db.directionsForRoute(code, v)
            val dir = dirs.getOrNull(selectedDir) ?: dirs.firstOrNull()
            val firstStop = dir?.stopNumbers?.firstOrNull()?.toIntOrNull()
            val nowMin = lineTextTime.hour * 60 + lineTextTime.minute
            val deps = if (firstStop != null) db.departures(firstStop, lineTextTime.toLocalDate(), v)
                .filter { it.routeCode == code && it.directionIndex == (dir?.directionIndex ?: 0) }.sortedBy { it.minutes }
            else emptyList()
            val trip = deps.firstOrNull { it.minutes >= nowMin } ?: deps.lastOrNull()
            val stops = trip?.let { db.tripStops(it.tripId) } ?: emptyList()
            val col = dir?.color ?: db.routeColor(code, v)
            val byNum = allStops.associateBy { it.number }
            val tripIdx = deps.indexOfFirst { it.tripId == trip?.tripId }
            val nextTrip = if (tripIdx >= 0) deps.getOrNull(tripIdx + 1) else null
            val gap = if (trip != null && nextTrip != null) nextTrip.minutes - trip.minutes else null
            runOnUiThread {
                textContent.addView(tv("$code  →  ${dir?.name ?: ""}", col, 18f, true))
                val headway = gap?.let { "   ·   next bus in $it min" } ?: if (trip != null) "   ·   last trip" else ""
                textContent.addView(tv("Trip departing ${trip?.let { Malta.hhmm(it.minutes) } ?: "—"}$headway", grey, 13f))
                for (s in stops) {
                    val nm = s.number?.let { byNum[it]?.display } ?: s.name
                    textContent.addView(tv("${Malta.hhmm(s.minutes)}    $nm", white, 15f))
                }
                if (stops.isEmpty()) textContent.addView(tv("No trips found for this time.", grey))
            }
        }.start()
    }

    private fun rollLine(step: Int) {
        val db = DbManager.db ?: return; val code = selectedLine ?: return
        Thread {
            val v = db.versionFor(lineTextTime.toLocalDate())
            val d = db.directionsForRoute(code, v).getOrNull(selectedDir) ?: return@Thread
            val firstStop = d.stopNumbers.firstOrNull()?.toIntOrNull() ?: return@Thread
            val deps = db.departures(firstStop, lineTextTime.toLocalDate(), v)
                .filter { it.routeCode == code && it.directionIndex == d.directionIndex }.sortedBy { it.minutes }
            if (deps.isEmpty()) return@Thread
            val nowMin = lineTextTime.hour * 60 + lineTextTime.minute
            var idx = deps.indexOfFirst { it.minutes >= nowMin }; if (idx < 0) idx = deps.size - 1
            val target = deps.getOrNull((idx + step).coerceIn(0, deps.size - 1)) ?: return@Thread
            lineTextTime = Malta.at(lineTextTime.toLocalDate(), target.minutes)
            runOnUiThread { buildTextPanel() }
        }.start()
    }

    private fun buildNavigateText() {
        val itin = currentItinerary
        if (itin == null) {
            textContent.addView(tv(getString(R.string.text_no_route), grey))
            textContent.addView(tv("Set from and to above, then tap Plan.", grey, 13f))
            return
        }
        textContent.addView(tv(getString(R.string.text_route), white, 17f, true))
        textContent.addView(tv("${Malta.hhmm(itin.departMinutes)} → ${Malta.hhmm(itin.arriveMinutes)}  (${itin.arriveMinutes - itin.departMinutes} min)", grey, 14f))
        textContent.addView(smallBtn(if (showLegStops) "Hide stops" else "Show stops") {
            showLegStops = !showLegStops; buildTextPanel()
        })
        val db = DbManager.db
        if (!showLegStops || db == null) {
            addLegRows(itin, emptyMap())
            textContent.addView(smallBtn(getString(R.string.navigate)) { confirmNavigate(itin) })
        } else {
            // load the intermediate stops of each bus leg off the UI thread, then render in order
            Thread {
                val mids = HashMap<Long, List<TripStop>>()
                for (bl in itin.busLegs) mids[bl.tripId] = intermediateStops(db, bl)
                runOnUiThread {
                    addLegRows(itin, mids)
                    textContent.addView(smallBtn(getString(R.string.navigate)) { confirmNavigate(itin) })
                }
            }.start()
        }
    }

    private fun addLegRows(itin: Itinerary, mids: Map<Long, List<TripStop>>) {
        val byNum = allStops.associateBy { it.number }
        for (leg in itin.legs) when (leg) {
            is WalkLeg -> textContent.addView(tv("Walk ${leg.meters} m to ${leg.toName}", white, 15f))
            is BusLeg -> {
                textContent.addView(clickRow(listOf(leg.color),
                    "${Malta.hhmm(leg.boardMinutes)}  ${leg.routeCode} → ${leg.towards}",
                    "${leg.fromName}  →  ${leg.toName}  (${Malta.hhmm(leg.alightMinutes)})") {})
                mids[leg.tripId]?.forEach { s ->
                    val nm = s.number?.let { byNum[it]?.display } ?: s.name
                    textContent.addView(tv("      ${Malta.hhmm(s.minutes)}   $nm", grey, 13f))
                }
            }
            else -> {}
        }
    }

    /** Stops strictly between board and alight on a bus leg's trip. */
    private fun intermediateStops(db: MptDb, bl: BusLeg): List<TripStop> {
        val ts = db.tripStops(bl.tripId)
        val b = ts.indexOfFirst { it.number == bl.boardNumber && it.minutes == bl.boardMinutes }
        val a = ts.indexOfLast { it.number == bl.alightNumber && it.minutes == bl.alightMinutes }
        return if (b in 0 until a) ts.subList(b + 1, a).toList() else emptyList()
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
            val colorOf = db.linesForStop(stop.number, v).associate { it.code to it.color }
            // first upcoming departure per line+direction (board is sorted by real time)
            val firstPer = LinkedHashMap<String, Departure>()
            for (d in db.departuresFrom(stop.number, now, 120)) {
                val k = "${d.routeCode}/${d.directionIndex}"; if (!firstPer.containsKey(k)) firstPer[k] = d
            }
            val rows = firstPer.values.sortedWith(
                compareBy({ codeKey(it.routeCode) }, { it.routeCode }, { it.directionIndex }))
            runOnUiThread {
                setView(ViewKind.MAP)   // always show the stop over the map
                val pad = (12 * resources.displayMetrics.density).toInt()
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
                var dlg: AlertDialog? = null
                if (rows.isEmpty()) box.addView(tv("No departures from this stop today.", grey))
                for (d in rows) {
                    val mins = java.time.Duration.between(now, d.time).toMinutes()
                    val cd = if (mins <= 0) "now" else "in $mins min"
                    box.addView(clickRow(listOf(colorOf[d.routeCode] ?: white),
                        "${d.routeCode}    →  ${d.towards}", "${Malta.hhmm(d.minutes)}    ·    $cd") {
                        selectLine(d.routeCode, d.directionIndex); dlg?.dismiss()
                    })
                }
                val place = Place(stop.display, stop.lat, stop.lon)
                dlg = AlertDialog.Builder(this)
                    .setTitle(stop.display + "  ·  #${stop.number}")
                    .setView(android.widget.ScrollView(this).apply { addView(box) })
                    .setPositiveButton(R.string.nav_from) { _, _ -> navFromMap(place, true) }
                    .setNeutralButton(R.string.nav_to) { _, _ -> navFromMap(place, false) }
                    .setNegativeButton(R.string.close, null).create()
                dlg.show()
                // navigate-from / navigate-to greyed out for now
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
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
                RouteDirection(it.directionIndex.toString(), it.name, it.shape, it.stopNumbers, it.color)
            })
            val gfx = RouteRenderer.build(route, busStopsById)
            runOnUiThread { map.itinPaths = emptyList(); map.routes = listOf(gfx); map.activeRouteId = code; map.invalidate() }
        }.start()
    }

    // ---------------- long-press POI menu
    private fun showLongPressMenu(lat: Double, lon: Double, poi: Poi?) {
        // Navigate: no menu — the point fills a from/to slot directly.
        if (mode == Mode.NAVIGATE) { placeFromMap(lat, lon, poi); return }
        // Explore: manage saved places, plus navigate-from / navigate-to.
        val place = Place(poi?.name ?: "Dropped pin", poi?.lat ?: lat, poi?.lon ?: lon)
        if (poi == null) {
            val items = arrayOf(getString(R.string.poi_store), getString(R.string.nav_from), getString(R.string.nav_to))
            AlertDialog.Builder(this).setItems(items) { _, w ->
                when (w) { 0 -> storePoi(lat, lon); 1 -> navFromMap(place, true); 2 -> navFromMap(place, false) }
            }.show()
        } else {
            val items = arrayOf(getString(R.string.nav_from), getString(R.string.nav_to), getString(R.string.poi_delete))
            AlertDialog.Builder(this).setTitle(poi.name).setItems(items) { _, w ->
                when (w) {
                    0 -> navFromMap(place, true)
                    1 -> navFromMap(place, false)
                    2 -> AlertDialog.Builder(this).setMessage(R.string.poi_delete_confirm)
                        .setPositiveButton(R.string.poi_delete) { _, _ ->
                            PoiStore.remove(this, poi.id); pois = PoiStore.load(this); map.pois = pois; rebuildIndex()
                        }.setNegativeButton(R.string.close, null).show()
                }
            }.show()
        }
    }

    /** Explore context menus: set from/to from a map point, mark it, and switch to Navigate. */
    private fun navFromMap(place: Place, asFrom: Boolean) {
        if (asFrom) setFrom(place) else setTo(place)
        toast((if (asFrom) "From: " else "To: ") + place.name)
        setMode(Mode.NAVIGATE)
    }

    /** Navigate: a long-pressed point goes to the focused field, else the first empty slot. */
    private fun placeFromMap(lat: Double, lon: Double, poi: Poi?) {
        val place = Place(poi?.name ?: "Dropped pin", poi?.lat ?: lat, poi?.lon ?: lon)
        when {
            searchTarget == SearchTarget.START -> { setFrom(place); toast("From: ${place.name}") }
            searchTarget == SearchTarget.END -> { setTo(place); toast("To: ${place.name}") }
            from == null -> { setFrom(place); toast("From: ${place.name}") }
            to == null -> { setTo(place); toast("To: ${place.name}") }
            else -> { setFrom(place); toast("From: ${place.name}") }
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

    private fun handleMapPick(r: SearchResult) {
        map.centerOn(BusStop(r.stopNumber?.toString() ?: "x", r.label, r.lat, r.lon, null))
        if (r.kind == SearchResult.Kind.STOP && r.stopNumber != null)
            allStops.firstOrNull { it.number == r.stopNumber }?.let { showStop(it) }
    }

    // ---------------- burger menu
    private fun showMisc() {
        val items = arrayListOf(getString(R.string.menu_settings), getString(R.string.menu_reload))
        if (Settings.debug(this)) items.add(getString(R.string.menu_debug))
        items.add(getString(R.string.privacy)); items.add(getString(R.string.menu_about))
        AlertDialog.Builder(this).setItems(items.toTypedArray()) { _, w ->
            when (items[w]) {
                getString(R.string.menu_settings) -> openSettings()
                getString(R.string.menu_reload) -> reloadMenu()
                getString(R.string.menu_debug) -> lastItinerary?.let { startNavigation(it, simulate = true) } ?: toast(getString(R.string.sim_need))
                getString(R.string.privacy) -> showConsent(false)
                getString(R.string.menu_about) -> showAbout()
            }
        }.show()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            putExtra("lat", map.viewLat()); putExtra("lon", map.viewLon()); putExtra("scale", map.viewScale())
        })
    }

    // ---------------- top search inputs (Explore: one bar; Navigate: start + destination)
    private fun setupSearchInputs() {
        searchAdapter = searchResultAdapter()
        searchResults.adapter = searchAdapter
        attachSearch(searchBar, SearchTarget.MAP)
        attachSearch(startInput, SearchTarget.START)
        attachSearch(endInput, SearchTarget.END)
        searchResults.setOnItemClickListener { _, _, p, _ ->
            val r = searchAdapter.getItem(p)
            if (r != null) when (searchTarget) {
                SearchTarget.MAP -> { handleMapPick(r); searchBar.setText("") }
                SearchTarget.START -> setFrom(r.asPlace())
                SearchTarget.END -> setTo(r.asPlace())
            }
            hideSearch(); hideKeyboard()
        }
    }

    /** Wire a debounced search on [input]; results are shared, [target] records which field is active. */
    private fun attachSearch(input: EditText, target: SearchTarget) {
        val handler = android.os.Handler(mainLooper); var pending: Runnable? = null
        input.setOnFocusChangeListener { _, has -> if (has) searchTarget = target }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (suppressSearch) return
                searchTarget = target
                pending?.let { handler.removeCallbacks(it) }
                val run = Runnable {
                    val q = input.text.toString()
                    val r = if (q.isBlank()) emptyList() else (searchIndex?.search(q) ?: emptyList())
                    searchAdapter.clear(); searchAdapter.addAll(r); searchAdapter.notifyDataSetChanged()
                    searchResults.visibility = if (r.isEmpty()) View.GONE else View.VISIBLE
                }
                pending = run; handler.postDelayed(run, 140)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun searchResultAdapter() = object : ArrayAdapter<SearchResult>(
        this, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList()
    ) {
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = super.getView(pos, cv, parent); val r = getItem(pos)!!
            v.findViewById<TextView>(android.R.id.text1).apply { text = r.label; setTextColor(white) }
            v.findViewById<TextView>(android.R.id.text2).apply { text = r.sublabel; setTextColor(grey) }
            return v
        }
    }

    private fun hideSearch() { searchResults.visibility = View.GONE }
    private fun hideKeyboard() {
        val tok = (currentFocus ?: searchBar).windowToken
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(tok, 0)
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

    // ---------------- planner (inline: start / destination / time / plan live in the top bar)
    private fun runPlan() {
        if (DbManager.db == null) { toast(getString(R.string.db_none)); return }
        val f = from; val t = to
        if (f == null || t == null) { toast(getString(R.string.planner_need)); return }
        val db = DbManager.db ?: return
        val depart = departWhen ?: LocalDateTime.now(Malta.ZONE)
        searching = true; navStatus.text = "Planning…"; refreshNavBar()
        val onProg: (String) -> Unit = { msg -> runOnUiThread { if (searching) navStatus.text = msg } }
        Thread {
            val res: List<Itinerary>; val stats: String
            if (Settings.debug(this)) {
                val cmp = runCatching { Planner.compare(db, allStops, f, t, depart, onProg) }.getOrDefault(emptyList())
                res = cmp.firstOrNull { it.engine == Engine.RAPTOR }?.itineraries ?: emptyList()
                stats = statsText(cmp)
            } else {
                val eng = Settings.engine(this)
                val t0 = System.nanoTime()
                res = runCatching { Planner.plan(db, allStops, f, t, depart, eng, onProg) }.getOrDefault(emptyList())
                stats = "%s   %.1f ms · %d options".format(eng.name, (System.nanoTime() - t0) / 1_000_000.0, res.size)
            }
            runOnUiThread { searching = false; refreshNavBar(); showItineraries(res, stats) }
        }.start()
    }

    private fun showItineraries(res: List<Itinerary>, stats: String) {
        if (res.isEmpty()) { toast(getString(R.string.planner_none)); return }
        val pad = (12 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        if (stats.isNotBlank()) box.addView(TextView(this).apply {
            text = stats; setTextColor(grey); textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE; setPadding(0, 0, 0, pad)
        })
        var dlg: AlertDialog? = null
        for (itin in res) {
            val cols = itin.busLegs.map { bl -> bl.color }.ifEmpty { listOf(grey) }
            box.addView(clickRow(cols,
                "${hm(itin.departMinutes)} → ${hm(itin.arriveMinutes)}  (${itin.arriveMinutes - itin.departMinutes} min)",
                summary(itin)) {
                dlg?.dismiss()
                currentItinerary = itin; mode = Mode.NAVIGATE; lastItinerary = itin
                updateMapForMode(); refreshView(); confirmNavigate(itin)
            })
        }
        dlg = AlertDialog.Builder(this).setTitle(R.string.planner_title)
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton(R.string.close, null).create()
        dlg.show()
    }

    private fun confirmNavigate(itin: Itinerary) {
        lastItinerary = itin
        val b = AlertDialog.Builder(this).setTitle(R.string.navigate).setMessage(summary(itin))
            .setPositiveButton(R.string.navigate) { _, _ -> startNavigation(itin, simulate = false) }
            .setNegativeButton(R.string.close, null)
        if (Settings.debug(this)) b.setNeutralButton(R.string.simulate) { _, _ -> startNavigation(itin, simulate = true) }
        b.show()
    }

    private fun statsText(cmp: List<EngineResult>): String {
        if (cmp.isEmpty()) return ""
        val lines = cmp.joinToString("\n") { r ->
            val best = r.bestArrive?.let { Malta.hhmm(it) } ?: "—"
            val name = when (r.engine) { Engine.RAPTOR -> "RAPTOR"; Engine.CSA -> "CSA"; Engine.MCRAPTOR -> "McRAPTOR" }
            "%-9s%6.1f ms · %d opt · →%s".format(name, r.millis, r.itineraries.size, best)
        }
        val arrivals = cmp.mapNotNull { it.bestArrive }.toSet()
        return lines + "\n" + if (arrivals.size <= 1) "✓ all agree on earliest arrival" else "✗ engines differ"
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

    // ---------------- navigation
    private fun startNavigation(itin: Itinerary, simulate: Boolean) {
        if (!simulate && !ensureLocation()) { toast("Location permission needed"); return }
        lastItinerary = itin
        NavHub.itinerary = itin
        if (NavHub.stops.isEmpty()) NavHub.stops = allStops.associateBy { it.number }
        NavHub.simulate = simulate
        NavHub.simPoints = if (simulate) buildSimPoints(itin) else emptyList()
        if (simulate) {
            toast(getString(R.string.sim_running))
            NavHub.simPoints.firstOrNull()?.let { map.setMyLocation(it[0], it[1], 5f, it[2].toFloat()); map.centerOnMe() }
        }
        startForegroundService(Intent(this, NavigationService::class.java))
        render()
    }

    private fun cancelNav() {
        stopService(Intent(this, NavigationService::class.java)); NavHub.clear(); render()
    }

    /** Debug: synthesize a GPS path along the itinerary (walk legs straight, bus
     *  legs along the route's road-snapped track between board and alight stops),
     *  interpolated to ~30 m spacing. */
    private fun buildSimPoints(itin: Itinerary): List<DoubleArray> {
        val db = DbManager.db
        val byNum = allStops.associateBy { it.number }
        val v = db?.versionFor(itin.serviceDate) ?: 1
        val shapeCache = HashMap<String, it.snel.melamur.data.DirectionShape?>()
        fun dirShape(code: String, dir: Int): it.snel.melamur.data.DirectionShape? =
            shapeCache.getOrPut("$code/$dir") { db?.directionsForRoute(code, v)?.firstOrNull { it.directionIndex == dir } }

        val wps = ArrayList<DoubleArray>()  // [lat, lon] waypoints
        for (leg in itin.legs) when (leg) {
            is WalkLeg -> { wps.add(doubleArrayOf(leg.fromLat, leg.fromLon)); wps.add(doubleArrayOf(leg.toLat, leg.toLon)) }
            is BusLeg -> {
                val shape = dirShape(leg.routeCode, leg.directionIndex)
                val b = byNum[leg.boardNumber]; val a = byNum[leg.alightNumber]
                val b0 = if (shape != null && b != null) shape.shape.project(b.x, b.y)?.along else null
                val a0 = if (shape != null && a != null && b0 != null) shape.shape.project(a.x, a.y, b0)?.along else null
                if (shape != null && b0 != null && a0 != null && a0 > b0) {
                    var along = b0                       // drive along the known track
                    while (along < a0) {
                        val p = shape.shape.at(along)
                        wps.add(doubleArrayOf(Geo.lat(p[1]).toDouble(), Geo.lon(p[0]).toDouble())); along += 30f
                    }
                    a?.let { wps.add(doubleArrayOf(it.lat, it.lon)) }
                } else {                                 // fallback: through the trip's stops
                    val ts = db?.tripStops(leg.tripId) ?: emptyList()
                    var inside = false
                    for (s in ts) {
                        if (s.number == leg.boardNumber && s.minutes == leg.boardMinutes) inside = true
                        if (inside) byNum[s.number]?.let { wps.add(doubleArrayOf(it.lat, it.lon)) }
                        if (s.number == leg.alightNumber && s.minutes == leg.alightMinutes) break
                    }
                }
            }
        }
        // interpolate between waypoints in projected metres, with bearings
        val pts = ArrayList<DoubleArray>()
        val sp = 30.0
        for (i in 0 until wps.size - 1) {
            val ax = Geo.x(wps[i][1]).toDouble(); val ay = Geo.y(wps[i][0]).toDouble()
            val bx = Geo.x(wps[i + 1][1]).toDouble(); val by = Geo.y(wps[i + 1][0]).toDouble()
            val d = Math.hypot(bx - ax, by - ay)
            if (d < 1.0) continue
            val brg = Geo.bearing((bx - ax).toFloat(), (by - ay).toFloat()).toDouble()
            val n = Math.max(1, (d / sp).toInt())
            for (k in 0 until n) {
                val t = k.toDouble() / n
                val x = ax + (bx - ax) * t; val y = ay + (by - ay) * t
                pts.add(doubleArrayOf(Geo.lat(y.toFloat()), Geo.lon(x.toFloat()), brg))
            }
        }
        wps.lastOrNull()?.let { pts.add(doubleArrayOf(it[0], it[1], pts.lastOrNull()?.get(2) ?: 0.0)) }
        return pts
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

    // ---------------- bottom panel
    private fun render() {
        val n = NavHub.snapshot
        if (n.simulated && n.lat != null && n.lon != null) map.setMyLocation(n.lat, n.lon, 5f, n.bearing)
        if (n.active || n.arrived) {
            panel.visibility = View.VISIBLE
            status.text = if (n.arrived) "" else "${n.distanceM} m"
            nextStop.text = n.instruction; nextStop.visibility = View.VISIBLE
            destination.text = getString(R.string.nav_cancel); destination.visibility = View.VISIBLE
            alert.text = if (n.pressStop) getString(R.string.press_stop) else n.detail
            alert.visibility = if (n.pressStop || n.detail.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            panel.visibility = View.GONE
        }
        if (::tabExplore.isInitialized) refreshTabs()
    }

    /** A list row with coloured line swatch(es) in a box before two text lines (white / grey). */
    private fun colorRow(colors: List<Int>, line1: String, line2: String): View {
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(px(12f), px(10f), px(12f), px(10f))
        }
        for (c in colors.take(4)) row.addView(View(this).apply {
            setBackgroundColor(c)
            layoutParams = LinearLayout.LayoutParams(px(16f), px(16f)).apply { marginEnd = px(5f) }
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(4f), 0, 0, 0)
            addView(TextView(this@MainActivity).apply { text = line1; setTextColor(white); textSize = 16f })
            addView(TextView(this@MainActivity).apply { text = line2; setTextColor(grey); textSize = 13f })
        })
        return row
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object { private const val REQ_DB = 1001 }
}
