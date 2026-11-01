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
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
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
import it.snel.melamur.map.Precalc
import it.snel.melamur.map.Geo
import it.snel.melamur.map.MapView
import it.snel.melamur.map.Polyline
import it.snel.melamur.map.RouteRenderer
import it.snel.melamur.nav.NavHub
import it.snel.melamur.nav.NavigationService
import it.snel.melamur.update.UpdateFlow
import it.snel.melamur.update.Updater
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
    // Explore has three sub-modes sharing one selection, shown by both the map and
    // the text view; changing it never switches the view or the mode.
    enum class Sub { POINT, LINE, STOP }
    private var sub = Sub.POINT
    private var pointLat: Double? = null             // POINT: chosen point (null = your position)
    private var pointLon: Double? = null
    private var selectedStop: Stop? = null           // STOP: the stop shown
    private var stopLineDirs: Map<String, Int> = emptyMap()  // STOP: direction to open per line
    private var stopLine: String? = null             // STOP: a line picked at the stop (first tap)
    private var stopLineDir = 0
    private var lineTrip: Long? = null               // LINE: show this run (picked from a stop's times)
    private var lineVariant: Long? = null            // LINE: only trips of this variant (null = all of the direction)
    private var shownLineStopDirs: Map<String, List<Int>> = emptyMap()
    @Volatile private var precalc: Precalc? = null   // per-database results (stop directions, groups, line drawings)  // line on the map: stop id -> its directions

    /** One Explore state, for the back button. */
    private data class Snap(
        val sub: Sub, val pointLat: Double?, val pointLon: Double?, val stop: Stop?,
        val stopLine: String?, val stopLineDir: Int, val line: String?, val dir: Int,
        val lineTrip: Long?, val lineTextTime: LocalDateTime, val lineVariant: Long? = null,
    )
    private val history = ArrayList<Snap>()
    private var lastBack = 0L
    private var selectedLine: String? = null         // LINE: the line shown (null = list of lines)
    private var selectedDir = 0
    private var pendingFit = false                   // LINE: zoom to the line once when chosen
    private var pointTextAt: DoubleArray? = null     // where the POINT text was last built
    private var currentItinerary: Itinerary? = null  // Navigate: the planned route
    private var lineTextTime: LocalDateTime = LocalDateTime.now(Malta.ZONE)
    private lateinit var panel: View
    private lateinit var textPanel: android.widget.ScrollView
    private lateinit var textContent: LinearLayout
    private lateinit var centerBtn: Button
    private lateinit var lineFitBtn: Button
    private var lineBounds: FloatArray? = null       // LINE: world extent of the line on the map
    // "Show whole line" is offered when the line appears on the map (picked, or back from
    // text) and withdrawn for good once the view roughly shows the whole line
    private var lineFitOffered = false
    private lateinit var zoomBox: View
    private lateinit var compassBtn: ImageButton
    private lateinit var tabExplore: Button
    private lateinit var tabNavigate: Button
    private lateinit var content: EdgeSwipeLayout
    private lateinit var edgeLeft: android.widget.ImageView
    private lateinit var edgeRight: android.widget.ImageView
    private lateinit var swipeHint: TextView
    private lateinit var tabMisc: ImageButton
    private lateinit var timeBtn: Button
    private lateinit var planBtn: Button
    private lateinit var navOptions: View
    private lateinit var navSelected: View
    private lateinit var navActive: View
    private lateinit var optInfo: TextView
    private lateinit var selInfo: TextView
    /** Navigate: entering from/to/time, browsing planned options, or one option selected. */
    private enum class NavState { INPUT, OPTIONS, SELECTED }
    private var navState = NavState.INPUT
    private val planOptions = ArrayList<Itinerary>()
    private var optIdx = 0
    private var planGen = 0              // bumped to drop the result of a cancelled planning
    private var shownMsgId = 0           // last navigation message shown
    private var pendingItinFit = false   // zoom to the option when it is drawn
    private lateinit var navCancel: Button
    private var showLegStops = false
    @Volatile private var lineColorCache: Map<String, List<Int>> = emptyMap()
    // last known position (real GPS, or the simulated one in debug), for distances
    private var myLat: Double? = null
    private var myLon: Double? = null
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

        zoomBox = findViewById(R.id.zoomBox)
        findViewById<Button>(R.id.zoomIn).setOnClickListener { map.zoomBy(2f) }
        findViewById<Button>(R.id.zoomOut).setOnClickListener { map.zoomBy(0.5f) }
        centerBtn = findViewById(R.id.center)
        lineFitBtn = findViewById(R.id.lineFit)
        lineFitBtn.setOnClickListener { fitLine() }
        // withdraw the offer as soon as the view shows the whole line (zoomed by hand or by us)
        map.onCameraDrawn = { if (lineFitOffered && lineFitBtn.visibility == View.VISIBLE) map.post { refreshLineFit() } }
        // center-me cycles as in Google Maps: go to me and follow -> heading-up -> north-up follow
        centerBtn.setOnClickListener {
            when {
                !map.follow -> if (!map.centerOnMe()) Toast.makeText(this, "Waiting for your location…", Toast.LENGTH_SHORT).show()
                !map.headingUp -> map.setHeadingUpMode(true)
                else -> map.setHeadingUpMode(false)
            }
        }
        // ◎ not following, ◉ following, ▲ heading-up; the compass sensor only runs in heading-up
        map.onTrackChanged = { f, h ->
            centerBtn.setText(when { h -> R.string.center_heading; f -> R.string.center_following; else -> R.string.center })
            updateHeadingSensor(); updateDebug()
        }
        compassBtn = findViewById(R.id.compass)
        compassBtn.setOnClickListener { map.resetNorth() }
        map.onBearingChanged = { b -> compassBtn.rotation = -b; updateCompass() }
        searchBar = findViewById(R.id.searchBar); searchResults = findViewById(R.id.searchResults)
        startInput = findViewById(R.id.startInput); endInput = findViewById(R.id.endInput)
        navInputs = findViewById(R.id.navInputs)
        setupSearchInputs()
        tabExplore = findViewById(R.id.tabExplore); tabNavigate = findViewById(R.id.tabNavigate)
        tabMisc = findViewById(R.id.tabMisc)
        content = findViewById(R.id.content); swipeHint = findViewById(R.id.swipeHint)
        edgeLeft = findViewById(R.id.edgeLeft); edgeRight = findViewById(R.id.edgeRight)
        setupEdgeSwipe()
        textPanel = findViewById(R.id.textPanel); textContent = findViewById(R.id.textContent)
        timeBtn = findViewById(R.id.timeBtn); planBtn = findViewById(R.id.planBtn)
        navOptions = findViewById(R.id.navOptions); navSelected = findViewById(R.id.navSelected)
        navActive = findViewById(R.id.navActive)
        optInfo = findViewById(R.id.optInfo); selInfo = findViewById(R.id.selInfo)
        findViewById<Button>(R.id.optPrev).setOnClickListener { stepOption(-1) }
        findViewById<Button>(R.id.optNext).setOnClickListener { stepOption(1) }
        findViewById<Button>(R.id.optEdit).setOnClickListener { setNavState(NavState.INPUT) }
        findViewById<Button>(R.id.optSelect).setOnClickListener { setNavState(NavState.SELECTED) }
        findViewById<Button>(R.id.selOther).setOnClickListener { setNavState(NavState.OPTIONS) }
        findViewById<Button>(R.id.selStart).apply {
            setOnClickListener { currentItinerary?.let { startNavigation(it, simulate = false) } }
            // debug: long-press replays the journey with simulated GPS
            setOnLongClickListener { if (Settings.debug(this@MainActivity)) currentItinerary?.let { startNavigation(it, simulate = true) }; true }
        }
        findViewById<Button>(R.id.busLeft).setOnClickListener { NavHub.busLeft(this) }
        navCancel = findViewById(R.id.navCancel)
        navCancel.setOnClickListener { cancelNav() }
        tabExplore.setOnClickListener { setMode(Mode.EXPLORE) }
        tabNavigate.setOnClickListener { setMode(Mode.NAVIGATE) }
        tabMisc.setOnClickListener { showMisc() }
        tabMisc.imageTintList = ColorStateList.valueOf(iconOff)
        findViewById<Button>(R.id.swapEnds).setOnClickListener { swapEndpoints() }
        timeBtn.setOnClickListener { pickDateTime { departWhen = it; refreshTimeLabel() } }
        planBtn.setOnClickListener { runPlan() }
        refreshTimeLabel()
        // Explore: a stop tap selects the stop, a chip selects its line, an empty tap selects
        // the point. Navigate keeps the stop popup (with navigate from / to).
        map.onStopTapped = { bs ->
            stopFor(bs)?.let { st ->
                if (mode != Mode.EXPLORE) { showStop(st); return@let }
                // a stop of the line on show: straight to that stop with this line picked
                val line = selectedLine
                val dirs = shownLineStopDirs[bs.id]
                if (sub == Sub.LINE && line != null && dirs != null)
                    selectStop(st, moveCamera = false, line = line, dir = if (selectedDir in dirs) selectedDir else dirs.first())
                else selectStop(st, moveCamera = false)
            }
        }
        // a line picked from the stop's chips: show it but keep the view (the button offers the zoom)
        // stop on the map: the first tap on a line shows where it goes and when it leaves,
        // the second tap on the same line shows the line (keeping the view)
        map.onChipTapped = { code ->
            if (mode == Mode.EXPLORE) {
                if (stopLine == code) selectLine(code, stopLineDir, fit = false)
                else pickStopLine(code, stopLineDirs[code] ?: 0)
            }
        }
        map.onMapTapped = { lat, lon -> if (mode == Mode.EXPLORE) selectPoint(lat, lon) }
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
            Preloader.labels?.let { applyLabels(it) }
            rebuildIndex()
        } else {
            Thread {
                val data = Preloader.getMapData(this)
                val lbl = Preloader.getLabels(this)
                runOnUiThread { map.data = data; applyLabels(lbl); rebuildIndex() }
            }.start()
        }

        if (DbManager.isImported(this)) {
            Thread { if (DbManager.db == null) DbManager.openExisting(this); loadData() }.start()
        } else {
            promptImport()
        }
        requestLocationPerms()
        applyHighContrast()
        refreshView()
    }

    // ---------------- modes (Explore/Navigate) × views (Map/Text)
    private fun setMode(m: Mode) { mode = m; hideSearch(); updateMapForMode(); refreshView(); updateDebug() }
    private fun setView(v: ViewKind) {
        if (v == ViewKind.MAP && viewKind != ViewKind.MAP) lineFitOffered = true
        viewKind = v; hideSearch(); refreshView(); updateDebug()
    }

    /**
     * Map <-> text by pulling from either edge (two views, so both sides toggle).
     * The text page slides over the map, following the finger; tapping a handle
     * switches too. A hint says so until the user has pulled once.
     */
    private fun setupEdgeSwipe() {
        var toText = false
        var sign = 1f
        fun w() = content.width.toFloat()
        content.listener = object : EdgeSwipeLayout.Listener {
            override fun onPullStart(fromLeft: Boolean): Boolean {
                textPanel.animate().cancel()
                toText = viewKind == ViewKind.MAP
                sign = if (fromLeft) -1f else 1f
                if (toText) { buildTextPanel(); textPanel.translationX = sign * w(); textPanel.visibility = View.VISIBLE }
                return true
            }
            override fun onPull(dx: Float) {
                textPanel.translationX = if (toText) sign * w() + dx else dx
            }
            override fun onPullEnd(commit: Boolean) {
                // text in: settle at 0 (commit) or back out; text out: off the far side or back to 0
                val target = when {
                    toText && commit -> 0f
                    toText -> sign * w()
                    commit -> -sign * w()
                    else -> 0f
                }
                textPanel.animate().translationX(target).setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        textPanel.translationX = 0f
                        if (commit) {
                            prefs.edit().putBoolean("swipe_learned", true).apply()
                            swipeHint.visibility = View.GONE
                            setView(if (toText) ViewKind.TEXT else ViewKind.MAP)
                        } else if (toText) textPanel.visibility = View.GONE
                    }.start()
            }
        }
        val toggle = View.OnClickListener { setView(if (viewKind == ViewKind.MAP) ViewKind.TEXT else ViewKind.MAP) }
        edgeLeft.setOnClickListener(toggle); edgeRight.setOnClickListener(toggle)
        if (!prefs.getBoolean("swipe_learned", false)) {
            swipeHint.setText(R.string.hint_swipe_to_text)
            swipeHint.visibility = View.VISIBLE
            swipeHint.postDelayed({ swipeHint.animate().alpha(0f).setDuration(400).withEndAction {
                swipeHint.visibility = View.GONE; swipeHint.alpha = 1f }.start() }, 6000)
        }
    }

    private val highContrast get() = HighContrast.on(this)

    /**
     * High contrast for bright sun: the map draws itself in black and white, and the
     * rest of the window is shown in grey with the contrast pushed towards pure
     * black and white (line colours become shades, their numbers stay readable).
     */
    private fun applyHighContrast() {
        val hc = highContrast
        map.highContrast = hc
        HighContrast.apply(this, window)
        // red text would turn almost black under the filter
        destination.setTextColor(if (hc) Color.WHITE else Color.rgb(238, 0, 0))
        refreshTabs()
    }

    private fun refreshTabs() {
        // high contrast: the chosen mode is white with black text, the other black with white text
        val on = if (highContrast) Color.WHITE else tabOn
        for ((b, sel) in listOf(tabExplore to (mode == Mode.EXPLORE), tabNavigate to (mode == Mode.NAVIGATE))) {
            b.backgroundTintList = ColorStateList.valueOf(if (sel) on else tabOff)
            b.setTextColor(if (sel && highContrast) Color.BLACK else Color.WHITE)
        }
        // the edge handles show the view a pull would bring in
        val other = if (viewKind == ViewKind.MAP) R.drawable.ic_text else R.drawable.ic_map
        edgeLeft.setImageResource(other); edgeRight.setImageResource(other)
        // center-me and zoom only make sense on the map
        centerBtn.visibility = if (viewKind == ViewKind.MAP) View.VISIBLE else View.GONE
        zoomBox.visibility = centerBtn.visibility
        refreshLineFit()
        updateCompass()
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
        val active = NavHub.snapshot.active
        fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        show(searchBar, !nav)
        show(navActive, nav && active)
        show(navOptions, nav && !active && navState == NavState.OPTIONS)
        show(navSelected, nav && !active && navState == NavState.SELECTED)
        show(navInputs, nav && !active && navState == NavState.INPUT)
        if (navInputs.visibility == View.VISIBLE) syncNavInputs()
        if (navOptions.visibility == View.VISIBLE) currentItinerary?.let {
            optInfo.text = getString(R.string.nav_option, optIdx + 1, planOptions.size) + "\n" + itinLine(it)
        }
        if (navSelected.visibility == View.VISIBLE) currentItinerary?.let { selInfo.text = itinLine(it) + "\n" + summary(it) }
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
        map.itinLabels = emptyList()
        shownLineStopDirs = emptyMap()
        map.routes = emptyList(); map.activeRouteId = null; map.itinPaths = emptyList(); map.invalidate()
    }

    private fun updateMapForMode() {
        when (mode) {
            Mode.EXPLORE -> updateExploreMap()
            Mode.NAVIGATE -> {
                map.clearSearchPin(); map.clearStopCallout(); map.selectedStopId = null
                currentItinerary?.let { drawItinerary(it) } ?: clearRoutes()
            }
        }
    }

    /** Explore on the map: the point's pin, the whole line, or the stop with its line chips. */
    private fun updateExploreMap() {
        map.clearStopCallout(); map.selectedStopId = null
        when (sub) {
            Sub.POINT -> {
                clearRoutes()
                val la = pointLat; val lo = pointLon
                if (la != null && lo != null) map.setSearchPin(la, lo) else map.clearSearchPin()
            }
            Sub.LINE -> {
                map.clearSearchPin()
                // "fancy zoom" to the whole line when it is chosen, unless the map follows you
                selectedLine?.let { showRouteOnMap(it, fit = pendingFit && !map.follow) } ?: clearRoutes()
                pendingFit = false
            }
            Sub.STOP -> {
                map.clearSearchPin(); clearRoutes()
                selectedStop?.let { showStopOnMap(it) }
            }
        }
    }

    private fun refreshLineFit() {
        if (!::lineFitBtn.isInitialized) return
        val onMap = mode == Mode.EXPLORE && sub == Sub.LINE && selectedLine != null && viewKind == ViewKind.MAP
        val b = lineBounds
        if (onMap && lineFitOffered && b != null) {
            val d = resources.displayMetrics.density
            if (map.isApproxFit(b[0], b[1], b[2], b[3], 24 * d, 24 * d, 76 * d, 88 * d)) lineFitOffered = false
        }
        // lineBounds may still be loading: keep the offer, show the button once known
        val show = onMap && lineFitOffered && b != null
        val vis = if (show) View.VISIBLE else View.GONE
        if (lineFitBtn.visibility != vis) lineFitBtn.visibility = vis
    }

    /** The explore selection changed: redraw the map and, if shown, the text. Never switches view. */
    private fun exploreChanged() {
        refreshLineFit()
        if (mode == Mode.EXPLORE) {
            updateMapForMode()
            if (viewKind == ViewKind.TEXT) buildTextPanel()
        }
        updateDebug()
    }

    private fun snap() = Snap(sub, pointLat, pointLon, selectedStop, stopLine, stopLineDir,
        selectedLine, selectedDir, lineTrip, lineTextTime, lineVariant)

    /** Keep the current Explore state for the back button (before changing it). */
    private fun remember() {
        if (mode != Mode.EXPLORE) return
        val s = snap()
        if (history.lastOrNull() != s) history.add(s)
        while (history.size > 60) history.removeAt(0)
    }

    private fun restore(s: Snap) {
        sub = s.sub; pointLat = s.pointLat; pointLon = s.pointLon; selectedStop = s.stop
        stopLine = s.stopLine; stopLineDir = s.stopLineDir; selectedLine = s.line; selectedDir = s.dir
        lineTrip = s.lineTrip; lineTextTime = s.lineTextTime; lineVariant = s.lineVariant
        if (selectedLine == null) lineBounds = null
        pendingFit = false; lineFitOffered = sub == Sub.LINE
        exploreChanged()
    }

    /**
     * Back: in Explore, return to the previous state (in the same view: never switches
     * map/text). With nothing to go back to, a second press within 2 s leaves the app.
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (searchResults.visibility == View.VISIBLE) { hideSearch(); return }
        // Navigate: selected -> options -> from/to (not while navigating)
        if (mode == Mode.NAVIGATE && !NavHub.snapshot.active) {
            when (navState) {
                NavState.SELECTED -> { setNavState(if (planOptions.isEmpty()) NavState.INPUT else NavState.OPTIONS); return }
                NavState.OPTIONS -> { setNavState(NavState.INPUT); return }
                NavState.INPUT -> {}
            }
        }
        if (mode == Mode.EXPLORE && history.isNotEmpty()) {
            val cur = snap()
            var s = history.removeAt(history.size - 1)
            while (s == cur && history.isNotEmpty()) s = history.removeAt(history.size - 1)
            if (s != cur) { restore(s); return }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBack < 2000) { super.onBackPressed(); return }
        lastBack = now
        toast(getString(R.string.back_again))
    }

    /** POINT mode at (lat, lon); null = your own position. */
    private fun selectPoint(lat: Double?, lon: Double?) {
        remember()
        sub = Sub.POINT; pointLat = lat; pointLon = lon
        exploreChanged()
    }

    /** STOP mode for [stop]; [moveCamera] centres the map on it (when chosen from text or search). */
    private fun selectStop(stop: Stop, moveCamera: Boolean, line: String? = null, dir: Int = 0) {
        remember()
        sub = Sub.STOP; selectedStop = stop; stopLine = line; stopLineDir = dir
        if (moveCamera && !map.follow) map.centerOn(stop.asBusStop())
        exploreChanged()
    }

    /** LINE mode for [code] in direction [dir]; with [fit] the map zooms to the whole line. */
    private fun selectLine(code: String, dir: Int = 0, fit: Boolean = true, trip: Long? = null) {
        remember()
        if (code != selectedLine) lineBounds = null
        sub = Sub.LINE; selectedLine = code; selectedDir = dir; pendingFit = fit
        lineFitOffered = true; lineTrip = trip; lineVariant = null
        lineTextTime = LocalDateTime.now(Malta.ZONE)
        exploreChanged()
    }

    /** STOP mode, first step on a line: [code] in direction [dir] at the selected stop (null = all lines). */
    private fun pickStopLine(code: String?, dir: Int) {
        remember()
        stopLine = code; stopLineDir = dir
        exploreChanged()
    }

    /** STOP mode on the map: highlight the stop and show its lines as coloured chips. */
    private fun showStopOnMap(stop: Stop) {
        map.selectedStopId = stop.number.toString()
        val db = DbManager.db ?: return
        Thread {
            val now = LocalDateTime.now(Malta.ZONE)
            val v = db.versionFor(now.toLocalDate())
            val lines = db.linesForStop(stop.number, v).sortedWith(compareBy({ codeKey(it.code) }, { it.code }))
            // per line, the direction of its next departure here (to open that direction in line mode)
            val dirs = HashMap<String, Int>()
            for (d in db.departuresFrom(stop.number, now, 300)) dirs.putIfAbsent(d.routeCode, d.directionIndex)
            val chips = lines.map { l ->
                val text = db.routeStyle(l.code)?.textColor ?: contrastText(l.color)
                MapView.Chip(l.code, l.color, text)
            }
            // the picked line: destination, next departure and the one after
            val pick = stopLine; val pickDir = stopLineDir
            val detail = if (pick == null) emptyList() else {
                val deps = db.departuresFrom(stop.number, now, 400)
                    .filter { it.routeCode == pick && it.directionIndex == pickDir }.take(2)
                val towards = deps.firstOrNull()?.towards ?: db.directionNames(pick, v)[pickDir] ?: ""
                val a = deps.getOrNull(0); val b = deps.getOrNull(1)
                listOf("$pick  →  $towards",
                    if (a == null) "No more departures today"
                    else "${Malta.hhmm(a.minutes)} (${minsFrom(now, a)})" + (b?.let { "  ·  then ${Malta.hhmm(it.minutes)}" } ?: "  ·  last today"))
            }
            runOnUiThread {
                // still the same selection?
                if (mode == Mode.EXPLORE && sub == Sub.STOP && selectedStop?.number == stop.number && stopLine == pick) {
                    stopLineDirs = dirs
                    map.setStopCallout(stop.x, stop.y, chips)
                    map.setCalloutDetail(pick, detail)
                }
            }
        }.start()
    }

    private fun minsFrom(now: LocalDateTime, d: Departure): String {
        val m = java.time.Duration.between(now, d.time).toMinutes()
        return if (m <= 0) "now" else "in $m min"
    }

    /** Black or white text, whichever reads better on [bg]. */
    private fun contrastText(bg: Int): Int {
        val l = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
        return if (l > 150) Color.BLACK else Color.WHITE
    }

    /** Debug mode: a line at the top of the map showing the mode/selection state. */
    private fun updateDebug() {
        if (!::map.isInitialized) return
        if (!Settings.debug(this)) { map.debugText = null; return }
        val sel = when (mode) {
            Mode.NAVIGATE -> "NAVIGATE"
            Mode.EXPLORE -> "EXPLORE · " + when (sub) {
                Sub.POINT -> pointLat?.let { "POINT %.5f,%.5f".format(java.util.Locale.US, it, pointLon) } ?: "POINT (you)"
                Sub.LINE -> "LINE ${selectedLine ?: "(list)"}/$selectedDir"
                Sub.STOP -> "STOP ${selectedStop?.let { "#${it.number}" } ?: "-"}" + (stopLine?.let { " · $it/$stopLineDir" } ?: "")
            }
        }
        val track = when { map.headingUp -> "heading-up"; map.follow -> "follow"; else -> "free" }
        map.debugText = "$sel · $track · ${viewKind.name.lowercase()}"
    }

    private fun drawItinerary(itin: Itinerary) {
        val db = DbManager.db ?: return
        Thread {
            val v = db.versionFor(itin.serviceDate)
            val byNum = allStops.associateBy { it.number }
            val tripVar = db.tripVariants(itin.busLegs.map { it.tripId })
            val shapeCache = HashMap<String, Polyline?>()
            fun shapeFor(code: String, dir: Int): Polyline? = shapeCache.getOrPut("$code/$dir") {
                db.directionsForRoute(code, v).firstOrNull { it.directionIndex == dir }?.shape
            }
            val paths = ArrayList<MapView.ItinPath>()
            for (leg in itin.legs) when (leg) {
                is WalkLeg -> {
                    val p = Path()
                    val rp = Preloader.walkGraph?.route(leg.fromLat, leg.fromLon, leg.toLat, leg.toLon)
                    if (rp != null && rp.pts.size >= 4) {
                        p.moveTo(rp.pts[0], rp.pts[1])
                        var i = 2; while (i < rp.pts.size) { p.lineTo(rp.pts[i], rp.pts[i + 1]); i += 2 }
                    } else {
                        p.moveTo(Geo.x(leg.fromLon), Geo.y(leg.fromLat)); p.lineTo(Geo.x(leg.toLon), Geo.y(leg.toLat))
                    }
                    paths.add(MapView.ItinPath(p, 0, true))
                }
                is BusLeg -> {
                    val p = Path()
                    // the geometry of the variant this trip runs, else the direction's main route
                    val variant = tripVar[leg.tripId]?.let { db.lineVariants(leg.routeCode, v).byId[it] }
                    val shape = variant?.let { db.variantShape(it) } ?: shapeFor(leg.routeCode, leg.directionIndex)
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
            // time labels: departure, each boarding (time + line), each alighting, arrival
            val labels = ArrayList<MapView.ItinLabel>()
            itin.legs.firstOrNull()?.let { l ->
                val (x, y) = when (l) {
                    is WalkLeg -> Geo.x(l.fromLon) to Geo.y(l.fromLat)
                    is BusLeg -> byNum[l.boardNumber]?.let { it.x to it.y } ?: (Float.NaN to Float.NaN)
                    else -> Float.NaN to Float.NaN
                }
                if (!x.isNaN() && l is WalkLeg) labels += MapView.ItinLabel(x, y, "${hm(itin.departMinutes)} start")
            }
            for (l in itin.busLegs) {
                byNum[l.boardNumber]?.let { labels += MapView.ItinLabel(it.x, it.y, "${hm(l.boardMinutes)}  bus ${l.routeCode}") }
                byNum[l.alightNumber]?.let { labels += MapView.ItinLabel(it.x, it.y, "${hm(l.alightMinutes)}  off") }
            }
            (itin.legs.lastOrNull() as? WalkLeg)?.let { labels += MapView.ItinLabel(Geo.x(it.toLon), Geo.y(it.toLat), "${hm(itin.arriveMinutes)}  arrive") }
            // extent, to zoom to a newly shown option
            val rb = android.graphics.RectF(); val tmp = android.graphics.RectF(); var any = false
            for (p in paths) { p.path.computeBounds(tmp, true); if (!any) { rb.set(tmp); any = true } else rb.union(tmp) }
            runOnUiThread {
                map.routes = emptyList(); map.activeRouteId = null; map.itinPaths = paths; map.itinLabels = labels; map.invalidate()
                if (pendingItinFit && any && !map.follow) {
                    pendingItinFit = false
                    val d = resources.displayMetrics.density
                    map.fitBounds(rb.left, rb.top, rb.right, rb.bottom, 60 * d, 40 * d, 76 * d, 60 * d)
                }
            }
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
    private fun clickRow(swatches: List<List<Int>>, line1: String, line2: String, onClick: () -> Unit): View =
        colorRow(swatches, line1, line2).apply { setOnClickListener { onClick() } }

    /** A line's direction colours from route_shape (distinct, by direction); fallback if unknown yet. */
    private fun lineColors(code: String, fallback: Int): List<Int> = lineColorCache[code] ?: listOf(fallback)

    private var textGen = 0   // bumped on every rebuild; background loads check it before adding rows

    private fun buildTextPanel() {
        textGen++
        textContent.removeAllViews()
        if (DbManager.db == null) { textContent.addView(tv(getString(R.string.db_none))); return }
        when (mode) { Mode.EXPLORE -> buildExploreText(); Mode.NAVIGATE -> buildNavigateText() }
    }

    private fun buildExploreText() = when (sub) {
        Sub.POINT -> buildPointText()
        Sub.LINE -> buildLineText()
        Sub.STOP -> buildStopText()
    }

    /** LINE: times along the line (earlier / later trip), or the list of all lines. */
    private fun buildLineText() {
        val db = DbManager.db ?: return
        val code = selectedLine
        if (code == null) {
            textContent.addView(tv(getString(R.string.text_select_line), white, 17f, true))
            val gen = textGen
            Thread {
                val v = db.versionFor(LocalDate.now(Malta.ZONE))
                val dirs = db.allLineDirections(v)
                val byNum = allStops.associateBy { it.number }
                val sorted = dirs.sortedWith(compareBy({ codeKey(it.code) }, { it.code }, { it.directionIndex }))
                runOnUiThread {
                    if (gen != textGen) return@runOnUiThread
                    for (d in sorted) {
                        val start = byNum[d.firstStop]?.display ?: "?"
                        val end = byNum[d.lastStop]?.display ?: d.name.ifBlank { "?" }
                        textContent.addView(clickRow(listOf(lineColors(d.code, d.color)), d.code, "$start  →  $end") {
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
        // choose a direction and, within it, one variant (or all its trips)
        var choices: List<Triple<String, Int, Long?>> = emptyList()   // label, direction, variant
        val flip = smallBtn(getString(R.string.text_select_variant)) {
            if (choices.isEmpty()) return@smallBtn
            HcDialog(this).setTitle(getString(R.string.text_select_variant))
                .setItems(choices.map { it.first }.toTypedArray()) { _, w ->
                    val (_, d, x) = choices[w]
                    remember(); selectedDir = d; lineVariant = x; lineTrip = null; exploreChanged()
                }.show()
        }
        flip.isEnabled = false; flip.alpha = 0.35f   // until we know there is something to choose
        c1.addView(flip)
        textContent.addView(c1)
        val c2 = LinearLayout(this)
        c2.addView(smallBtn(getString(R.string.text_all_lines)) { showAllLines() })
        textContent.addView(c2)
        val gen = textGen
        Thread {
            val v = db.versionFor(lineTextTime.toLocalDate())
            val dirs = db.directionsForRoute(code, v)
            val dir = dirs.firstOrNull { it.directionIndex == selectedDir } ?: dirs.firstOrNull()
            val di = dir?.directionIndex ?: selectedDir
            val nowMin = lineTextTime.hour * 60 + lineTextTime.minute
            // every trip in this direction, whichever variant it runs - or only the chosen variant's
            val lv = db.lineVariants(code, v)
            val chosen = lineVariant?.let { lv.byId[it] }?.takeIf { it.directionIndex == di }
            val allTrips = db.tripsOf(code, di, lineTextTime.toLocalDate(), v)
            val trips = if (chosen == null) allTrips else {
                val tv = db.tripVariants(allTrips.map { it.first })
                allTrips.filter { tv[it.first] == chosen.id }
            }
            // what can be chosen: each direction (all trips), then its variants with their difference
            val opts = ArrayList<Triple<String, Int, Long?>>()
            for (d in dirs) {
                val vs = lv.byId.values.filter { it.directionIndex == d.directionIndex }
                    .sortedWith(compareBy({ !it.main }, { it.variant }))
                opts += Triple("→ ${d.name}" + if (vs.size > 1) "  (all variants)" else "", d.directionIndex, null)
                if (vs.size > 1) for (x in vs)
                    opts += Triple("      ${lv.label(x.id)}", d.directionIndex, x.id)
            }
            // a run picked at a stop, else the first one from the chosen time
            var idx = lineTrip?.let { t -> trips.indexOfFirst { it.first == t } } ?: -1
            if (idx < 0) { idx = trips.indexOfFirst { it.second >= nowMin }; if (idx < 0) idx = trips.size - 1 }
            val trip = trips.getOrNull(idx); val nextTrip = trips.getOrNull(idx + 1)
            val stops = trip?.let { db.tripStops(it.first) } ?: emptyList()
            val col = dir?.color ?: db.routeColor(code, v)
            val byNum = allStops.associateBy { it.number }
            val gap = if (trip != null && nextTrip != null) nextTrip.second - trip.second else null
            // variants: how this trip differs from the line's other versions in this direction
            val variants = lv.labelsFor(di)
            val thisTrip = trip?.let { lv.label(db.tripVariants(listOf(it.first))[it.first]) }.orEmpty()
            runOnUiThread {
                if (gen != textGen) return@runOnUiThread
                choices = opts
                val any = opts.size > 1
                flip.isEnabled = any; flip.alpha = if (any) 1f else 0.35f
                textContent.addView(tv("$code  →  ${dir?.name ?: ""}", col, 18f, true))
                if (chosen != null) textContent.addView(tv("Variant: ${lv.label(chosen.id)}", white, 14f, true))
                else if (variants.size > 1) textContent.addView(tv("All ${variants.size} variants: ${variants.joinToString("  ·  ")}", grey, 13f))
                val headway = gap?.let { "   ·   next bus in $it min" } ?: if (trip != null) "   ·   last trip" else ""
                textContent.addView(tv("Trip departing ${trip?.let { Malta.hhmm(it.second) } ?: "—"}$headway", grey, 13f))
                if (thisTrip.isNotEmpty() && chosen == null) textContent.addView(tv("This trip: $thisTrip", white, 14f, true))
                for (s in stops) {
                    val stop = s.number?.let { byNum[it] }
                    val nm = stop?.display ?: s.name
                    val row = tv("${Malta.hhmm(s.minutes)}    $nm", white, 15f)
                    // → that stop, with this line picked
                    if (stop != null) row.setOnClickListener { selectStop(stop, moveCamera = true, line = code, dir = di) }
                    textContent.addView(row)
                }
                if (stops.isEmpty()) textContent.addView(tv("No trips found for this time.", grey))
            }
        }.start()
    }

    /** LINE mode without a line: the list of all lines. */
    private fun showAllLines() { remember(); sub = Sub.LINE; selectedLine = null; lineTrip = null; exploreChanged() }

    /** POINT: coordinates, the street and town nearby, the nearest landmark and stops. */
    private fun buildPointText() {
        val useMe = pointLat == null
        val la = if (useMe) myLat else pointLat; val lo = if (useMe) myLon else pointLon
        textContent.addView(tv(if (useMe) "Your position" else "Selected point", white, 17f, true))
        if (la == null || lo == null) {
            textContent.addView(tv("Tap the map to pick a point, or wait for your location.", grey))
            textContent.addView(smallBtn(getString(R.string.text_all_lines)) { showAllLines() })
            return
        }
        pointTextAt = doubleArrayOf(la, lo)
        val x = Geo.x(lo); val y = Geo.y(la)
        fun d(px: Float, py: Float) = Math.hypot((px - x).toDouble(), (py - y).toDouble())
        fun dist(m: Double) = if (m < 1000) "${Math.round(m / 10) * 10} m" else "%.1f km".format(java.util.Locale.US, m / 1000)
        textContent.addView(tv("%.5f, %.5f".format(java.util.Locale.US, la, lo), grey, 14f))
        // no house numbers in the data: the nearest named street label and the stop's town
        val nearStop = allStops.minByOrNull { d(it.x, it.y) }
        val locality = nearStop?.locality ?: places.minByOrNull { d(it.x, it.y) }?.name
        val street = streets.minByOrNull { d(it.x, it.y) }
        val where = listOfNotNull(street?.name, locality).joinToString(", ")
        if (where.isNotEmpty()) textContent.addView(tv("Nearby: $where", white, 15f))
        landmarks.minByOrNull { d(it.x, it.y) }?.let { lm ->
            textContent.addView(tv("Landmark: ${lm.name}  (${dist(d(lm.x, lm.y))})", white, 15f).apply {
                setOnClickListener { selectPoint(Geo.lat(lm.y).toDouble(), Geo.lon(lm.x).toDouble()) }
            })
        }
        textContent.addView(tv("Nearest stops", white, 16f, true))
        for (s in allStops.sortedBy { d(it.x, it.y) }.take(3))
            textContent.addView(clickRow(emptyList(), s.display, "#${s.number}  ·  ${dist(d(s.x, s.y))}") {
                selectStop(s, moveCamera = true)
            })
        val row = LinearLayout(this)
        row.addView(smallBtn(getString(R.string.text_all_lines)) { showAllLines() })
        if (!useMe) row.addView(smallBtn("Use my position") { selectPoint(null, null) })
        textContent.addView(row)
    }

    /** STOP: every line at the stop with its next departure and the one after. */
    private fun buildStopText() {
        val stop = selectedStop
        if (stop == null) {
            textContent.addView(tv("Tap a stop on the map to see its departures.", grey))
            textContent.addView(smallBtn(getString(R.string.text_all_lines)) { showAllLines() })
            return
        }
        val db = DbManager.db ?: return
        textContent.addView(tv(stop.display, white, 17f, true))
        textContent.addView(tv("Stop #${stop.number}", grey, 13f))
        stopLine?.let { buildStopLineText(db, stop, it, stopLineDir); return }
        textContent.addView(smallBtn(getString(R.string.text_all_lines)) { showAllLines() })
        val gen = textGen
        Thread {
            val now = LocalDateTime.now(Malta.ZONE)
            val v = db.versionFor(now.toLocalDate())
            val lines = db.linesForStop(stop.number, v)
            // first two upcoming departures per line + direction + variant
            val deps = db.departuresFrom(stop.number, now, 400)
            val tripVar = db.tripVariants(deps.map { it.tripId })
            val per = LinkedHashMap<String, MutableList<Departure>>()
            for (d in deps) {
                val l = per.getOrPut("${d.routeCode}/${d.directionIndex}/${tripVar[d.tripId] ?: -1}") { ArrayList() }
                if (l.size < 2) l.add(d)
            }
            val rows = per.values.sortedWith(compareBy({ codeKey(it[0].routeCode) }, { it[0].routeCode },
                { it[0].directionIndex }, { it[0].time }))
            val variantLabel = rows.associate { r -> r[0].tripId to db.lineVariants(r[0].routeCode, v).label(tripVar[r[0].tripId]) }
            val served = rows.map { it[0].routeCode }.toSet()
            val idle = lines.filter { it.code !in served }.sortedWith(compareBy({ codeKey(it.code) }, { it.code }))
            runOnUiThread {
                if (gen != textGen) return@runOnUiThread   // the panel was rebuilt meanwhile
                for (r in rows) {
                    val a = r[0]; val b = r.getOrNull(1)
                    val mins = java.time.Duration.between(now, a.time).toMinutes()
                    val next = "${Malta.hhmm(a.minutes)} (${if (mins <= 0) "now" else "in $mins min"})"
                    val then = b?.let { "  ·  then ${Malta.hhmm(it.minutes)}" } ?: "  ·  last today"
                    val vl = variantLabel[a.tripId].orEmpty().let { if (it.isEmpty()) "" else "  ·  $it" }
                    textContent.addView(clickRow(listOf(lineColors(a.routeCode, white)),
                        "${a.routeCode}  →  ${a.towards}$vl", next + then) { pickStopLine(a.routeCode, a.directionIndex) })
                }
                for (l in idle) textContent.addView(clickRow(listOf(lineColors(l.code, l.color)), l.code,
                    "no more departures today") { pickStopLine(l.code, stopLineDirs[l.code] ?: 0) })
                if (rows.isEmpty() && idle.isEmpty()) textContent.addView(tv("No lines serve this stop.", grey))
            }
        }.start()
    }

    /**
     * STOP, one line picked: all of today's departures of that line (direction) at this
     * stop, the next one in bold. Tapping a time (or the line) shows that whole run.
     */
    private fun buildStopLineText(db: MptDb, stop: Stop, code: String, dir: Int) {
        val bar = LinearLayout(this)
        bar.addView(smallBtn(getString(R.string.text_stop_all_lines)) { pickStopLine(null, 0) })
        textContent.addView(bar)
        val gen = textGen
        Thread {
            val now = LocalDateTime.now(Malta.ZONE)
            val today = now.toLocalDate()
            val v = db.versionFor(today)
            val deps = db.departures(stop.number, today, v)
                .filter { it.routeCode == code && it.directionIndex == dir }.sortedBy { it.minutes }
            val nowMin = now.hour * 60 + now.minute
            val nextIdx = deps.indexOfFirst { it.minutes >= nowMin }
            val towards = deps.firstOrNull()?.towards ?: db.directionNames(code, v)[dir] ?: ""
            val col = lineColors(code, db.routeColor(code, v)).first()
            runOnUiThread {
                if (gen != textGen) return@runOnUiThread
                val next = deps.getOrNull(nextIdx)
                textContent.addView(clickRow(listOf(lineColors(code, col)), "$code  →  $towards",
                    next?.let { "Next ${Malta.hhmm(it.minutes)} (${minsFrom(now, it)})  ·  tap a time for the whole run" }
                        ?: "No more departures today") {
                    selectLine(code, dir, trip = (next ?: deps.lastOrNull())?.tripId)
                })
                if (deps.isEmpty()) { textContent.addView(tv("No departures today.", grey)); return@runOnUiThread }
                // the times in rows of five; past ones grey, the next one bold
                val d = resources.displayMetrics.density
                var row: LinearLayout? = null
                for ((i, dep) in deps.withIndex()) {
                    if (i % 5 == 0) { row = LinearLayout(this); textContent.addView(row) }
                    val past = nextIdx < 0 || i < nextIdx
                    val t = tv(Malta.hhmm(dep.minutes), if (past) grey else white, 16f, i == nextIdx).apply {
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        if (i == nextIdx) setBackgroundColor(Color.rgb(60, 60, 60))
                        setOnClickListener { selectLine(code, dir, trip = dep.tripId) }
                    }
                    row!!.addView(t)
                }
                // keep the grid aligned: pad the last row
                val rest = (5 - deps.size % 5) % 5
                repeat(rest) { row?.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }) }
            }
        }.start()
    }

    private fun rollLine(step: Int) {
        val db = DbManager.db ?: return; val code = selectedLine ?: return
        Thread {
            val v = db.versionFor(lineTextTime.toLocalDate())
            val dirs = db.directionsForRoute(code, v)
            val di = (dirs.firstOrNull { it.directionIndex == selectedDir } ?: dirs.firstOrNull())?.directionIndex ?: selectedDir
            val all = db.tripsOf(code, di, lineTextTime.toLocalDate(), v)   // all variants ...
            val trips = lineVariant?.let { x ->                                // ... or the chosen one's
                val tv = db.tripVariants(all.map { it.first }); all.filter { tv[it.first] == x }
            }?.takeIf { it.isNotEmpty() } ?: all
            if (trips.isEmpty()) return@Thread
            val nowMin = lineTextTime.hour * 60 + lineTextTime.minute
            var idx = lineTrip?.let { t -> trips.indexOfFirst { it.first == t } } ?: -1
            if (idx < 0) { idx = trips.indexOfFirst { it.second >= nowMin }; if (idx < 0) idx = trips.size - 1 }
            val target = trips[(idx + step).coerceIn(0, trips.size - 1)]
            runOnUiThread {
                lineTextTime = Malta.at(lineTextTime.toLocalDate(), target.second); lineTrip = target.first
                buildTextPanel()
            }
        }.start()
    }

    private fun buildNavigateText() {
        val itin = currentItinerary
        if (itin == null) {
            textContent.addView(tv(getString(R.string.text_no_route), grey))
            textContent.addView(tv("Set from and to above, then tap Plan.", grey, 13f))
            return
        }
        textContent.addView(tv(if (navState == NavState.OPTIONS) getString(R.string.nav_option, optIdx + 1, planOptions.size)
            else getString(R.string.text_route), white, 17f, true))
        textContent.addView(tv("${Malta.hhmm(itin.departMinutes)} → ${Malta.hhmm(itin.arriveMinutes)}  (${itin.arriveMinutes - itin.departMinutes} min)", grey, 14f))
        textContent.addView(smallBtn(if (showLegStops) "Hide stops" else "Show stops") {
            showLegStops = !showLegStops; buildTextPanel()
        })
        val db = DbManager.db
        if (!showLegStops || db == null) {
            addLegRows(itin, emptyMap())
            addNavTextButtons()
        } else {
            // load the intermediate stops of each bus leg off the UI thread, then render in order
            Thread {
                val mids = HashMap<Long, List<TripStop>>()
                for (bl in itin.busLegs) mids[bl.tripId] = intermediateStops(db, bl)
                runOnUiThread {
                    addLegRows(itin, mids)
                    addNavTextButtons()
                }
            }.start()
        }
    }

    /** Under the journey in text: the same choices as the bar above (browse, select, go). */
    private fun addNavTextButtons() {
        val row = LinearLayout(this)
        when (navState) {
            NavState.OPTIONS -> {
                row.addView(smallBtn("◀") { stepOption(-1) })
                row.addView(smallBtn("▶") { stepOption(1) })
                row.addView(smallBtn(getString(R.string.nav_select)) { setNavState(NavState.SELECTED) })
            }
            NavState.SELECTED -> if (!NavHub.snapshot.active) {
                row.addView(smallBtn(getString(R.string.nav_other_options)) { setNavState(NavState.OPTIONS) })
                row.addView(smallBtn(getString(R.string.nav_interactive)) { currentItinerary?.let { startNavigation(it, simulate = false) } })
            }
            NavState.INPUT -> {}
        }
        if (row.childCount > 0) textContent.addView(row)
    }

    private fun addLegRows(itin: Itinerary, mids: Map<Long, List<TripStop>>) {
        val byNum = allStops.associateBy { it.number }
        for (leg in itin.legs) when (leg) {
            is WalkLeg -> {
                val m = Preloader.walkGraph?.route(leg.fromLat, leg.fromLon, leg.toLat, leg.toLon)?.meters?.toInt()
                    ?: leg.meters
                textContent.addView(tv("Walk $m m to ${leg.toName}", white, 15f))
            }
            is BusLeg -> {
                // which version of the line this bus is, if the line has several in this direction
                val vl = DbManager.db?.let { db ->
                    db.lineVariants(leg.routeCode, db.versionFor(leg.serviceDate)).label(db.tripVariants(listOf(leg.tripId))[leg.tripId])
                }.orEmpty().let { if (it.isEmpty()) "" else "  ·  $it" }
                textContent.addView(clickRow(listOf(lineColors(leg.routeCode, leg.color)),
                    "${Malta.hhmm(leg.boardMinutes)}  ${leg.routeCode} → ${leg.towards}$vl",
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

    override fun onResume() {
        super.onResume(); resumed = true
        NavHub.listen(navListener); startOwnLocation(); updateHeadingSensor(); render()
        updateDebug()   // debug mode may have been switched in Settings
    }
    override fun onPause() {
        resumed = false; updateHeadingSensor()
        NavHub.unlisten(navListener); lm.removeUpdates(this); super.onPause()
    }

    /** Compass button: on the map view, only while the map is not north up; its needle points north. */
    private fun updateCompass() {
        if (!::compassBtn.isInitialized) return
        val turned = kotlin.math.abs(MapView.angleDiff(map.bearing, 0f)) > 0.5f
        compassBtn.visibility = if (viewKind == ViewKind.MAP && turned) View.VISIBLE else View.GONE
    }

    // ---------------- heading for heading-up mode: the phone's compass (rotation
    // vector sensor), or the GPS / simulated course while moving faster than a
    // walk (more reliable than a compass inside a bus); smoothed, true north
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var resumed = false
    private var sensorActive = false
    private var compassDeg = Float.NaN
    private var courseDeg = Float.NaN
    private var courseAt = 0L
    private var headingDeg = Float.NaN
    private var declination = 0f
    private val rotM = FloatArray(9); private val rotR = FloatArray(9); private val orient = FloatArray(3)
    private val headingListener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            // some devices deliver 5 values, which getRotationMatrixFromVector rejects
            SensorManager.getRotationMatrixFromVector(rotM, ev.values.copyOf(minOf(4, ev.values.size)))
            val (ax, ay) = when (displayRotation()) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotM, ax, ay, rotR)
            SensorManager.getOrientation(rotR, orient)
            // magnetic north -> true north (the map's north)
            compassDeg = MapView.norm360(Math.toDegrees(orient[0].toDouble()).toFloat() + declination)
            tickHeading()
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0 else windowManager.defaultDisplay.rotation

    /** Run the compass only while heading-up is on and the app is in front. */
    private fun updateHeadingSensor() {
        val want = resumed && map.headingUp
        if (want == sensorActive) return
        if (want) {
            val s = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            if (s != null) {
                declination = GeomagneticField((myLat ?: 35.9).toFloat(), (myLon ?: 14.45).toFloat(), 0f,
                    System.currentTimeMillis()).declination
                sensorActive = sensors.registerListener(headingListener, s, SensorManager.SENSOR_DELAY_GAME)
            }
            // no compass sensor: heading-up then follows the GPS course only
        } else {
            sensors.unregisterListener(headingListener); sensorActive = false; headingDeg = Float.NaN
        }
    }

    /** A direction of travel from GPS or the simulation (only meaningful while moving). */
    private fun setCourse(deg: Float) {
        courseDeg = deg; courseAt = SystemClock.elapsedRealtime()
        if (!sensorActive) tickHeading()   // without a compass, fixes are the only clock
    }

    /** Smooth towards the course (recent, while moving) or else the compass, and turn the map. */
    private fun tickHeading() {
        val recentCourse = !courseDeg.isNaN() && SystemClock.elapsedRealtime() - courseAt < 4000
        val target = if (recentCourse) courseDeg else compassDeg
        if (target.isNaN()) return
        val k = if (sensorActive) 0.15f else 1f
        headingDeg = if (headingDeg.isNaN()) target else MapView.norm360(headingDeg + k * MapView.angleDiff(target, headingDeg))
        map.setDeviceHeading(headingDeg)
    }

    // ---------------- database import (SAF)
    private fun promptImport() {
        HcDialog(this).setTitle(R.string.db_pick_title).setMessage(R.string.db_pick_text)
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
        // each line's direction colours (route_shape.color), distinct, in direction order
        lineColorCache = db.allLineDirections(v).groupBy { it.code }
            .mapValues { (_, ds) -> ds.sortedBy { it.directionIndex }.map { it.color }.distinct() }
        allStops = stops
        NavHub.stops = stops.associateBy { it.number }
        val first = db.firstValidFrom()
        val today = LocalDate.now(Malta.ZONE).toString()
        val byId = bs.associateBy { it.id }
        // what the map can work out once per database: stop directions and stop groups
        val density = resources.displayMetrics.density
        val key = Precalc.key(this, v, density)
        val cached = Precalc.load(this, key, byId)
        runOnUiThread {
            busStopsById = byId
            map.stops = bs
            cached?.let { precalc = it; map.setPrecalc(it) }
            rebuildIndex()
            if (first != null && today < first) toast(getString(R.string.db_future, first))
            render()
        }
        if (cached == null) {
            val note = findViewById<TextView>(R.id.precalcNote)
            runOnUiThread { note.text = getString(R.string.precalc_busy); note.visibility = View.VISIBLE }
            val p = Precalc.compute(db, v, bs, density) { msg ->
                runOnUiThread { note.text = getString(R.string.precalc_busy) + "\n" + msg }
            }
            try { Precalc.save(this, key, p) } catch (e: Exception) { /* used this session anyway */ }
            runOnUiThread {
                if (map.stops === bs) { precalc = p; map.setPrecalc(p) }
                note.visibility = View.GONE
                toast(getString(R.string.precalc_done))
            }
        }
    }

    /** Place, street and landmark names: for search, and drawn on the map. */
    private fun applyLabels(l: Triple<List<MapView.PlaceLabel>, List<MapView.StreetLabel>, List<MapView.PlaceLabel>>) {
        places = l.first; streets = l.second; landmarks = l.third
        map.places = places; map.streets = streets; map.landmarks = landmarks
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
                val pad = (12 * resources.displayMetrics.density).toInt()
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
                var dlg: AlertDialog? = null
                if (rows.isEmpty()) box.addView(tv("No departures from this stop today.", grey))
                for (d in rows) {
                    val mins = java.time.Duration.between(now, d.time).toMinutes()
                    val cd = if (mins <= 0) "now" else "in $mins min"
                    box.addView(clickRow(listOf(lineColors(d.routeCode, colorOf[d.routeCode] ?: white)),
                        "${d.routeCode}    →  ${d.towards}", "${Malta.hhmm(d.minutes)}    ·    $cd") {
                        selectLine(d.routeCode, d.directionIndex); dlg?.dismiss()
                    })
                }
                val place = Place(stop.display, stop.lat, stop.lon)
                dlg = HcDialog(this)
                    .setTitle(stop.display + "  ·  #${stop.number}")
                    .setView(android.widget.ScrollView(this).apply { addView(box) })
                    .setPositiveButton(R.string.nav_from) { _, _ -> navFromMap(place, true) }
                    .setNeutralButton(R.string.nav_to) { _, _ -> navFromMap(place, false) }
                    .create()   // no close button; tap outside (or Back) dismisses
                dlg.show()
                // navigate-from / navigate-to are available while planning, but
                // greyed out while a route is actively being navigated
                val navActive = NavHub.snapshot.active || NavHub.snapshot.arrived
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !navActive
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !navActive
            }
        }.start()
    }

    /** Draw a line on the map; with [fit], also zoom so the whole line is visible. */
    private fun showRouteOnMap(code: String, fit: Boolean = false) {
        val db = DbManager.db ?: return
        Thread {
            // the line's merged drawing from all its variant polylines: precalculated,
            // or worked out now if the precalculation is not there yet
            val gfx = precalc?.lines?.get(code)
                ?: RouteRenderer.fromDb(db, code, db.versionFor(LocalDate.now(Malta.ZONE)), busStopsById)
            val stopDirs = HashMap<String, MutableList<Int>>()
            for (st in gfx.stops) stopDirs.getOrPut(st.id) { ArrayList() }.let { if (st.dir !in it) it.add(st.dir) }
            val bounds = gfx.bounds
            runOnUiThread {
                map.itinPaths = emptyList(); map.routes = listOf(gfx); map.activeRouteId = code; map.invalidate()
                shownLineStopDirs = stopDirs
                if (bounds != null) lineBounds = bounds
                if (fit) fitLine() else refreshLineFit()
            }
        }.start()
    }

    /** Zoom to the whole line on show, clear of the overlays: zoom buttons (top-right),
     *  center-me (bottom-right), the scale bar (bottom-left) and the whole-line button. */
    private fun fitLine() {
        val b = lineBounds ?: return
        lineFitOffered = false; refreshLineFit()
        val d = resources.displayMetrics.density
        map.fitBounds(b[0], b[1], b[2], b[3], 24 * d, 24 * d, 76 * d, 88 * d)
    }

    // ---------------- long-press POI menu
    private fun showLongPressMenu(lat: Double, lon: Double, poi: Poi?) {
        // Navigate: no menu — the point fills a from/to slot directly.
        if (mode == Mode.NAVIGATE) { placeFromMap(lat, lon, poi); return }
        // Explore: manage saved places, plus navigate-from / navigate-to.
        val place = Place(poi?.name ?: "Dropped pin", poi?.lat ?: lat, poi?.lon ?: lon)
        if (poi == null) {
            val items = arrayOf(getString(R.string.poi_store), getString(R.string.nav_from), getString(R.string.nav_to))
            HcDialog(this).setItems(items) { _, w ->
                when (w) { 0 -> storePoi(lat, lon); 1 -> navFromMap(place, true); 2 -> navFromMap(place, false) }
            }.show()
        } else {
            val items = arrayOf(getString(R.string.nav_from), getString(R.string.nav_to), getString(R.string.poi_delete))
            HcDialog(this).setTitle(poi.name).setItems(items) { _, w ->
                when (w) {
                    0 -> navFromMap(place, true)
                    1 -> navFromMap(place, false)
                    2 -> HcDialog(this).setMessage(R.string.poi_delete_confirm)
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
        HcDialog(this).setTitle(R.string.poi_store).setView(input)
            .setPositiveButton(R.string.poi_store) { _, _ ->
                val name = input.text.toString().ifBlank { "POI" }
                PoiStore.add(this, name, lat, lon); pois = PoiStore.load(this); map.pois = pois; rebuildIndex()
            }.setNegativeButton(R.string.close, null).show()
    }

    /** Explore search result: a stop selects STOP mode, anything else POINT mode; the map centres on it. */
    private fun handleMapPick(r: SearchResult) {
        val stop = if (r.kind == SearchResult.Kind.STOP) allStops.firstOrNull { it.number == r.stopNumber } else null
        if (stop != null) { selectStop(stop, moveCamera = true); return }
        selectPoint(r.lat, r.lon)
        map.centerOn(BusStop(r.stopNumber?.toString() ?: "x", r.label, r.lat, r.lon, null))
    }

    // ---------------- burger menu
    private fun showMisc() {
        val items = arrayListOf(getString(R.string.menu_settings), getString(R.string.menu_reload),
            getString(R.string.menu_update),
            getString(if (highContrast) R.string.menu_contrast_off else R.string.menu_contrast_on))
        if (Settings.debug(this)) items.add(getString(R.string.menu_debug))
        items.add(getString(R.string.privacy)); items.add(getString(R.string.menu_about))
        HcDialog(this).setItems(items.toTypedArray()) { _, w ->
            when (items[w]) {
                getString(R.string.menu_settings) -> openSettings()
                getString(R.string.menu_reload) -> reloadMenu()
                getString(R.string.menu_update) -> UpdateFlow(this).start()
                getString(R.string.menu_contrast_on), getString(R.string.menu_contrast_off) -> {
                    HighContrast.set(this, !highContrast); applyHighContrast()
                }
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
            val dist = distanceFromMe(r.lat, r.lon)
            v.findViewById<TextView>(android.R.id.text2).apply {
                text = if (dist != null) "${r.sublabel}  ·  $dist" else r.sublabel; setTextColor(grey)
            }
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
        HcDialog(this).setTitle(R.string.menu_reload).setItems(
            arrayOf(getString(R.string.db_reopen), getString(R.string.db_import_new))
        ) { _, w ->
            if (w == 0) Thread { if (DbManager.reload(this)) loadData() }.start() else pickDatabase()
        }.show()
    }

    private fun showAbout() {
        val extra = DbManager.db?.attribution()?.let { "\n\nDataset attribution:\n$it" } ?: ""
        val build = "\n\nInstalled: ${Updater.installedLabel()}" +
            BuildConfig.CI_SHA.take(7).let { if (it.isNotEmpty()) " ($it)" else "" }
        HcDialog(this).setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_text) + extra + build)
            .setPositiveButton(R.string.close, null).show()
    }

    // ---------------- planner (inline: start / destination / time / plan live in the top bar)
    /** "09:12 → 09:58 (46 min)". */
    private fun itinLine(it: Itinerary) =
        "${hm(it.departMinutes)} → ${hm(it.arriveMinutes)}  (${it.arriveMinutes - it.departMinutes} min)"

    private fun setNavState(st: NavState) {
        navState = st
        when (st) {
            NavState.INPUT -> { planOptions.clear(); currentItinerary = null }
            NavState.OPTIONS -> currentItinerary = planOptions.getOrNull(optIdx)
            NavState.SELECTED -> currentItinerary?.let { lastItinerary = it }
        }
        updateMapForMode(); refreshView()
    }

    /**
     * A modal "planning" dialog showing what the planner is doing; [work] runs in
     * the background and its result is handed to [done] unless cancelled.
     */
    private fun <T> withPlanning(title: String, work: ((String) -> Unit) -> T, done: (T) -> Unit) {
        val gen = ++planGen
        val d = resources.displayMetrics.density
        val status = TextView(this).apply { setTextColor(white); textSize = 14f; text = getString(R.string.nav_planning) + "…" }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; val p = (20 * d).toInt(); setPadding(p, p, p, p / 2)
            addView(status); addView(bar)
        }
        val dlg = HcDialog(this).setTitle(title).setView(box).setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> planGen++ }.create()
        dlg.show()
        Thread {
            val res = work { msg -> runOnUiThread { status.text = msg } }
            runOnUiThread {
                if (gen != planGen) return@runOnUiThread
                runCatching { dlg.dismiss() }
                done(res)
            }
        }.start()
    }

    private fun runPlan() {
        if (DbManager.db == null) { toast(getString(R.string.db_none)); return }
        val f = from; val t = to
        if (f == null || t == null) { toast(getString(R.string.planner_need)); return }
        val db = DbManager.db ?: return
        val depart = departWhen ?: LocalDateTime.now(Malta.ZONE)
        val debug = Settings.debug(this)
        withPlanning(getString(R.string.nav_planning), { prog ->
            if (debug) {
                val cmp = runCatching { Planner.compare(db, allStops, f, t, depart, prog) }.getOrDefault(emptyList())
                cmp.firstOrNull { it.engine == Engine.RAPTOR }?.itineraries ?: emptyList()
            } else runCatching { Planner.plan(db, allStops, f, t, depart, Settings.engine(this), prog) }.getOrDefault(emptyList())
        }) { res ->
            if (res.isEmpty()) { toast(getString(R.string.planner_none)); return@withPlanning }
            planOptions.clear(); planOptions.addAll(res.sortedWith(compareBy({ it.departMinutes }, { it.arriveMinutes })))
            optIdx = 0; pendingItinFit = true
            setNavState(NavState.OPTIONS)
        }
    }

    /**
     * Next / previous option; past the last (first) one, plan again from just after
     * the last departure (or an hour before the first) to find more.
     */
    private fun stepOption(step: Int) {
        val n = optIdx + step
        if (n in planOptions.indices) { optIdx = n; pendingItinFit = true; setNavState(NavState.OPTIONS); return }
        val f = from; val t = to; val db = DbManager.db ?: return
        if (f == null || t == null || planOptions.isEmpty()) return
        val date = planOptions.first().serviceDate
        val later = step > 0
        val at = if (later) Malta.at(date, planOptions.last().departMinutes + 1)
                 else Malta.at(date, planOptions.first().departMinutes - 60)
        withPlanning(getString(if (later) R.string.cd_next_option else R.string.cd_prev_option), { prog ->
            runCatching { Planner.plan(db, allStops, f, t, at, Settings.engine(this), prog) }.getOrDefault(emptyList())
        }) { res ->
            val known = planOptions.map { it.depart to it.arrive }.toSet()
            val fresh = res.filter { (it.depart to it.arrive) !in known }.filter {
                if (later) it.depart > planOptions.last().depart else it.depart < planOptions.first().depart
            }
            if (fresh.isEmpty()) { toast(getString(if (later) R.string.nav_no_later else R.string.nav_no_earlier)); return@withPlanning }
            val cur = planOptions.getOrNull(optIdx)
            planOptions.addAll(fresh); planOptions.sortWith(compareBy({ it.departMinutes }, { it.arriveMinutes }))
            optIdx = (planOptions.indexOf(cur) + step).coerceIn(0, planOptions.size - 1)
            pendingItinFit = true
            setNavState(NavState.OPTIONS)
        }
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
        val th = HighContrast.dialogTheme(this)
        DatePickerDialog(this, th, { _, y, mo, d ->
            TimePickerDialog(this, th, { _, h, mi ->
                onPicked(LocalDateTime.of(y, mo + 1, d, h, mi))
            }, now.hour, now.minute, true).let { HighContrast.dialog(it) }.show()
        }, now.year, now.monthValue - 1, now.dayOfMonth).let { HighContrast.dialog(it) }.show()
    }

    // ---------------- navigation
    private fun startNavigation(itin: Itinerary, simulate: Boolean) {
        if (!simulate && !ensureLocation()) { toast("Location permission needed"); return }
        lastItinerary = itin; currentItinerary = itin; navState = NavState.SELECTED
        NavHub.itinerary = itin
        NavHub.to = to ?: itin.legs.lastOrNull()?.let { l ->
            (l as? WalkLeg)?.let { Place(it.toName, it.toLat, it.toLon) }
        }
        if (NavHub.stops.isEmpty()) NavHub.stops = allStops.associateBy { it.number }
        NavHub.simulate = simulate
        NavHub.simPoints = if (simulate) buildSimPoints(itin) else emptyList()
        if (simulate) {
            toast(getString(R.string.sim_running))
            NavHub.simPoints.firstOrNull()?.let { map.setMyLocation(it[0], it[1], 5f, it[2].toFloat()); map.centerOnMe() }
        }
        startForegroundService(Intent(this, NavigationService::class.java))
        // interactive: the map follows you
        if (!simulate) map.centerOnMe()
        render()
    }

    /** Stop interactive navigation; the selected journey stays (you can go again or pick another). */
    private fun cancelNav() {
        stopService(Intent(this, NavigationService::class.java)); NavHub.clear()
        navState = if (currentItinerary != null) NavState.SELECTED else NavState.INPUT
        updateMapForMode(); refreshView(); render()
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
        val b = HcDialog(this).setTitle(R.string.consent_title).setMessage(R.string.consent_text)
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

    override fun onLocationChanged(l: Location) {
        myLat = l.latitude; myLon = l.longitude
        if (l.hasBearing() && l.speed > 3f) setCourse(l.bearing)   // moving: trust the course over the compass
        // POINT text about your own position: refresh once you have moved ~20 m
        if (mode == Mode.EXPLORE && sub == Sub.POINT && pointLat == null && viewKind == ViewKind.TEXT) {
            val at = pointTextAt
            if (at == null || Math.hypot((Geo.x(l.longitude) - Geo.x(at[1])).toDouble(),
                    (Geo.y(l.latitude) - Geo.y(at[0])).toDouble()) > 20.0) buildTextPanel()
        }
        map.setMyLocation(l.latitude, l.longitude, l.accuracy, if (l.hasBearing() && l.speed > 2f) l.bearing else null)
    }

    /** "350 m" / "1.2 km" from the current position, or null if it is not known yet. */
    private fun distanceFromMe(lat: Double, lon: Double): String? {
        val la = myLat ?: return null; val lo = myLon ?: return null
        val m = Math.hypot((Geo.x(lon) - Geo.x(lo)).toDouble(), (Geo.y(lat) - Geo.y(la)).toDouble())
        return if (m < 1000) "${Math.round(m / 10) * 10} m" else "%.1f km".format(java.util.Locale.US, m / 1000)
    }

    @Deprecated("needed for API < 29") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    // ---------------- bottom panel
    private fun render() {
        val n = NavHub.snapshot
        // re-planned while navigating: show the new journey
        NavHub.itinerary?.let { ni ->
            if (n.active && ni !== currentItinerary) {
                currentItinerary = ni; lastItinerary = ni
                if (mode == Mode.NAVIGATE) { updateMapForMode(); if (viewKind == ViewKind.TEXT) buildTextPanel() }
            }
        }
        // a message from navigation (e.g. after "the bus left without me"): show it once
        if (n.messageId > shownMsgId && n.message.isNotEmpty()) {
            shownMsgId = n.messageId
            HcDialog(this).setMessage(n.message).setPositiveButton(android.R.string.ok, null).show()
        }
        if (n.simulated && n.lat != null && n.lon != null) {
            myLat = n.lat; myLon = n.lon
            n.bearing?.let { setCourse(it) }
            map.setMyLocation(n.lat, n.lon, 5f, n.bearing)
        }
        if (n.active || n.arrived) {
            panel.visibility = View.VISIBLE
            status.text = if (n.arrived) "" else "${n.distanceM} m"
            nextStop.text = n.instruction; nextStop.visibility = View.VISIBLE
            destination.text = if (n.arrived) getString(R.string.nav_cancel) else ""
            destination.visibility = if (n.arrived) View.VISIBLE else View.GONE
            alert.text = if (n.pressStop) getString(R.string.press_stop) else n.detail
            alert.visibility = if (n.pressStop || n.detail.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            panel.visibility = View.GONE
        }
        if (::tabExplore.isInitialized) refreshTabs()
    }

    /**
     * A list row: one swatch box per line (each box shows that line's direction
     * colours — split when they differ) before two text lines (white / grey).
     */
    private fun colorRow(swatches: List<List<Int>>, line1: String, line2: String): View {
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(px(12f), px(10f), px(12f), px(10f))
        }
        for (sw in swatches.take(4)) row.addView(LineSwatch(this, sw).apply {
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
