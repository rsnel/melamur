package it.snel.melamur.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import it.snel.melamur.data.BusStop
import it.snel.melamur.data.Poi
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Offline vector map with pan, pinch zoom and double-tap zoom.
 *
 * "Follow" mode keeps the user's position in the centre. A pinch whose focus
 * is close to the screen centre zooms around the centre, so following is not
 * lost; a pinch elsewhere, or a pan, zooms/moves freely and ends follow mode.
 */
class MapView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    private val dp = resources.displayMetrics.density
    var data: MapData? = null
        set(v) { field = v; invalidate() }
    var stops: List<BusStop> = emptyList()
        set(v) { field = v; invalidate() }
    var routes: List<RouteGfx> = emptyList()
        set(v) { field = v; invalidate() }
    /** A planned-journey overlay leg: a path in projected (world) coords. */
    class ItinPath(val path: Path, val color: Int, val walk: Boolean)
    var itinPaths: List<ItinPath> = emptyList()
        set(v) { field = v; invalidate() }
    class PlaceLabel(val x: Float, val y: Float, val name: String, val rank: Int)
    class StreetLabel(val x: Float, val y: Float, val x2: Float, val y2: Float, val name: String)
    var places: List<PlaceLabel> = emptyList()
        set(v) { field = v; invalidate() }
    var streets: List<StreetLabel> = emptyList()
        set(v) { field = v; invalidate() }
    var pois: List<Poi> = emptyList()
        set(v) { field = v; invalidate() }
    var activeRouteId: String? = null
    var nextStopId: String? = null
    var destinationStopId: String? = null
    // journey endpoints (projected x,y), drawn with distinct start/finish symbols
    private var startPt: FloatArray? = null
    private var finishPt: FloatArray? = null
    /** Set the journey start/finish markers; each is [lat, lon] or null to clear. */
    fun setEndpoints(start: DoubleArray?, finish: DoubleArray?) {
        startPt = start?.let { floatArrayOf(Geo.x(it[1]), Geo.y(it[0])) }
        finishPt = finish?.let { floatArrayOf(Geo.x(it[1]), Geo.y(it[0])) }
        invalidate()
    }

    // a pin dropped at the currently selected search result
    private var searchPt: FloatArray? = null
    fun setSearchPin(lat: Double, lon: Double) { searchPt = floatArrayOf(Geo.x(lon), Geo.y(lat)); invalidate() }
    fun clearSearchPin() { if (searchPt != null) { searchPt = null; invalidate() } }
    var onStopTapped: ((BusStop) -> Unit)? = null
    /** Long-press on the map: reports the world position and the POI hit, if any. */
    var onMapLongPress: ((lat: Double, lon: Double, poi: Poi?) -> Unit)? = null
    var onFollowChanged: ((Boolean) -> Unit)? = null

    // view state: centre in projected metres, scale in px per metre
    private var cx = Geo.x(14.43); private var cy = Geo.y(35.90); private var scale = 0.035f
    var follow = false
        private set(v) { if (field != v) { field = v; onFollowChanged?.invoke(v) } }
    private var me: FloatArray? = null // x, y, accuracy, bearing(NaN if unknown)

    fun setMyLocation(lat: Double, lon: Double, acc: Float, bearing: Float?) {
        me = floatArrayOf(Geo.x(lon), Geo.y(lat), acc, bearing ?: Float.NaN)
        if (follow) { cx = me!![0]; cy = me!![1] }
        invalidate()
    }

    /** Centre on a specific stop (e.g. from search) and zoom in; leaves follow mode. */
    fun centerOn(s: BusStop) {
        cx = s.x; cy = s.y; if (scale < 3f) scale = 6f; follow = false; invalidate()
    }

    // current view, for saving/restoring a startup position
    fun viewLat() = Geo.lat(cy).toDouble()
    fun viewLon() = Geo.lon(cx).toDouble()
    fun viewScale() = scale
    fun setView(lat: Double, lon: Double, sc: Float) {
        cx = Geo.x(lon); cy = Geo.y(lat); scale = sc; follow = false; invalidate()
    }

    /** Zoom by [f] around the screen centre (keeps follow mode). */
    fun zoomBy(f: Float) = zoomAt(width / 2f, height / 2f, f)

    /** Centre on the user; zoom in to street level if we are zoomed far out. */
    fun centerOnMe(): Boolean {
        val m = me ?: return false
        cx = m[0]; cy = m[1]; if (scale < 1f) scale = 2f
        follow = true; invalidate(); return true
    }

    // ---------------- gestures
    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nearCentre = hypot(d.focusX - width / 2f, d.focusY - height / 2f) < min(width, height) * 0.3f
            if (follow && nearCentre) zoomAt(width / 2f, height / 2f, d.scaleFactor)
            else { follow = false; zoomAt(d.focusX, d.focusY, d.scaleFactor) }
            return true
        }
    })
    private val gestureDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDet.isInProgress || e2.pointerCount > 1) return true
            follow = false
            cx += dx / scale; cy -= dy / scale; invalidate(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (follow) zoomAt(width / 2f, height / 2f, 2f) else zoomAt(e.x, e.y, 2f); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val wx = cx + (e.x - width / 2f) / scale; val wy = cy - (e.y - height / 2f) / scale
            val r = 24 * dp / scale
            stops.minByOrNull { hypot(it.x - wx, it.y - wy) }
                ?.takeIf { hypot(it.x - wx, it.y - wy) < r && scale > MIN_STOP_SCALE }
                ?.let { onStopTapped?.invoke(it) }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val wx = cx + (e.x - width / 2f) / scale; val wy = cy - (e.y - height / 2f) / scale
            val r = 24 * dp / scale
            val poi = pois.minByOrNull { hypot(it.x - wx, it.y - wy) }
                ?.takeIf { hypot(it.x - wx, it.y - wy) < r }
            onMapLongPress?.invoke(Geo.lat(wy).toDouble(), Geo.lon(wx).toDouble(), poi)
        }
    })

    private fun zoomAt(fx: Float, fy: Float, f: Float) {
        val ns = (scale * f).coerceIn(0.01f, 25f)
        val wx = cx + (fx - width / 2f) / scale; val wy = cy - (fy - height / 2f) / scale
        cx = wx - (fx - width / 2f) / ns; cy = wy + (fy - height / 2f) / ns
        scale = ns; invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e); gestureDet.onTouchEvent(e); return true
    }

    // ---------------- drawing
    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.FILL }
    private fun stroke(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    // normal light map: blue water, grey-green land, pastel roads — so the red
    // stop rings and the coloured bus lines stay clearly visible on top
    private val sea = Color.rgb(160, 200, 224)
    private class Layer(val style: Int, val paint: Paint, val widthPx: Float, val widthM: Float, val minScale: Float)
    private val layers = listOf(
        Layer(MapData.LAND, fill(Color.rgb(223, 227, 214)), 0f, 0f, 0f),
        Layer(MapData.FARM, fill(Color.rgb(231, 229, 210)), 0f, 0f, 0.05f),
        Layer(MapData.GREEN, fill(Color.rgb(200, 224, 186)), 0f, 0f, 0.05f),
        Layer(MapData.URBAN, fill(Color.rgb(228, 225, 219)), 0f, 0f, 0f),
        Layer(MapData.WATER, fill(sea), 0f, 0f, 0.05f),
        Layer(MapData.BUILDING, fill(Color.rgb(214, 210, 199)), 0f, 0f, 0.6f),
        Layer(MapData.FOOT, stroke(Color.rgb(205, 196, 180)), 0.8f, 0f, 1.0f),
        Layer(MapData.SERVICE, stroke(Color.rgb(250, 247, 240)), 1f, 3f, 0.5f),
        Layer(MapData.MINOR, stroke(Color.rgb(255, 255, 255)), 1f, 5f, 0.15f),
        Layer(MapData.TERTIARY, stroke(Color.rgb(251, 243, 226)), 1.2f, 7f, 0.06f),
        Layer(MapData.SECONDARY, stroke(Color.rgb(247, 233, 201)), 1.4f, 8f, 0.02f),
        Layer(MapData.PRIMARY, stroke(Color.rgb(243, 223, 173)), 1.6f, 9f, 0f),
        Layer(MapData.TRUNK, stroke(Color.rgb(239, 215, 154)), 2f, 11f, 0f),
    )
    private val routePaint = stroke(Color.WHITE)
    // planned journey overlay: solid for the used bus segment, dotted for walking
    private val itinBusPaint = stroke(Color.WHITE).apply { strokeWidth = 6 * dp }
    private val itinWalkPaint = stroke(Color.rgb(60, 60, 60)).apply {
        strokeWidth = 4 * dp; pathEffect = DashPathEffect(floatArrayOf(2 * dp, 8 * dp), 0f)
    }
    private val itinPathScreen = Path()
    private val arrowPaint = fill(Color.WHITE)
    private val stopFill = fill(Color.WHITE)
    private val stopRing = stroke(Color.rgb(238, 0, 0)).apply { strokeWidth = 2 * dp }
    // white text with a strong dark halo for high contrast on the light map
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12 * dp; isFakeBoldText = true; setShadowLayer(4 * dp, 0f, 0f, Color.BLACK)
    }
    private val placePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; setShadowLayer(5 * dp, 0f, 0f, Color.BLACK)
    }
    private val streetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11 * dp; textAlign = Paint.Align.CENTER
        setShadowLayer(4 * dp, 0f, 0f, Color.BLACK)
    }
    private val placeDot = fill(Color.WHITE)
    private val poiFill = fill(Color.rgb(255, 193, 7))
    private val poiStroke = stroke(Color.WHITE).apply { strokeWidth = 1.5f * dp }
    private val poiLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7); textSize = 12 * dp; setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
    }
    private val diamond = Path()
    // journey endpoint markers: green teardrop pin (start) and chequered flag (finish)
    private val startFill = fill(Color.rgb(46, 160, 67))
    private val startRing = stroke(Color.WHITE).apply { strokeWidth = 2.5f * dp }
    private val flagPole = stroke(Color.rgb(20, 20, 20)).apply { strokeWidth = 2f * dp }
    private val flagOutline = stroke(Color.rgb(20, 20, 20)).apply { strokeWidth = 1.5f * dp }
    private val checkerDark = fill(Color.rgb(20, 20, 20))
    private val checkerLight = fill(Color.WHITE)
    // selected-search-result pin: a blue teardrop, distinct from start (green) / POI (amber)
    private val searchFill = fill(Color.rgb(33, 118, 243))
    private val searchRing = stroke(Color.WHITE).apply { strokeWidth = 2.5f * dp }
    private val pin = Path()
    // scale bar (bottom-left): dark line on a white halo, readable on the light map
    private val scaleHalo = stroke(Color.WHITE).apply { strokeWidth = 5 * dp; strokeCap = Paint.Cap.SQUARE }
    private val scaleLine = stroke(Color.rgb(30, 30, 30)).apply { strokeWidth = 2 * dp; strokeCap = Paint.Cap.SQUARE }
    private val scaleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 30, 30); textSize = 12 * dp; isFakeBoldText = true
        setShadowLayer(3 * dp, 0f, 0f, Color.WHITE)
    }
    private val scalePath = Path()
    private val occupied = HashSet<Long>() // label collision grid, rebuilt per frame
    private val stopLabelCells = HashSet<String>() // stops whose name won a slot this frame
    private val meFill = fill(Color.WHITE)
    private val meRing = stroke(Color.rgb(238, 0, 0)).apply { strokeWidth = 4 * dp }
    private val meAcc = fill(Color.argb(50, 238, 0, 0))
    private val matrix = Matrix()
    private val pt = FloatArray(2)
    private val arrow = Path()

    override fun onDraw(c: Canvas) {
        c.drawColor(sea)
        val hw = width / 2f / scale; val hh = height / 2f / scale
        val vx0 = cx - hw; val vx1 = cx + hw; val vy0 = cy - hh; val vy1 = cy + hh
        matrix.reset()
        matrix.setTranslate(-cx, -cy); matrix.postScale(scale, -scale); matrix.postTranslate(width / 2f, height / 2f)
        c.save(); c.concat(matrix)
        data?.let { d ->
            for (l in layers) {
                if (scale < l.minScale) continue
                if (l.paint.style == Paint.Style.STROKE)
                    l.paint.strokeWidth = maxOf(l.widthPx * dp, l.widthM * scale) / scale
                for (b in d.buckets[l.style] ?: continue)
                    if (b.maxX >= vx0 && b.minX <= vx1 && b.maxY >= vy0 && b.minY <= vy1) c.drawPath(b.path, l.paint)
            }
        }
        for (r in routes) {
            val active = activeRouteId == null || r.route.id == activeRouteId
            for (dl in r.lines) {
                routePaint.color = dl.color
                routePaint.alpha = if (active) 255 else 90
                routePaint.strokeWidth = (if (active) 4f else 2.5f) * dp / scale
                c.drawPath(dl.path, routePaint)
            }
        }
        c.restore()

        // direction arrows along each route line (screen space)
        for (r in routes) for (dl in r.lines) {
            arrowPaint.color = dl.color
            val a = dl.arrows
            var i = 0
            while (i < a.size) {
                pt[0] = a[i]; pt[1] = a[i + 1]; matrix.mapPoints(pt)
                if (pt[0] in 0f..width.toFloat() && pt[1] in 0f..height.toFloat()) drawArrow(c, pt[0], pt[1], a[i + 2], 7 * dp)
                i += 3
            }
        }
        // planned-journey overlay (screen space so stroke width and dots stay constant)
        for (ip in itinPaths) {
            itinPathScreen.set(ip.path); itinPathScreen.transform(matrix)
            if (ip.walk) c.drawPath(itinPathScreen, itinWalkPaint)
            else { itinBusPaint.color = ip.color; c.drawPath(itinPathScreen, itinBusPaint) }
        }
        // ---- text labels (screen space, collision-avoided)
        occupied.clear()
        // Stop names take priority: reserve their label cells first, so street and
        // place names only fill the grid cells left over.
        stopLabelCells.clear()
        val showStopLabels = scale > STOP_LABEL_SCALE
        if (scale > MIN_STOP_SCALE && showStopLabels) {
            for (s in stops) {
                if (s.x < vx0 || s.x > vx1 || s.y < vy0 || s.y > vy1) continue
                pt[0] = s.x; pt[1] = s.y; matrix.mapPoints(pt)
                if (pt[0] < 0 || pt[0] > width || pt[1] < 0 || pt[1] > height) continue
                val r = (if (s.id == nextStopId || s.id == destinationStopId) 7 else 4) * dp
                if (reserve(pt[0] + r + 3 * dp, pt[1] + 4 * dp)) stopLabelCells.add(s.id)
            }
        }
        if (scale > STREET_LABEL_SCALE) {
            streetPaint.textSize = 11 * dp
            for (s in streets) {
                if (s.x < vx0 || s.x > vx1 || s.y < vy0 || s.y > vy1) continue
                pt[0] = s.x; pt[1] = s.y; matrix.mapPoints(pt)
                val sx = pt[0]; val sy = pt[1]
                if (sx < 0 || sx > width || sy < 0 || sy > height) continue
                if (!reserve(sx, sy)) continue
                pt[0] = s.x2; pt[1] = s.y2; matrix.mapPoints(pt)
                var ang = Math.toDegrees(atan2((pt[1] - sy).toDouble(), (pt[0] - sx).toDouble())).toFloat()
                if (ang > 90f || ang < -90f) ang += 180f
                c.save(); c.translate(sx, sy); c.rotate(ang)
                c.drawText(s.name, 0f, -2 * dp, streetPaint); c.restore()
            }
        }
        for (p in places) {
            val minS = PLACE_MIN_SCALE[p.rank]
            if (scale < minS) continue
            if (p.x < vx0 || p.x > vx1 || p.y < vy0 || p.y > vy1) continue
            pt[0] = p.x; pt[1] = p.y; matrix.mapPoints(pt)
            if (pt[0] < 0 || pt[0] > width || pt[1] < 0 || pt[1] > height) continue
            if (!reserve(pt[0], pt[1])) continue
            placePaint.textSize = PLACE_TEXT[p.rank] * dp
            placePaint.isFakeBoldText = p.rank <= 1
            if (p.rank <= 2) c.drawCircle(pt[0], pt[1], 2.5f * dp, placeDot)
            c.drawText(p.name, pt[0], pt[1] - 5 * dp, placePaint)
        }

        if (scale > MIN_STOP_SCALE) {
            for (s in stops) {
                if (s.x < vx0 || s.x > vx1 || s.y < vy0 || s.y > vy1) continue
                pt[0] = s.x; pt[1] = s.y; matrix.mapPoints(pt)
                val hl = s.id == nextStopId || s.id == destinationStopId
                val r = (if (hl) 7 else 4) * dp
                s.heading?.let { h ->  // arrow pointing in the served direction of bus travel
                    val br = Math.toRadians(h.toDouble())
                    val len = 3.4f * r
                    val tx = pt[0] + len * sin(br).toFloat(); val ty = pt[1] - len * cos(br).toFloat()
                    c.drawLine(pt[0], pt[1], tx, ty, stopRing)
                    arrowPaint.color = Color.rgb(238, 0, 0)
                    drawArrow(c, tx, ty, h, (if (hl) 7 else 5) * dp)
                }
                stopFill.color = if (s.id == destinationStopId) Color.rgb(238, 0, 0) else Color.WHITE
                c.drawCircle(pt[0], pt[1], r, stopFill); c.drawCircle(pt[0], pt[1], r, stopRing)
                if (hl || s.id in stopLabelCells) c.drawText(s.name, pt[0] + r + 3 * dp, pt[1] + 4 * dp, label)
            }
        }
        for (p in pois) {
            if (p.x < vx0 || p.x > vx1 || p.y < vy0 || p.y > vy1) continue
            pt[0] = p.x; pt[1] = p.y; matrix.mapPoints(pt)
            val s = 6 * dp
            diamond.reset(); diamond.moveTo(pt[0], pt[1] - s); diamond.lineTo(pt[0] + s, pt[1])
            diamond.lineTo(pt[0], pt[1] + s); diamond.lineTo(pt[0] - s, pt[1]); diamond.close()
            c.drawPath(diamond, poiFill); c.drawPath(diamond, poiStroke)
            if (scale > 1.5f) c.drawText(p.name, pt[0] + s + 3 * dp, pt[1] + 4 * dp, poiLabel)
        }
        searchPt?.let { s -> pt[0] = s[0]; pt[1] = s[1]; matrix.mapPoints(pt); drawPin(c, pt[0], pt[1], searchFill, searchRing, 10 * dp) }
        startPt?.let { s -> pt[0] = s[0]; pt[1] = s[1]; matrix.mapPoints(pt); drawStartPin(c, pt[0], pt[1]) }
        finishPt?.let { f -> pt[0] = f[0]; pt[1] = f[1]; matrix.mapPoints(pt); drawFinishFlag(c, pt[0], pt[1]) }
        me?.let { m ->
            pt[0] = m[0]; pt[1] = m[1]; matrix.mapPoints(pt)
            c.drawCircle(pt[0], pt[1], m[2] * scale, meAcc)
            if (!m[3].isNaN()) { meFill.color = Color.rgb(238, 0, 0); drawArrow(c, pt[0], pt[1], m[3], 16 * dp) }
            meFill.color = Color.WHITE
            c.drawCircle(pt[0], pt[1], 8 * dp, meFill); c.drawCircle(pt[0], pt[1], 8 * dp, meRing)
        }
        drawScaleBar(c)
    }

    /** Scale bar: the largest 1/2/5 x 10^n metres that fits in ~110 dp. */
    private fun drawScaleBar(c: Canvas) {
        val maxM = 110 * dp / scale
        val p = Math.pow(10.0, Math.floor(Math.log10(maxM.toDouble())))
        val m = doubleArrayOf(5 * p, 2 * p, p).first { it <= maxM }
        val len = (m * scale).toFloat()
        val x0 = 14 * dp; val y = height - 16 * dp; val tick = 6 * dp
        scalePath.reset()
        scalePath.moveTo(x0, y - tick); scalePath.lineTo(x0, y)
        scalePath.lineTo(x0 + len, y); scalePath.lineTo(x0 + len, y - tick)
        c.drawPath(scalePath, scaleHalo); c.drawPath(scalePath, scaleLine)
        val label = if (m >= 1000) "${(m / 1000).toInt()} km" else "${m.toInt()} m"
        c.drawText(label, x0 + 4 * dp, y - 5 * dp, scaleText)
    }

    /** Teardrop pin with a white centre; its tip sits exactly on (x, y). */
    private fun drawPin(c: Canvas, x: Float, y: Float, fillP: Paint, ringP: Paint, r: Float) {
        val cyc = y - 1.8f * r
        pin.reset()
        pin.addCircle(x, cyc, r, Path.Direction.CW)
        pin.moveTo(x - r * 0.62f, cyc + r * 0.6f); pin.lineTo(x, y); pin.lineTo(x + r * 0.62f, cyc + r * 0.6f); pin.close()
        c.drawPath(pin, fillP); c.drawPath(pin, ringP)
        c.drawCircle(x, cyc, r * 0.4f, checkerLight)
    }
    private fun drawStartPin(c: Canvas, x: Float, y: Float) = drawPin(c, x, y, startFill, startRing, 9 * dp)

    /** Chequered finish flag on a pole whose foot sits on (x, y). */
    private fun drawFinishFlag(c: Canvas, x: Float, y: Float) {
        val cell = 5 * dp; val cols = 3; val rows = 2
        val w = cell * cols; val top = y - 20 * dp
        c.drawLine(x, y, x, top, flagPole)
        for (row in 0 until rows) for (col in 0 until cols) {
            val left = x + col * cell; val t = top + row * cell
            c.drawRect(left, t, left + cell, t + cell, if ((row + col) % 2 == 0) checkerDark else checkerLight)
        }
        c.drawRect(x, top, x + w, top + rows * cell, flagOutline)
    }

    private fun drawArrow(c: Canvas, x: Float, y: Float, bearing: Float, size: Float) {
        c.save(); c.translate(x, y); c.rotate(bearing)
        arrow.reset(); arrow.moveTo(0f, -size); arrow.lineTo(size * 0.7f, size * 0.6f)
        arrow.lineTo(0f, size * 0.2f); arrow.lineTo(-size * 0.7f, size * 0.6f); arrow.close()
        c.drawPath(arrow, if (size > 10 * dp) meFill else arrowPaint)
        c.restore()
    }

    /** Claim a label slot on a coarse grid; false if the cell is already taken. */
    private fun reserve(x: Float, y: Float): Boolean {
        val gx = (x / (72 * dp)).toInt(); val gy = (y / (18 * dp)).toInt()
        return occupied.add(gx.toLong() * 100000L + gy)
    }

    companion object {
        const val MIN_STOP_SCALE = 0.25f
        // stop names show from this zoom (lowered so they appear earlier); streets a bit later
        private const val STOP_LABEL_SCALE = 1.2f
        private const val STREET_LABEL_SCALE = 2.0f
        // indexed by place rank 0..4 (capital/city, town, village/suburb, hamlet, locality)
        private val PLACE_MIN_SCALE = floatArrayOf(0f, 0.02f, 0.12f, 0.5f, 1.5f)
        private val PLACE_TEXT = floatArrayOf(17f, 15f, 13f, 12f, 11f)
    }
}
