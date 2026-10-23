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
 * 3-second loading screen: the bus drives in from the right as "Mela" appears,
 * then "Mur" appears, and the bus drives off to the left. Credits OpenStreetMap
 * and Malta Public Transport.
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
        private val dur = 3000L
        private val bus: Drawable? = runCatching { resources.getDrawable(R.drawable.ic_logo, a.theme) }.getOrNull()
        private val boat: Drawable? = runCatching { resources.getDrawable(R.drawable.ic_boat, a.theme) }.getOrNull()
        private val cross: Drawable? = runCatching { resources.getDrawable(R.drawable.ic_maltese_cross, a.theme) }.getOrNull()
        private val red = Color.rgb(238, 0, 0)
        private val mela = textPaint(Color.WHITE)
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

            val cyText = h * 0.70f
            val size = min(w, h) * 0.13f
            mela.textSize = size; mur.textSize = size
            // word reveal
            val melaA = ((el - 300) / 500f).coerceIn(0f, 1f)
            val murA = ((el - 1100) / 500f).coerceIn(0f, 1f)
            val gap = size * 0.1f
            val wMela = mela.measureText("Mela"); val wMur = mur.measureText("Mur")
            val total = wMela + gap + wMur
            val x0 = (w - total) / 2
            mela.alpha = (melaA * 255).toInt()
            mur.alpha = (murA * 255).toInt()
            c.drawText("Mela", x0 + wMela / 2, cyText, mela)
            c.drawText("Mur", x0 + wMela + gap + wMur / 2, cyText, mur)

            // vehicles cross the strip, each driving in from the right and out to
            // the left (Malta drives on the left): empty -> bus -> boat -> bus
            val vW = w * 0.34f; val vH = vW * 0.6f
            val yBus = h * 0.52f
            fun pass(d: Drawable?, t0: Float, t1: Float) {
                if (d == null || el < t0 || el > t1) return
                val p = ease(((el - t0) / (t1 - t0)).coerceIn(0f, 1f))
                val xStart = w + vW; val xEnd = -vW * 1.3f
                val x = xStart + (xEnd - xStart) * p
                d.setBounds(x.toInt(), yBus.toInt(), (x + vW).toInt(), (yBus + vH).toInt()); d.draw(c)
            }
            pass(bus, 250f, 1150f)
            pass(boat, 1150f, 2050f)
            pass(bus, 2050f, 2950f)

            c.drawText("Timetables © Malta Public Transport", w / 2, h - 46 * dp, credit)
            c.drawText("Map data © OpenStreetMap contributors", w / 2, h - 28 * dp, credit)

            if (t >= 1f) { if (!done) { done = true; onDone() } } else postInvalidateOnAnimation()
        }
    }
}
