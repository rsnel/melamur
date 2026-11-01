package it.snel.melamur.map

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import it.snel.melamur.data.BusStop
import it.snel.melamur.data.Poi
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
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
        set(v) { field = v; clusterCache.clear(); invalidate() }
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
    /** Named points people look for (attractions, hotels, churches, supermarkets, ...). */
    var landmarks: List<PlaceLabel> = emptyList()
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
    /** A tap on the map that hit no stop, chip or callout. */
    var onMapTapped: ((lat: Double, lon: Double) -> Unit)? = null
    /** A tap on a line chip in the stop callout. */
    var onChipTapped: ((code: String) -> Unit)? = null
    /** Explore stop mode: the selected stop is drawn highlighted with its name. */
    var selectedStopId: String? = null
        set(v) { field = v; invalidate() }
    /** One-line debug overlay at the top of the map (null = none). */
    var debugText: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    /** A line number chip in the stop callout, in the line's colours. */
    class Chip(val code: String, val color: Int, val textColor: Int)
    private var calloutX = 0f
    private var calloutY = 0f
    private var calloutChips: List<Chip>? = null
    private class ChipHit(val code: String, val r: android.graphics.RectF)
    private val chipHits = ArrayList<ChipHit>()
    private val calloutRect = android.graphics.RectF()
    /** Show the lines serving the stop at world position (x, y) as chips above it. */
    fun setStopCallout(x: Float, y: Float, chips: List<Chip>) {
        calloutX = x; calloutY = y; calloutChips = chips; calloutSel = null; calloutLines = emptyList(); invalidate()
    }
    /** Stop callout, one line picked: [sel] is ringed and [lines] (destination, times) shown under the chips. */
    fun setCalloutDetail(sel: String?, lines: List<String>) { calloutSel = sel; calloutLines = lines; invalidate() }
    private var calloutSel: String? = null
    private var calloutLines: List<String> = emptyList()
    private val calloutTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 20, 20); textSize = 14 * dp; textAlign = Paint.Align.CENTER
    }
    private val chipSelRing = stroke(Color.rgb(20, 20, 20)).apply { strokeWidth = 3 * dp }
    fun clearStopCallout() { if (calloutChips != null) { calloutChips = null; chipHits.clear(); calloutRect.setEmpty(); invalidate() } }
    /** Long-press on the map: reports the world position and the POI hit, if any. */
    var onMapLongPress: ((lat: Double, lon: Double, poi: Poi?) -> Unit)? = null
    /** Tracking changed: [follow] = the map follows you; [headingUp] = and turns with your heading. */
    var onTrackChanged: ((follow: Boolean, headingUp: Boolean) -> Unit)? = null
    /** The map's bearing changed (degrees; 0 = north up), e.g. to turn a compass button. */
    var onBearingChanged: ((Float) -> Unit)? = null

    // view state: centre in projected metres, scale in px per metre, and the
    // bearing: the compass direction that points up the screen (0 = north up)
    private var cx = Geo.x(14.43); private var cy = Geo.y(35.90); private var scale = 0.035f
    private var rot = 0f
        set(v) { val n = norm360(v); if (n != field) { field = n; onBearingChanged?.invoke(n) } }
    val bearing get() = rot

    var follow = false
        private set(v) {
            if (field == v) return
            field = v
            if (!v) { headingUp = false; followOff = 0f }
            onTrackChanged?.invoke(field, headingUp)
        }
    var headingUp = false
        private set(v) { if (field != v) { field = v; onTrackChanged?.invoke(follow, v) } }
    // while following, your position sits this fraction of the height below the
    // centre (heading-up shows more of what lies ahead, as navigation apps do)
    private var followOff = 0f
    private var me: FloatArray? = null // x, y, accuracy, course (NaN if unknown)
    private var deviceHeading = Float.NaN

    // ---------------- screen <-> world with rotation
    // world (east, north) -> screen: rotate by +bearing (counter-clockwise), then
    // scale, flip y and move to the view centre; the inverse for touches.
    private val tmp = FloatArray(2)
    private fun cosR() = cos(Math.toRadians(rot.toDouble())).toFloat()
    private fun sinR() = sin(Math.toRadians(rot.toDouble())).toFloat()

    /** World point under screen point (px, py), written to [out]. */
    private fun toWorld(px: Float, py: Float, out: FloatArray = tmp): FloatArray {
        val u = (px - width / 2f) / scale; val v = -(py - height / 2f) / scale
        val c = cosR(); val s = sinR()
        out[0] = cx + u * c + v * s; out[1] = cy - u * s + v * c
        return out
    }

    /** Move the centre so world point (wx, wy) appears at screen point (px, py). */
    private fun anchor(px: Float, py: Float, wx: Float, wy: Float) {
        val u = (px - width / 2f) / scale; val v = -(py - height / 2f) / scale
        val c = cosR(); val s = sinR()
        cx = wx - (u * c + v * s); cy = wy - (-u * s + v * c)
    }

    /** Move the map with a screen-space drag of (dx, dy) px (GestureDetector's distance). */
    private fun panBy(dx: Float, dy: Float) {
        val u = dx / scale; val v = -dy / scale
        val c = cosR(); val s = sinR()
        cx += u * c + v * s; cy += -u * s + v * c
    }

    private fun followY() = height / 2f + followOff * height
    private fun anchorMe() { me?.let { anchor(width / 2f, followY(), it[0], it[1]) } }
    /** Zoom/rotate focus: your position while following, else the given point. */
    private fun focusX(x: Float) = if (follow) width / 2f else x
    private fun focusY(y: Float) = if (follow) followY() else y

    fun setMyLocation(lat: Double, lon: Double, acc: Float, bearing: Float?) {
        me = floatArrayOf(Geo.x(lon), Geo.y(lat), acc, bearing ?: Float.NaN)
        if (follow && !animating()) anchorMe()
        invalidate()
    }

    /** Heading of the phone / direction of travel (degrees from true north); heading-up turns with it. */
    fun setDeviceHeading(deg: Float) {
        deviceHeading = deg
        if (headingUp && !animating() && !rotating) { rot = deg; anchorMe() }
        invalidate()
    }

    // ---------------- motion (Material / Google Maps style): kinetic panning,
    // and animated zoom, camera moves and turns with Material's standard easing
    private val scroller = OverScroller(ctx)
    private var flingX = 0; private var flingY = 0
    private var cam: ValueAnimator? = null
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)

    private fun animating() = cam?.isRunning == true
    private fun stopMotion() { scroller.forceFinished(true); cam?.cancel(); cam = null }

    private fun ValueAnimator.whenDone(onDone: (() -> Unit)?) {
        if (onDone != null) addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(a: Animator) { cancelled = true }
            override fun onAnimationEnd(a: Animator) { if (!cancelled) onDone() }
        })
    }

    /** Smoothly zoom by [f], keeping the map point under (fx, fy) in place. */
    private fun animateZoomAt(fx: Float, fy: Float, f: Float) {
        stopMotion()
        val s0 = scale; val s1 = (scale * f).coerceIn(MIN_SCALE, MAX_SCALE)
        val w = toWorld(fx, fy, FloatArray(2))
        cam = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300; interpolator = easing
            addUpdateListener { a ->
                scale = (s0 * Math.pow((s1 / s0).toDouble(), (a.animatedValue as Float).toDouble())).toFloat()
                anchor(fx, fy, w[0], w[1]); invalidate()
            }
            start()
        }
    }

    /** Smoothly move the camera to centre (tx, ty) at scale [ts]; [onDone] runs unless interrupted. */
    private fun animateCamera(tx: Float, ty: Float, ts: Float, onDone: (() -> Unit)? = null) {
        stopMotion()
        val x0 = cx; val y0 = cy; val s0 = scale; val s1 = ts.coerceIn(MIN_SCALE, MAX_SCALE)
        cam = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450; interpolator = easing
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                scale = (s0 * Math.pow((s1 / s0).toDouble(), t.toDouble())).toFloat()
                cx = x0 + (tx - x0) * t; cy = y0 + (ty - y0) * t; invalidate()
            }
            whenDone(onDone)
            start()
        }
    }

    /** Smoothly turn to bearing [target] (the short way round), moving your position to [endOff]. */
    private fun animateBearing(target: Float, endOff: Float, onDone: (() -> Unit)? = null) {
        stopMotion()
        val r0 = rot; val d = angleDiff(target, r0); val o0 = followOff
        cam = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350; interpolator = easing
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                rot = r0 + d * t   // turns around the view centre ...
                if (follow) { followOff = o0 + (endOff - o0) * t; anchorMe() }   // ... or around you
                invalidate()
            }
            whenDone(onDone)
            start()
        }
    }

    /** Compass button: turn back to north up (keeps following; leaves heading-up). */
    fun resetNorth() { headingUp = false; animateBearing(0f, 0f) }

    /** Heading-up on (only while following) or off (back to north up). */
    fun setHeadingUpMode(on: Boolean) {
        if (on) {
            if (!follow) return
            headingUp = true
            animateBearing(if (deviceHeading.isNaN()) rot else deviceHeading, HEADING_OFFSET)
        } else { headingUp = false; animateBearing(0f, 0f) }
    }

    /** Centre on a specific stop (e.g. from search) and zoom in; leaves follow mode. */
    fun centerOn(s: BusStop) {
        follow = false
        animateCamera(s.x, s.y, if (scale < 3f) 6f else scale)
    }

    // current view, for saving/restoring a startup position
    fun viewLat() = Geo.lat(cy).toDouble()
    fun viewLon() = Geo.lon(cx).toDouble()
    fun viewScale() = scale
    fun setView(lat: Double, lon: Double, sc: Float) {
        cx = Geo.x(lon); cy = Geo.y(lat); scale = sc; follow = false; invalidate()
    }

    /**
     * Centre and zoom so the world box (x0,y0)-(x1,y1) fits inside the view
     * minus the given screen paddings (px), e.g. to keep it clear of overlay
     * buttons. Keeps the current bearing; leaves follow mode.
     */
    fun fitBounds(x0: Float, y0: Float, x1: Float, y1: Float, padL: Float, padT: Float, padR: Float, padB: Float,
                  minS: Float = MIN_SCALE, maxS: Float = MAX_SCALE) {
        if (width == 0 || height == 0) { post { fitBounds(x0, y0, x1, y1, padL, padT, padR, padB, minS, maxS) }; return }
        val aw = (width - padL - padR).coerceAtLeast(1f); val ah = (height - padT - padB).coerceAtLeast(1f)
        // the box's extent as seen on the (possibly rotated) screen
        val mx = (x0 + x1) / 2f; val my = (y0 + y1) / 2f
        val c = cosR(); val s = sinR()
        var bw = 1f; var bh = 1f
        for ((px, py) in listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) {
            val dx = px - mx; val dy = py - my
            bw = maxOf(bw, 2 * kotlin.math.abs(dx * c - dy * s)); bh = maxOf(bh, 2 * kotlin.math.abs(dx * s + dy * c))
        }
        val ts = min(aw / bw, ah / bh).coerceIn(minS, maxS).coerceIn(MIN_SCALE, MAX_SCALE)
        // put the box centre at the centre of the free area
        val u = (padL + aw / 2f - width / 2f) / ts; val v = -(padT + ah / 2f - height / 2f) / ts
        follow = false
        animateCamera(mx - (u * c + v * s), my - (-u * s + v * c), ts)
    }

    /** Called after every frame (the camera may have moved); keep it cheap. */
    var onCameraDrawn: (() -> Unit)? = null

    /**
     * Whether the view roughly shows what [fitBounds] would for this box: the zoom
     * within a factor of about 1.5 of it, and the box's centre near the centre of
     * the free area.
     */
    fun isApproxFit(x0: Float, y0: Float, x1: Float, y1: Float, padL: Float, padT: Float, padR: Float, padB: Float): Boolean {
        if (width == 0 || height == 0) return false
        val aw = (width - padL - padR).coerceAtLeast(1f); val ah = (height - padT - padB).coerceAtLeast(1f)
        val mx = (x0 + x1) / 2f; val my = (y0 + y1) / 2f
        val c = cosR(); val s = sinR()
        var bw = 1f; var bh = 1f
        for ((px, py) in listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) {
            val dx = px - mx; val dy = py - my
            bw = maxOf(bw, 2 * kotlin.math.abs(dx * c - dy * s)); bh = maxOf(bh, 2 * kotlin.math.abs(dx * s + dy * c))
        }
        val ts = min(aw / bw, ah / bh).coerceIn(MIN_SCALE, MAX_SCALE)
        if (scale > ts * 1.3f || scale < ts / 1.6f) return false
        pt[0] = mx; pt[1] = my; matrix.mapPoints(pt)
        val fx = padL + aw / 2f; val fy = padT + ah / 2f
        return kotlin.math.abs(pt[0] - fx) < aw * 0.2f && kotlin.math.abs(pt[1] - fy) < ah * 0.2f
    }

    /** Zoom by [f] around the screen centre (or you, while following), animated. */
    fun zoomBy(f: Float) = animateZoomAt(width / 2f, focusY(height / 2f), f)

    /** Glide to the user and follow; zoom in to street level if we are zoomed far out. */
    fun centerOnMe(): Boolean {
        val m = me ?: return false
        animateCamera(m[0], m[1], if (scale < 1f) 2f else scale) {
            follow = true
            anchorMe(); invalidate()   // catch up with fixes during the glide
        }
        return true
    }

    // ---------------- gestures (as Google Maps): drag + fling to pan, pinch,
    // twist to rotate, double-tap to zoom in, double-tap-and-drag for one-finger
    // zoom (the scale detector's "quick scale"), two-finger tap to zoom out
    private var scaledInGesture = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var twoTapOk = false
    private var twoTapStart = 0L
    private val twoTapPts = FloatArray(4)
    // rotation only starts after a deliberate twist, so pinching does not turn the map
    private var rotating = false
    private var rotInit = false
    private var rotLast = 0f
    private var rotAccum = 0f

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            scaledInGesture = true
            val nearAnchor = hypot(d.focusX - width / 2f, d.focusY - followY()) < min(width, height) * 0.3f
            if (follow && nearAnchor) zoomAt(width / 2f, followY(), d.scaleFactor)
            else { follow = false; zoomAt(d.focusX, d.focusY, d.scaleFactor) }
            return true
        }
    }).apply { isQuickScaleEnabled = true }
    private val gestureDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDet.isInProgress || e2.pointerCount > 1) return true
            follow = false
            panBy(dx, dy); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaledInGesture || rotating || e2.pointerCount > 1) return false
            follow = false
            flingX = 0; flingY = 0
            scroller.fling(0, 0, -vx.toInt(), -vy.toInt(),
                Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
            postInvalidateOnAnimation(); return true
        }
        // zoom in on release of a plain double-tap; if the second tap was dragged,
        // the scale detector's quick scale already zoomed, so do nothing extra
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && !scaledInGesture)
                animateZoomAt(focusX(e.x), focusY(e.y), 2f)
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // the stop callout is on top: a chip selects its line; the rest of the panel does nothing
            chipHits.firstOrNull { it.r.contains(e.x, e.y) }?.let { onChipTapped?.invoke(it.code); return true }
            if (calloutRect.contains(e.x, e.y)) return true
            val w = toWorld(e.x, e.y); val wx = w[0]; val wy = w[1]
            val r = 24 * dp / scale
            if (scale <= MIN_STOP_SCALE) {
                clusterHit(e.x, e.y)?.let { zoomToCluster(it); return true }
            }
            val stop = stops.minByOrNull { hypot(it.x - wx, it.y - wy) }
                ?.takeIf { hypot(it.x - wx, it.y - wy) < r && scale > MIN_STOP_SCALE }
            if (stop != null) onStopTapped?.invoke(stop)
            else onMapTapped?.invoke(Geo.lat(wy).toDouble(), Geo.lon(wx).toDouble())
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val w = toWorld(e.x, e.y); val wx = w[0]; val wy = w[1]
            val r = 24 * dp / scale
            val poi = pois.minByOrNull { hypot(it.x - wx, it.y - wy) }
                ?.takeIf { hypot(it.x - wx, it.y - wy) < r }
            onMapLongPress?.invoke(Geo.lat(wy).toDouble(), Geo.lon(wx).toDouble(), poi)
        }
    })

    private fun zoomAt(fx: Float, fy: Float, f: Float) {
        val w = toWorld(fx, fy)
        scale = (scale * f).coerceIn(MIN_SCALE, MAX_SCALE)
        anchor(fx, fy, w[0], w[1]); invalidate()
    }

    /** Turn the map by [d] degrees around screen point (fx, fy). */
    private fun rotateAt(fx: Float, fy: Float, d: Float) {
        val w = toWorld(fx, fy)
        rot += d
        anchor(fx, fy, w[0], w[1]); invalidate()
    }

    private fun pointerAngle(e: MotionEvent) =
        Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // any touch stops a fling or camera animation, as in Google Maps
            MotionEvent.ACTION_DOWN -> {
                stopMotion(); scaledInGesture = false; twoTapOk = false; rotating = false; rotInit = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    twoTapOk = true; twoTapStart = e.eventTime
                    for (i in 0..1) { twoTapPts[2 * i] = e.getX(i); twoTapPts[2 * i + 1] = e.getY(i) }
                } else twoTapOk = false
                rotInit = false; rotAccum = 0f
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) {
                if (twoTapOk) for (i in 0..1)
                    if (hypot(e.getX(i) - twoTapPts[2 * i], e.getY(i) - twoTapPts[2 * i + 1]) > slop) twoTapOk = false
                // twist: fingers turning clockwise turn the map clockwise (the bearing decreases)
                val a = pointerAngle(e)
                if (!rotInit) { rotInit = true; rotLast = a }
                else {
                    val d = angleDiff(a, rotLast); rotLast = a; rotAccum += d
                    if (!rotating && kotlin.math.abs(rotAccum) > ROT_THRESHOLD) { rotating = true; headingUp = false }
                    if (rotating) {
                        val mx = (e.getX(0) + e.getX(1)) / 2f; val my = (e.getY(0) + e.getY(1)) / 2f
                        rotateAt(focusX(mx), focusY(my), -d)
                    }
                }
            }
            // two fingers down and up again quickly without moving: zoom out
            MotionEvent.ACTION_POINTER_UP -> {
                if (twoTapOk && e.eventTime - twoTapStart < TWO_TAP_MS) {
                    val mx = (twoTapPts[0] + twoTapPts[2]) / 2f; val my = (twoTapPts[1] + twoTapPts[3]) / 2f
                    animateZoomAt(focusX(mx), focusY(my), 0.5f)
                }
                twoTapOk = false; rotInit = false   // pointer indices change: re-take the angle
            }
        }
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
    private class Layer(val style: Int, val paint: Paint, val widthPx: Float, val widthM: Float, val minScale: Float) {
        val normal = paint.color
    }
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
    // landmarks: violet dot + violet name on a white halo, distinct from stop/place labels
    private val landmarkColor = Color.rgb(105, 70, 160)
    private val landmarkDot = fill(landmarkColor)
    private val landmarkRing = stroke(Color.WHITE).apply { strokeWidth = 1.2f * dp }
    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = landmarkColor; textSize = 11 * dp; isFakeBoldText = true; setShadowLayer(3 * dp, 0f, 0f, Color.WHITE)
    }
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
    // stop callout and debug overlay
    private val calloutBg = fill(Color.argb(240, 255, 255, 255))
    private val calloutEdge = stroke(Color.rgb(150, 150, 150)).apply { strokeWidth = 1 * dp }
    private val chipFill = fill(Color.GRAY)
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * dp; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 30, 30); textSize = 11 * dp; textAlign = Paint.Align.CENTER
        setShadowLayer(3 * dp, 0f, 0f, Color.WHITE)
    }
    private val occupied = HashSet<Long>() // label collision grid, rebuilt per frame
    private val stopLabelCells = HashSet<String>() // stops whose name won a slot this frame
    private val meFill = fill(Color.WHITE)
    private val meRing = stroke(Color.rgb(238, 0, 0)).apply { strokeWidth = 4 * dp }
    private val meAcc = fill(Color.argb(50, 238, 0, 0))
    private val matrix = Matrix()

    // ---------------- high contrast: black and white only, for bright sunlight
    private val hcCoast = stroke(Color.WHITE)
    private val hcRoad = stroke(Color.WHITE)
    private val hcFoot = stroke(Color.WHITE).apply { strokeCap = Paint.Cap.BUTT }
    private val hcCasing = stroke(Color.BLACK)
    private val pinDot = fill(Color.WHITE)
    private val arrowEdge = stroke(Color.BLACK).apply { strokeWidth = 1.5f * dp }
    private var accent = Color.rgb(238, 0, 0)   // stop rings, destination, your direction
    private var stopBase = Color.WHITE

    var highContrast = false
        set(v) { field = v; applyPalette(); invalidate() }

    private fun applyPalette() {
        val hc = highContrast
        fun pick(normal: Int, bw: Int) = if (hc) bw else normal
        val red = Color.rgb(238, 0, 0)
        accent = pick(red, Color.WHITE)
        stopBase = pick(Color.WHITE, Color.BLACK)
        stopRing.color = pick(red, Color.WHITE)
        meRing.color = pick(red, Color.WHITE)
        meAcc.color = pick(Color.argb(50, 238, 0, 0), Color.argb(70, 255, 255, 255))
        itinWalkPaint.color = pick(Color.rgb(60, 60, 60), Color.WHITE)
        landmarkDot.color = pick(landmarkColor, Color.WHITE)
        landmarkRing.color = pick(Color.WHITE, Color.BLACK)
        landmarkPaint.color = pick(landmarkColor, Color.WHITE)
        landmarkPaint.setShadowLayer(3 * dp, 0f, 0f, pick(Color.WHITE, Color.BLACK))
        poiFill.color = pick(Color.rgb(255, 193, 7), Color.WHITE)
        poiStroke.color = pick(Color.WHITE, Color.BLACK)
        poiLabel.color = pick(Color.rgb(255, 193, 7), Color.WHITE)
        startFill.color = pick(Color.rgb(46, 160, 67), Color.BLACK)
        searchFill.color = pick(Color.rgb(33, 118, 243), Color.WHITE)
        searchRing.color = pick(Color.WHITE, Color.BLACK)
        flagPole.color = pick(Color.rgb(20, 20, 20), Color.WHITE)
        flagOutline.color = pick(Color.rgb(20, 20, 20), Color.WHITE)
        scaleHalo.color = pick(Color.WHITE, Color.BLACK)
        scaleLine.color = pick(Color.rgb(30, 30, 30), Color.WHITE)
        scaleText.color = pick(Color.rgb(30, 30, 30), Color.WHITE)
        scaleText.setShadowLayer(3 * dp, 0f, 0f, pick(Color.WHITE, Color.BLACK))
        calloutBg.color = pick(Color.argb(240, 255, 255, 255), Color.BLACK)
        calloutEdge.color = pick(Color.rgb(150, 150, 150), Color.WHITE)
        debugPaint.color = pick(Color.rgb(30, 30, 30), Color.WHITE)
        debugPaint.setShadowLayer(3 * dp, 0f, 0f, pick(Color.WHITE, Color.BLACK))
        clusterFill.color = pick(red, Color.WHITE)
        clusterRing.color = pick(Color.WHITE, Color.BLACK)
        lineStopFill.color = pick(Color.WHITE, Color.BLACK)
        lineStopRing.color = pick(Color.rgb(20, 20, 20), Color.WHITE)
        clusterText.color = pick(Color.WHITE, Color.BLACK)
    }
    private val pt = FloatArray(2)
    private val arrow = Path()

    override fun onDraw(c: Canvas) {
        // kinetic panning: advance the fling by this frame's distance
        if (!scroller.isFinished && scroller.computeScrollOffset()) {
            panBy((scroller.currX - flingX).toFloat(), (scroller.currY - flingY).toFloat())
            flingX = scroller.currX; flingY = scroller.currY
            postInvalidateOnAnimation()
        }
        c.drawColor(if (highContrast) Color.BLACK else sea)
        // visible world area: a circle around the centre covers the view at any bearing
        val vr = hypot(width.toFloat(), height.toFloat()) / 2f / scale
        val vx0 = cx - vr; val vx1 = cx + vr; val vy0 = cy - vr; val vy1 = cy + vr
        matrix.reset()
        matrix.setTranslate(-cx, -cy); matrix.postRotate(rot)
        matrix.postScale(scale, -scale); matrix.postTranslate(width / 2f, height / 2f)
        c.save(); c.concat(matrix)
        data?.let { d ->
            for (l in layers) {
                if (scale < l.minScale) continue
                var paint = l.paint
                if (highContrast) {
                    // black and white: coastline, thin white streets, dotted footpaths; no fills
                    paint = when (l.style) {
                        MapData.LAND -> hcCoast.apply { strokeWidth = 1.5f * dp / scale }
                        MapData.FOOT -> hcFoot.apply {
                            strokeWidth = 1f * dp / scale
                            pathEffect = DashPathEffect(floatArrayOf(1.5f * dp / scale, 4 * dp / scale), 0f)
                        }
                        MapData.SERVICE, MapData.MINOR -> hcRoad.apply { strokeWidth = 0.8f * dp / scale }
                        MapData.TERTIARY, MapData.SECONDARY -> hcRoad.apply { strokeWidth = 1.3f * dp / scale }
                        MapData.PRIMARY, MapData.TRUNK -> hcRoad.apply { strokeWidth = 2f * dp / scale }
                        else -> continue
                    }
                } else if (l.paint.style == Paint.Style.STROKE)
                    l.paint.strokeWidth = maxOf(l.widthPx * dp, l.widthM * scale) / scale
                for (b in d.buckets[l.style] ?: continue)
                    if (b.maxX >= vx0 && b.minX <= vx1 && b.maxY >= vy0 && b.minY <= vy1) c.drawPath(b.path, paint)
            }
        }
        for (r in routes) {
            val active = activeRouteId == null || r.route.id == activeRouteId
            for (dl in r.lines) {
                if (highContrast) {
                    // a fat white line broken by short black parts ("XXXX XXXX XXXX") on a thin
                    // black border, so it stands out from the thin white streets; variant
                    // branches thinner, with more black
                    val w = (if (dl.branch || !active) 4f else 7f) * dp / scale
                    hcCasing.strokeWidth = w + 3 * dp / scale
                    hcCasing.strokeCap = Paint.Cap.BUTT
                    c.drawPath(dl.path, hcCasing)
                    hcCasing.strokeCap = Paint.Cap.ROUND
                    routePaint.color = Color.WHITE; routePaint.alpha = 255; routePaint.strokeWidth = w
                    routePaint.strokeCap = Paint.Cap.BUTT
                    routePaint.pathEffect = if (dl.branch) DashPathEffect(floatArrayOf(8 * dp / scale, 5 * dp / scale), 0f)
                        else DashPathEffect(floatArrayOf(18 * dp / scale, 4 * dp / scale), 0f)
                    c.drawPath(dl.path, routePaint)
                    routePaint.pathEffect = null; routePaint.strokeCap = Paint.Cap.ROUND
                    continue
                }
                routePaint.color = dl.color
                routePaint.alpha = if (active) 255 else 90
                routePaint.strokeWidth = (if (active) 4f else 2.5f) * dp / scale
                c.drawPath(dl.path, routePaint)
            }
        }
        c.restore()

        // direction arrows on the stretches each direction has to itself, at a constant
        // density on screen: one per ARROW_SPACING_DP. The spacing in metres is a power
        // of two, so zooming in only adds arrows between the ones already there; a
        // stretch shorter than the spacing still gets one arrow, in its middle.
        val spacing = Math.pow(2.0, kotlin.math.ceil(kotlin.math.log2((ARROW_SPACING_DP * dp / scale).toDouble()))).toFloat()
        val stride = maxOf(1, (spacing / RouteRenderer.STEP).toInt())
        for (r in routes) for (dl in r.lines) {
            arrowPaint.color = if (highContrast) Color.WHITE else dl.color
            for (run in dl.runs) {
                if (run.x1 < vx0 || run.x0 > vx1 || run.y1 < vy0 || run.y0 > vy1) continue
                val n = run.count
                if (n - 1 < stride) drawRunArrow(c, run, n / 2)
                else {
                    // multiples of the stride from the start of the stretch, so halving the
                    // spacing keeps every existing arrow in place
                    var i = stride
                    while (i < n) { drawRunArrow(c, run, i); i += stride }
                }
            }
        }
        // planned-journey overlay (screen space so stroke width and dots stay constant)
        for (ip in itinPaths) {
            itinPathScreen.set(ip.path); itinPathScreen.transform(matrix)
            if (highContrast) {
                hcCasing.strokeWidth = (if (ip.walk) 7 else 10) * dp; c.drawPath(itinPathScreen, hcCasing)
                itinBusPaint.color = Color.WHITE
                // the bus part like a route: fat white with short black parts
                itinBusPaint.pathEffect = if (ip.walk) null else DashPathEffect(floatArrayOf(18 * dp, 4 * dp), 0f)
                itinBusPaint.strokeCap = if (ip.walk) Paint.Cap.ROUND else Paint.Cap.BUTT
            } else { itinBusPaint.color = ip.color; itinBusPaint.pathEffect = null; itinBusPaint.strokeCap = Paint.Cap.ROUND }
            c.drawPath(itinPathScreen, if (ip.walk) itinWalkPaint else itinBusPaint)
        }
        // ---- text labels (screen space, collision-avoided)
        occupied.clear()
        // Stop names take priority: reserve their label cells first, so street and
        // place names only fill the grid cells left over.
        stopLabelCells.clear()
        collectLineStops(vx0, vy0, vx1, vy1)
        if (scale > LINE_STOP_LABEL_SCALE) for (m in lineMarks) {
            if (m.sx < 0 || m.sx > width || m.sy < 0 || m.sy > height) continue
            m.labelled = reserve(m.sx + 10 * dp, m.sy + 4 * dp)
        }
        val showStopLabels = scale > STOP_LABEL_SCALE
        if (scale > MIN_STOP_SCALE && showStopLabels) {
            for (s in stops) {
                if (s.x < vx0 || s.x > vx1 || s.y < vy0 || s.y > vy1) continue
                if (s.id in lineStopIds) continue
                pt[0] = s.x; pt[1] = s.y; matrix.mapPoints(pt)
                if (pt[0] < 0 || pt[0] > width || pt[1] < 0 || pt[1] > height) continue
                val r = (if (s.id == nextStopId || s.id == destinationStopId) 7 else 4) * dp
                if (reserve(pt[0] + r + 3 * dp, pt[1] + 4 * dp)) stopLabelCells.add(s.id)
            }
        }
        // after stop names: towns and villages, then landmarks, then street names
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
        if (scale > LANDMARK_SCALE) {
            for (p in landmarks) {
                if (p.x < vx0 || p.x > vx1 || p.y < vy0 || p.y > vy1) continue
                pt[0] = p.x; pt[1] = p.y; matrix.mapPoints(pt)
                if (pt[0] < 0 || pt[0] > width || pt[1] < 0 || pt[1] > height) continue
                if (!reserve(pt[0] + 7 * dp, pt[1])) continue
                c.drawCircle(pt[0], pt[1], 3.5f * dp, landmarkDot); c.drawCircle(pt[0], pt[1], 3.5f * dp, landmarkRing)
                c.drawText(p.name, pt[0] + 7 * dp, pt[1] + 4 * dp, landmarkPaint)
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

        // stop symbols shrink as you zoom out (down to 40 %), staying recognisable
        val sz = stopSizeFactor()
        if (scale > MIN_STOP_SCALE) {
            for (s in stops) {
                if (s.x < vx0 || s.x > vx1 || s.y < vy0 || s.y > vy1) continue
                if (s.id in lineStopIds && s.id != selectedStopId && s.id != nextStopId && s.id != destinationStopId) continue
                pt[0] = s.x; pt[1] = s.y; matrix.mapPoints(pt)
                val hl = s.id == nextStopId || s.id == destinationStopId || s.id == selectedStopId
                val r = (if (hl) 7f else 4f * sz) * dp
                stopRing.strokeWidth = (if (hl) 2f else maxOf(1f, 2f * sz)) * dp
                stopFill.color = if (s.id == destinationStopId) accent else stopBase
                val bs = stopBearings[s.id] ?: s.heading?.let { floatArrayOf(it) } ?: NO_BEARINGS
                drawStopGlyph(c, pt[0], pt[1], r, bs, stopFill, stopRing)
                if (hl || s.id in stopLabelCells) c.drawText(s.mapName, pt[0] + r + 3 * dp, pt[1] + 4 * dp, label)
            }
        }
        else drawClusters(c, vx0, vy0, vx1, vy1)
        drawLineStops(c)
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
            // your direction: the phone's heading in heading-up mode, else the GPS course
            val dir = if (headingUp && !deviceHeading.isNaN()) deviceHeading else m[3]
            if (!dir.isNaN()) { meFill.color = accent; drawArrow(c, pt[0], pt[1], dir - rot, 16 * dp) }
            meFill.color = if (highContrast) Color.BLACK else Color.WHITE
            c.drawCircle(pt[0], pt[1], 8 * dp, meFill); c.drawCircle(pt[0], pt[1], 8 * dp, meRing)
        }
        drawCallout(c)
        drawScaleBar(c)
        debugText?.let { c.drawText(it, width / 2f, 22 * dp, debugPaint) }
        onCameraDrawn?.invoke()
    }

    /** Stop mode: the stop's lines as chips in their colours, on a panel above the stop. */
    private fun drawCallout(c: Canvas) {
        chipHits.clear(); calloutRect.setEmpty()
        val chips = calloutChips ?: return
        if (chips.isEmpty()) return
        pt[0] = calloutX; pt[1] = calloutY; matrix.mapPoints(pt)
        val sx = pt[0]; val sy = pt[1]
        if (sx < 0 || sx > width || sy < 0 || sy > height) return
        val h = 26 * dp; val padX = 8 * dp; val gap = 5 * dp; val pad = 7 * dp; val maxW = width * 0.8f
        // lay the chips out in rows no wider than maxW
        val rows = ArrayList<ArrayList<Pair<Chip, Float>>>()
        var row = ArrayList<Pair<Chip, Float>>(); var rowW = 0f
        for (ch in chips) {
            val w = maxOf(h, chipText.measureText(ch.code) + 2 * padX)
            if (row.isNotEmpty() && rowW + gap + w > maxW) { rows.add(row); row = ArrayList(); rowW = 0f }
            rowW += (if (row.isEmpty()) 0f else gap) + w; row.add(ch to w)
        }
        rows.add(row)
        fun widthOf(r: List<Pair<Chip, Float>>) = r.sumOf { it.second.toDouble() }.toFloat() + gap * (r.size - 1)
        val lineH = 19 * dp
        calloutTextPaint.color = if (highContrast) Color.WHITE else Color.rgb(20, 20, 20)
        chipSelRing.color = if (highContrast) Color.WHITE else Color.rgb(20, 20, 20)
        val textW = calloutLines.maxOfOrNull { calloutTextPaint.measureText(it) } ?: 0f
        val blockW = maxOf(rows.maxOf { widthOf(it) }, minOf(textW, width - 6 * pad))
        val textH = if (calloutLines.isEmpty()) 0f else gap + calloutLines.size * lineH
        val blockH = rows.size * h + (rows.size - 1) * gap + textH
        var top = sy - 16 * dp - blockH - pad            // above the stop ...
        if (top < pad + 30 * dp) top = sy + 16 * dp + pad  // ... or below it near the top edge
        val left = (sx - blockW / 2).coerceIn(2 * pad, maxOf(2 * pad, width - blockW - 2 * pad))
        calloutRect.set(left - pad, top - pad, left + blockW + pad, top + blockH + pad)
        c.drawRoundRect(calloutRect, 9 * dp, 9 * dp, calloutBg); c.drawRoundRect(calloutRect, 9 * dp, 9 * dp, calloutEdge)
        var y = top
        for (r in rows) {
            var x = left + (blockW - widthOf(r)) / 2
            for ((ch, w) in r) {
                val rect = android.graphics.RectF(x, y, x + w, y + h)
                chipFill.color = if (highContrast) Color.BLACK else ch.color
                c.drawRoundRect(rect, 5 * dp, 5 * dp, chipFill)
                if (highContrast) c.drawRoundRect(rect, 5 * dp, 5 * dp, calloutEdge)
                chipText.color = if (highContrast) Color.WHITE else ch.textColor
                c.drawText(ch.code, x + w / 2, y + h / 2 + chipText.textSize * 0.36f, chipText)
                if (ch.code == calloutSel) {
                    val ring = android.graphics.RectF(rect.left - 2.5f * dp, rect.top - 2.5f * dp, rect.right + 2.5f * dp, rect.bottom + 2.5f * dp)
                    c.drawRoundRect(ring, 7 * dp, 7 * dp, chipSelRing)
                }
                chipHits.add(ChipHit(ch.code, rect))
                x += w + gap
            }
            y += h + gap
        }
        // the picked line: where it goes and when it leaves (tap the chip again for the line)
        var ty = y - gap + gap + lineH * 0.75f
        for ((i, t) in calloutLines.withIndex()) {
            calloutTextPaint.isFakeBoldText = i == 0
            c.drawText(t, left + blockW / 2, ty, calloutTextPaint); ty += lineH
        }
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
        pinDot.color = ringP.color   // centre dot in the ring colour: white, or black on a white pin
        c.drawCircle(x, cyc, r * 0.4f, pinDot)
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

    private fun drawRunArrow(c: Canvas, run: ArrowRun, i: Int) {
        val a = run.pts
        pt[0] = a[3 * i]; pt[1] = a[3 * i + 1]; matrix.mapPoints(pt)
        if (pt[0] in 0f..width.toFloat() && pt[1] in 0f..height.toFloat()) drawArrow(c, pt[0], pt[1], a[3 * i + 2] - rot, 7 * dp)
    }

    private fun drawArrow(c: Canvas, x: Float, y: Float, bearing: Float, size: Float) {
        c.save(); c.translate(x, y); c.rotate(bearing)
        arrow.reset(); arrow.moveTo(0f, -size); arrow.lineTo(size * 0.7f, size * 0.6f)
        arrow.lineTo(0f, size * 0.2f); arrow.lineTo(-size * 0.7f, size * 0.6f); arrow.close()
        c.drawPath(arrow, if (size > 10 * dp) meFill else arrowPaint)
        if (highContrast) c.drawPath(arrow, arrowEdge)
        c.restore()
    }

    /** Claim a label slot on a coarse grid; false if the cell is already taken. */
    private fun reserve(x: Float, y: Float): Boolean {
        val gx = (x / (72 * dp)).toInt(); val gy = (y / (18 * dp)).toInt()
        return occupied.add(gx.toLong() * 100000L + gy)
    }

    // ---------------- stops of the line on show: every stop clearly marked, with
    // an arrow for the way the bus leaves it. A stop and its same-name partner in
    // the other direction are drawn as one (no arrow) while they would overlap.
    private class LineMark(var sx: Float, var sy: Float, val bearings: FloatArray, val color: Int,
                           val name: String, val ids: List<String>) { var labelled = false }
    private val lineMarks = ArrayList<LineMark>()
    private val lineStopIds = HashSet<String>()
    private val lineStopFill = fill(Color.WHITE)
    private val lineStopRing = stroke(Color.rgb(20, 20, 20)).apply { strokeWidth = 2.5f * dp }

    private fun collectLineStops(vx0: Float, vy0: Float, vx1: Float, vy1: Float) {
        lineMarks.clear(); lineStopIds.clear()
        val pad = 60 * dp / scale
        for (r in routes) {
            if (activeRouteId != null && r.route.id != activeRouteId) continue
            val ss = r.stops
            val done = BooleanArray(ss.size)
            for (i in ss.indices) {
                if (done[i]) continue
                val a = ss[i]
                lineStopIds.add(a.id)
                if (a.x < vx0 - pad || a.x > vx1 + pad || a.y < vy0 - pad || a.y > vy1 + pad) continue
                pt[0] = a.x; pt[1] = a.y; matrix.mapPoints(pt)
                val ax = pt[0]; val ay = pt[1]
                val j = r.partner.getOrElse(i) { -1 }
                if (j >= 0 && !done[j]) {
                    val b = ss[j]
                    pt[0] = b.x; pt[1] = b.y; matrix.mapPoints(pt)
                    if (hypot(pt[0] - ax, pt[1] - ay) < PAIR_MERGE_DP * dp) {
                        // one marker for both directions, between the two stops
                        done[j] = true; lineStopIds.add(b.id)
                        lineMarks += LineMark((ax + pt[0]) / 2, (ay + pt[1]) / 2,
                            Precalc.mergeBearings(listOf(a.bearing, b.bearing).filter { !it.isNaN() }), a.color, a.label, listOf(a.id, b.id))
                        done[i] = true
                        continue
                    }
                }
                done[i] = true
                lineMarks += LineMark(ax, ay, if (a.bearing.isNaN()) NO_BEARINGS else floatArrayOf(a.bearing), a.color, a.label, listOf(a.id))
            }
        }
    }

    /**
     * A stop: a circle with a triangle fused to it for every direction buses leave
     * in, filled and outlined as one shape. Shapes are built north-up (bearings are
     * compass bearings) and cached; the canvas turns them with the map.
     */
    private val glyphCache = HashMap<String, Path>()
    private fun glyph(r: Float, bearings: FloatArray): Path {
        val key = buildString {
            append((r * 2).toInt())
            for (b in bearings) { append(','); append(((b / 5f).roundToInt() * 5) % 360) }
        }
        return glyphCache.getOrPut(key) {
            val p = Path().apply { addCircle(0f, 0f, r, Path.Direction.CW) }
            for (b in bearings) {
                val a = Math.toRadians(b.toDouble())
                val ux = sin(a).toFloat(); val uy = -cos(a).toFloat()     // screen: y down
                val px = -uy; val py = ux                                  // perpendicular
                val half = r * 0.82f; val base = r * 0.45f; val tip = r * 2.15f
                val t = Path()
                t.moveTo(ux * tip, uy * tip)
                t.lineTo(ux * base + px * half, uy * base + py * half)
                t.lineTo(ux * base - px * half, uy * base - py * half)
                t.close()
                p.op(t, Path.Op.UNION)
            }
            if (glyphCache.size > 400) glyphCache.clear()
            p
        }
    }

    /** 1 at street level, smaller further out, never below 0.4 (circle and triangles still readable). */
    private fun stopSizeFactor(): Float = Math.pow((scale / 1.5f).toDouble(), 0.3).toFloat().coerceIn(0.4f, 1f)

    private fun drawStopGlyph(c: Canvas, x: Float, y: Float, r: Float, bearings: FloatArray, fillP: Paint, ringP: Paint) {
        if (bearings.isEmpty()) { c.drawCircle(x, y, r, fillP); c.drawCircle(x, y, r, ringP); return }
        val g = glyph(r, bearings)
        c.save(); c.translate(x, y); c.rotate(-rot)
        c.drawPath(g, fillP); c.drawPath(g, ringP)
        c.restore()
    }

    private fun drawLineStops(c: Canvas) {
        for (m in lineMarks) {
            if (m.sx < -20 * dp || m.sx > width + 20 * dp || m.sy < -20 * dp || m.sy > height + 20 * dp) continue
            val sel = m.ids.any { it == selectedStopId }
            val f = if (sel) 1f else stopSizeFactor()
            val r = (if (m.ids.size > 1) 6.5f else 5.5f) * f * dp
            lineStopRing.strokeWidth = maxOf(1f, 2.5f * f) * dp
            drawStopGlyph(c, m.sx, m.sy, r, m.bearings, lineStopFill, lineStopRing)
            if (m.labelled || sel)
                c.drawText(m.name, m.sx + r + 4 * dp, m.sy + 4 * dp, label)
        }
    }

    // ---------------- stop clusters: zoomed out too far to show stops, nearby
    // stops are drawn as one circle with their count at their average position.
    // Clusters are built in world space per power-of-two zoom level, so they stay
    // put while panning and only regroup when the zoom crosses a level.
    private val clusterCache = HashMap<Int, List<StopCluster>>()
    private val clusterScreen = ArrayList<Pair<StopCluster, FloatArray>>()   // last drawn: cluster, (x, y, r)
    private val clusterFill = fill(Color.rgb(238, 0, 0))
    private val clusterRing = stroke(Color.WHITE).apply { strokeWidth = 2 * dp }
    private val clusterText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 10 * dp
    }
    /** Departure directions per stop id (precalculated; see [Precalc]). */
    private var stopBearings: Map<String, FloatArray> = emptyMap()

    /** Take the precalculated stop groups and stop directions (call after setting [stops]). */
    fun setPrecalc(p: Precalc) {
        clusterCache.clear(); clusterCache.putAll(p.clusters)
        stopBearings = p.bearings
        invalidate()
    }

    /** Groups for [level]: precalculated, or worked out now (and kept) if not there yet. */
    private fun clustersFor(level: Int): List<StopCluster> = clusterCache.getOrPut(level) { StopCluster.compute(stops, level) }

    /** Just big enough for the number: two digits fit comfortably, four barely. */
    private fun clusterRadius(n: Int): Float {
        val digits = maxOf(2, n.toString().length)
        return clusterText.measureText("8".repeat(digits)) / 2 + 2.5f * dp
    }

    private fun drawClusters(c: Canvas, vx0: Float, vy0: Float, vx1: Float, vy1: Float) {
        clusterScreen.clear()
        if (stops.isEmpty()) return
        // cell of ~48dp on screen, rounded to a power of two in world units
        val level = StopCluster.levelFor(scale, dp)
        val pad = 40 * dp / scale
        for (cl in clustersFor(level)) {
            if (cl.x < vx0 - pad || cl.x > vx1 + pad || cl.y < vy0 - pad || cl.y > vy1 + pad) continue
            pt[0] = cl.x; pt[1] = cl.y; matrix.mapPoints(pt)
            val n = cl.stops.size
            val r = clusterRadius(n)
            c.drawCircle(pt[0], pt[1], r, clusterFill); c.drawCircle(pt[0], pt[1], r, clusterRing)
            c.drawText(n.toString(), pt[0], pt[1] + clusterText.textSize * 0.36f, clusterText)
            clusterScreen.add(cl to floatArrayOf(pt[0], pt[1], r))
        }
    }

    private fun clusterHit(sx: Float, sy: Float): StopCluster? =
        clusterScreen.filter { (_, p) -> hypot(p[0] - sx, p[1] - sy) < p[2] + 8 * dp }
            .minByOrNull { (_, p) -> hypot(p[0] - sx, p[1] - sy) }?.first

    /** Glide in until the cluster's stops fill the view: they show as stops or smaller clusters. */
    private fun zoomToCluster(cl: StopCluster) {
        val m = 56 * dp
        // at least twice as close, so a tap always opens the group up; at most street level
        fitBounds(cl.x0, cl.y0, cl.x1, cl.y1, m, m, m, m, minS = scale * 2f, maxS = 3f)
    }

    companion object {
        const val MIN_STOP_SCALE = 0.25f
        const val MIN_SCALE_ALL = 0.01f   // = MIN_SCALE: whole islands
        private val NO_BEARINGS = FloatArray(0)
        private const val ARROW_SPACING_DP = 140f   // one direction arrow per this much line on screen
        private const val LINE_STOP_LABEL_SCALE = 0.35f
        private const val PAIR_MERGE_DP = 26f   // closer than this on screen: draw the pair as one
        private const val MIN_SCALE = 0.01f   // px per metre: whole islands
        private const val MAX_SCALE = 25f     // px per metre: street detail
        private const val TWO_TAP_MS = 300L   // two-finger tap must be quicker than this
        private const val ROT_THRESHOLD = 12f // degrees of twist before the map starts to rotate
        private const val HEADING_OFFSET = 0.2f // heading-up: you sit 20% of the height below centre

        /** Angle in [0, 360). */
        fun norm360(a: Float) = ((a % 360f) + 360f) % 360f
        /** Signed smallest difference a - b, in (-180, 180]. */
        fun angleDiff(a: Float, b: Float): Float { val d = norm360(a - b); return if (d > 180f) d - 360f else d }
        // stop names show from this zoom (lowered so they appear earlier); streets a bit later
        private const val STOP_LABEL_SCALE = 1.2f
        private const val STREET_LABEL_SCALE = 2.0f
        private const val LANDMARK_SCALE = 1.5f
        // indexed by place rank 0..4 (capital/city, town, village/suburb, hamlet, locality)
        private val PLACE_MIN_SCALE = floatArrayOf(0f, 0.02f, 0.12f, 0.5f, 1.5f)
        private val PLACE_TEXT = floatArrayOf(17f, 15f, 13f, 12f, 11f)
    }
}
