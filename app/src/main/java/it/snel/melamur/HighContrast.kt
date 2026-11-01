package it.snel.melamur

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.view.Window

/**
 * High contrast for bright sun. Windows (the main screen, settings, every
 * dialog) are shown through a filter: grey, with the contrast pushed towards
 * pure black and white. The map draws itself in black and white on top of that.
 */
object HighContrast {
    private const val KEY = "high_contrast"

    fun on(ctx: Context) = ctx.getSharedPreferences("melamur", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun set(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences("melamur", Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply()

    private val paint by lazy {
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val k = 1.8f; val off = 128f * (1 - k)
        cm.postConcat(ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, off, 0f, k, 0f, 0f, off, 0f, 0f, k, 0f, off, 0f, 0f, 0f, 1f, 0f)))
        Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
    }

    /** Filter [w] if high contrast is on, or take the filter off. */
    fun apply(ctx: Context, w: Window?) {
        val v = w?.decorView ?: return
        if (on(ctx)) v.setLayerType(View.LAYER_TYPE_HARDWARE, paint) else v.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    fun <D : Dialog> dialog(d: D): D { apply(d.context, d.window); return d }

    /** Theme for dialogs: white accents in high contrast, else the app's (0 = default). */
    fun dialogTheme(ctx: Context): Int = if (on(ctx)) R.style.Theme_MelaMur_Dialog_HC else 0
}

/** AlertDialog.Builder whose dialogs follow high contrast. */
class HcDialog(ctx: Context) : AlertDialog.Builder(ctx, HighContrast.dialogTheme(ctx)) {
    override fun create(): AlertDialog = HighContrast.dialog(super.create())
}
