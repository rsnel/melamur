package it.snel.melamur

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The coloured box next to a line number. One colour: a plain square. Two
 * colours (the line's directions differ): split diagonally, first direction
 * top-left. More: vertical stripes.
 */
class LineSwatch(ctx: Context, private val colors: List<Int>) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tri = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cols = colors.ifEmpty { listOf(Color.GRAY) }
        when {
            cols.size == 1 -> { paint.color = cols[0]; c.drawRect(0f, 0f, w, h, paint) }
            cols.size == 2 -> {
                // full square in colour 0, then the bottom-right triangle in colour 1 (no seam)
                paint.color = cols[0]; c.drawRect(0f, 0f, w, h, paint)
                tri.reset(); tri.moveTo(w, 0f); tri.lineTo(w, h); tri.lineTo(0f, h); tri.close()
                paint.color = cols[1]; c.drawPath(tri, paint)
            }
            else -> {
                val sw = w / cols.size
                cols.forEachIndexed { i, col -> paint.color = col; c.drawRect(i * sw, 0f, (i + 1) * sw, h, paint) }
            }
        }
    }
}
