package it.snel.melamur.update

import android.app.Activity
import android.app.AlertDialog
import it.snel.melamur.HcDialog
import it.snel.melamur.R
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Menu item "Check for updates": check GitHub, offer the update, download and install it. */
class UpdateFlow(private val act: Activity) {
    private val prefs = act.getSharedPreferences("melamur", Context.MODE_PRIVATE)
    private var token: String?
        get() = prefs.getString("github_token", null)?.takeIf { it.isNotBlank() }
        set(v) { prefs.edit().apply { if (v.isNullOrBlank()) remove("github_token") else putString("github_token", v) }.apply() }

    private fun ui(block: () -> Unit) = act.runOnUiThread { if (!act.isFinishing && !act.isDestroyed) block() }
    private fun alert(title: String, msg: String?) =
        HcDialog(act).setTitle(title).setMessage(msg ?: "").setPositiveButton(android.R.string.ok, null).show()

    fun start() {
        Toast.makeText(act, R.string.upd_checking, Toast.LENGTH_SHORT).show()
        Thread {
            val r = Updater.check(token)
            ui {
                when (r) {
                    is Updater.Result.UpToDate -> alert(act.getString(R.string.upd_none), r.message + "\n\n" + act.getString(R.string.upd_installed, Updater.installedLabel()))
                    is Updater.Result.Failed -> alert(act.getString(R.string.upd_check_failed), r.message)
                    is Updater.Result.Update -> offer(r.available)
                }
            }
        }.start()
    }

    private fun offer(a: Updater.Available) {
        HcDialog(act).setTitle(act.getString(R.string.upd_available, a.title))
            .setMessage(a.details + "\n\n" + act.getString(R.string.upd_installed, Updater.installedLabel()))
            .setPositiveButton(R.string.upd_install) { _, _ -> proceed(a) }
            .setNegativeButton(R.string.upd_later, null).show()
    }

    private fun proceed(a: Updater.Available) {
        if (a.needsToken && token == null) { askToken(null) { proceed(a) }; return }
        if (!act.packageManager.canRequestPackageInstalls()) {
            HcDialog(act).setTitle(R.string.upd_allow_title)
                .setMessage(R.string.upd_allow_text)
                .setPositiveButton(R.string.upd_open_settings) { _, _ ->
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        download(a)
    }

    private fun download(a: Updater.Available) {
        val d = act.resources.displayMetrics.density
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; max = 1000 }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; val p = (20 * d).toInt(); setPadding(p, p, p, p); addView(bar)
        }
        val dlg = HcDialog(act).setTitle(act.getString(R.string.upd_downloading, a.title)).setView(box).setCancelable(false).show()
        Thread {
            try {
                val apk = Updater.download(a, token, File(act.cacheDir, "update")) { done, total ->
                    if (total > 0) act.runOnUiThread { bar.isIndeterminate = false; bar.progress = (done * 1000 / total).toInt() }
                }
                Updater.install(act, apk)   // Android now shows its own "Update this app?" screen
                ui { dlg.dismiss() }
            } catch (e: Updater.AuthException) {
                ui { dlg.dismiss(); askToken(act.getString(R.string.upd_token_rejected)) { proceed(a) } }
            } catch (e: Exception) {
                ui { dlg.dismiss(); alert(act.getString(R.string.upd_failed), e.message) }
            }
        }.start()
    }

    /** Debug builds are GitHub Actions artifacts, which GitHub only serves to signed-in users. */
    private fun askToken(problem: String?, onSaved: () -> Unit) {
        val d = act.resources.displayMetrics.density
        val input = EditText(act).apply {
            hint = "github_pat_…"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; val p = (20 * d).toInt(); setPadding(p, p / 2, p, 0)
            addView(TextView(act).apply {
                setTextColor(Color.rgb(176, 176, 176))
                text = (problem?.let { "$it\n\n" } ?: "") +
                    act.getString(R.string.upd_token_text)
            })
            addView(input)
        }
        val b = HcDialog(act).setTitle(R.string.upd_token_title).setView(box)
            .setPositiveButton(R.string.upd_save) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { token = t; onSaved() }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (token != null) b.setNeutralButton(R.string.upd_remove_token) { _, _ -> token = null }
        b.show()
    }
}
