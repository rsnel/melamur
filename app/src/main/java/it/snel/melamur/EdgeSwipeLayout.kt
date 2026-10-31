package it.snel.melamur

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Content area that switches between map and text when the user pulls from the
 * left or right edge (both edges toggle: with two views the order is circular).
 * The drag is followed live through [listener]; a short fling also commits.
 * Edge strips are excluded from the system back gesture where Android allows it.
 */
class EdgeSwipeLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {

    interface Listener {
        /** A pull started from the left ([fromLeft]) or right edge; return false to ignore it. */
        fun onPullStart(fromLeft: Boolean): Boolean
        /** Horizontal finger travel since the pull started (positive = to the right). */
        fun onPull(dx: Float)
        /** Finger lifted: [commit] says whether to switch views. */
        fun onPullEnd(commit: Boolean)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val edge = 36 * density               // width of the grab strip on each side
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = 600 * density
    private var downX = 0f
    private var downY = 0f
    private var fromLeft = false
    private var armed = false     // touch started in an edge strip
    private var pulling = false
    private var vt: VelocityTracker? = null

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (android.os.Build.VERSION.SDK_INT < 29) return
        // Android limits gesture exclusion to 200dp per edge: take the middle 200dp.
        val h = b - t
        val ex = (200 * density).toInt().coerceAtMost(h)
        val top = (h - ex) / 2
        val e = edge.toInt()
        systemGestureExclusionRects = listOf(Rect(0, top, e, top + ex), Rect(r - l - e, top, r - l, top + ex))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; pulling = false
                armed = ev.x < edge || ev.x > width - edge
                fromLeft = ev.x < edge
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (armed && ev.pointerCount == 1) {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    val inward = if (fromLeft) dx > slop else dx < -slop
                    if (inward && abs(dx) > abs(dy) * 1.5f) {
                        armed = false
                        if (listener?.onPullStart(fromLeft) == true) {
                            pulling = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true // children get ACTION_CANCEL
                        }
                    } else if (abs(dy) > slop * 2 || ev.pointerCount > 1) armed = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { armed = false }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!pulling) return super.onTouchEvent(ev)
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> listener?.onPull(clamp(ev.x - downX))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = clamp(ev.x - downX)
                vt?.computeCurrentVelocity(1000)
                val v = vt?.xVelocity ?: 0f
                val fling = if (fromLeft) v > minFling else v < -minFling
                val back = if (fromLeft) v < -minFling else v > minFling
                val commit = ev.actionMasked == MotionEvent.ACTION_UP && !back && (fling || abs(dx) > width / 3f)
                pulling = false
                vt?.recycle(); vt = null
                listener?.onPullEnd(commit)
            }
        }
        return true
    }

    private fun clamp(dx: Float) = if (fromLeft) dx.coerceIn(0f, width.toFloat()) else dx.coerceIn(-width.toFloat(), 0f)
}
