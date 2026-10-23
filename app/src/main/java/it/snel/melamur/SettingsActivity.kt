package it.snel.melamur

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import it.snel.melamur.data.Engine
import it.snel.melamur.data.Settings

/**
 * Settings: debug mode (all three routing engines + mock travel), the routing
 * engine to use when debug is off, and storing the current map view as the
 * startup position. The current map view is passed in as intent extras.
 */
class SettingsActivity : Activity() {
    private val white = Color.WHITE
    private val grey = Color.rgb(176, 176, 176)
    private val d by lazy { resources.displayMetrics.density }
    private fun px(v: Float) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11, 11, 11))
            setPadding(px(20f), px(20f), px(20f), px(20f))
        }
        root.addView(title(getString(R.string.settings_title), 22f, Color.rgb(238, 0, 0)))

        // --- debug switch
        val debug = Switch(this).apply {
            text = getString(R.string.settings_debug); setTextColor(white); textSize = 17f
            isChecked = Settings.debug(this@SettingsActivity)
            setPadding(0, px(16f), 0, px(4f))
        }
        root.addView(debug)
        root.addView(hint(getString(R.string.settings_debug_sum)))

        // --- engine selection (used when debug off)
        root.addView(title(getString(R.string.settings_engine), 16f, white).apply { setPadding(0, px(20f), 0, px(2f)) })
        root.addView(hint(getString(R.string.settings_engine_sum)))
        val group = RadioGroup(this)
        val engines = listOf(Engine.RAPTOR to R.string.engine_raptor, Engine.CSA to R.string.engine_csa,
            Engine.MCRAPTOR to R.string.engine_mcraptor)
        val current = Settings.engine(this)
        engines.forEachIndexed { i, (e, res) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = getString(res); setTextColor(white); textSize = 16f
                isChecked = e == current; tag = e
            })
        }
        group.setOnCheckedChangeListener { _, id ->
            (findViewById<RadioButton>(id).tag as? Engine)?.let { Settings.setEngine(this, it) }
        }
        root.addView(group)

        fun applyDebugEnabled() {
            val on = debug.isChecked
            for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = !on
        }
        applyDebugEnabled()
        debug.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, v ->
            Settings.setDebug(this, v); applyDebugEnabled()
        })

        // --- startup map
        root.addView(title(getString(R.string.settings_startup), 16f, white).apply { setPadding(0, px(24f), 0, px(6f)) })
        val lat = intent.getDoubleExtra("lat", Double.NaN)
        val lon = intent.getDoubleExtra("lon", Double.NaN)
        val scale = intent.getFloatExtra("scale", 0f)
        root.addView(Button(this).apply {
            text = getString(R.string.settings_store_map); isEnabled = !lat.isNaN()
            setOnClickListener {
                Settings.setStartup(this@SettingsActivity, lat, lon, scale)
                Toast.makeText(this@SettingsActivity, R.string.settings_map_stored, Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.settings_clear_map)
            setOnClickListener {
                Settings.clearStartup(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, R.string.settings_map_cleared, Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(11, 11, 11)); addView(root) })
    }

    private fun title(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s; setTextColor(color); textSize = size; setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun hint(s: String) = TextView(this).apply { text = s; setTextColor(grey); textSize = 13f }
}
