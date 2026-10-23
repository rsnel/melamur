package it.snel.melamur

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import kotlin.math.min

/**
 * 5-second loading screen under a Maltese cross + "MALTA" lockup: a bus drives
 * in from the right and stops, then leaves; a boat (lower, on the water) drives
 * in and stops, then leaves; a second bus drives in. "Mela" fades in until the
 * boat has stopped, then "Mur" fades in (both red). Credits OpenStreetMap and
 * Malta Public Transport.
 */
class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(SplashView(this) {
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
        })
    }

    private class SplashView(a: Activity, val onDone: () -> Unit) : View(a) {
        private val dp = resources.displayMetrics.density
        private val start = System.nanoTime()
        private val dur = 6500L
        private val bus: Drawable? = runCatching { resources.getDrawable(R.drawable.ic_logo, a.theme) }.getOrNull()
        private val boat: Drawable? = runCatching { resources.getDrawable(R.drawable.ic_boat, a.theme) }.getOrNull()
        private val cross: Drawable? = runCatching { resources.getDrawable(R.drawable.ic_maltese_cross, a.theme) }.getOrNull()
        private val red = Color.rgb(238, 0, 0)
        private val mela = textPaint(red)
        private val mur = textPaint(red)
        // "MALTA" in letter-spaced small caps (serif) below the cross
        private val malta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); letterSpacing = 0.5f
        }
        private val credit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 150, 150); textSize = 12 * dp; textAlign = Paint.Align.CENTER
        }
        private var done = false

        private fun textPaint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }

        private fun ease(t: Float) = t * t * (3 - 2 * t)   // smoothstep

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(11, 11, 11))
            val el = (System.nanoTime() - start) / 1_000_000f
            val t = (el / dur).coerceIn(0f, 1f)
            val w = width.toFloat(); val h = height.toFloat()
            val m = min(w, h)

            // Maltese cross in the upper region, with "MALTA" below it
            val cs = m * 0.26f; val cxp = (w - cs) / 2f; val cty = h * 0.09f
            cross?.let {
                it.alpha = ((el / 600f).coerceIn(0f, 1f) * 255).toInt()
                it.setBounds(cxp.toInt(), cty.toInt(), (cxp + cs).toInt(), (cty + cs).toInt()); it.draw(c)
            }
            malta.textSize = m * 0.055f
            malta.alpha = (((el - 300) / 500f).coerceIn(0f, 1f) * 255).toInt()
            c.drawText("MALTA", w / 2f + malta.letterSpacing * malta.textSize / 2f, cty + cs + m * 0.09f, malta)

            // vehicles: bus drives in from the right and stops, then leaves; the
            // boat (lower, on the water) drives in and stops, then leaves; a
            // second bus drives in. Malta drives on the left, so exits go left.
            val vW = w * 0.34f; val vH = vW * 0.6f
            val yBus = h * 0.46f; val yBoat = h * 0.62f
            val boatStop = 3300f
            fun driveStop(d: Drawable?, yTop: Float, tIn: Float, tHold: Float, tOut: Float, tEnd: Float) {
                if (d == null || el < tIn || el > tEnd) return
                val xStart = w + vW; val xMid = (w - vW) / 2f; val xEnd = -vW * 1.3f
                val x = when {
                    el < tHold -> xStart + (xMid - xStart) * ease(((el - tIn) / (tHold - tIn)).coerceIn(0f, 1f))
                    el < tOut -> xMid
                    else -> xMid + (xEnd - xMid) * ease(((el - tOut) / (tEnd - tOut)).coerceIn(0f, 1f))
                }
                d.setBounds(x.toInt(), yTop.toInt(), (x + vW).toInt(), (yTop + vH).toInt()); d.draw(c)
            }
            driveStop(bus, yBus, 300f, 1200f, 1900f, 2500f)
            driveStop(boat, yBoat, 2500f, boatStop, 4100f, 4700f)
            // second bus drives in and comes to rest (tOut == tEnd, so it never drives out)
            driveStop(bus, yBus, 4700f, 5600f, 6500f, 6500f)

            // "Mela" fades in until the boat has stopped; "Mur" fades in after. Both red.
            val cyText = h * 0.82f
            val size = m * 0.13f
            mela.textSize = size; mur.textSize = size
            val gap = size * 0.1f
            val wMela = mela.measureText("Mela"); val wMur = mur.measureText("Mur")
            val x0 = (w - (wMela + gap + wMur)) / 2
            mela.alpha = (((el - 1200f) / (boatStop - 1200f)).coerceIn(0f, 1f) * 255).toInt()
            mur.alpha = (((el - boatStop) / 900f).coerceIn(0f, 1f) * 255).toInt()
            c.drawText("Mela", x0 + wMela / 2, cyText, mela)
            c.drawText("Mur", x0 + wMela + gap + wMur / 2, cyText, mur)

            c.drawText("Timetables © Malta Public Transport", w / 2, h - 46 * dp, credit)
            c.drawText("Map data © OpenStreetMap contributors", w / 2, h - 28 * dp, credit)

            if (t >= 1f) { if (!done) { done = true; onDone() } } else postInvalidateOnAnimation()
        }
    }
}
