package it.snel.melamur.update

import android.app.Activity
import android.app.AlertDialog
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
        AlertDialog.Builder(act).setTitle(title).setMessage(msg ?: "").setPositiveButton(android.R.string.ok, null).show()

    fun start() {
        Toast.makeText(act, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = Updater.check(token)
            ui {
                when (r) {
                    is Updater.Result.UpToDate -> alert("No update", "${r.message}\n\nInstalled: ${Updater.installedLabel()}")
                    is Updater.Result.Failed -> alert("Update check failed", r.message)
                    is Updater.Result.Update -> offer(r.available)
                }
            }
        }.start()
    }

    private fun offer(a: Updater.Available) {
        AlertDialog.Builder(act).setTitle("${a.title} is available")
            .setMessage("${a.details}\n\nInstalled: ${Updater.installedLabel()}")
            .setPositiveButton("Install") { _, _ -> proceed(a) }
            .setNegativeButton("Later", null).show()
    }

    private fun proceed(a: Updater.Available) {
        if (a.needsToken && token == null) { askToken(null) { proceed(a) }; return }
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act).setTitle("Allow installing updates")
                .setMessage("Android asks once whether MelaMur may install apps. Turn it on, come back, and tap Check for updates again.")
                .setPositiveButton("Open settings") { _, _ ->
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                }
                .setNegativeButton("Cancel", null).show()
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
        val dlg = AlertDialog.Builder(act).setTitle("Downloading ${a.title}…").setView(box).setCancelable(false).show()
        Thread {
            try {
                val apk = Updater.download(a, token, File(act.cacheDir, "update")) { done, total ->
                    if (total > 0) act.runOnUiThread { bar.isIndeterminate = false; bar.progress = (done * 1000 / total).toInt() }
                }
                Updater.install(act, apk)   // Android now shows its own "Update this app?" screen
                ui { dlg.dismiss() }
            } catch (e: Updater.AuthException) {
                ui { dlg.dismiss(); askToken("GitHub did not accept the token.") { proceed(a) } }
            } catch (e: Exception) {
                ui { dlg.dismiss(); alert("Update failed", e.message) }
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
                    "GitHub only lets signed-in users download debug builds. Paste a GitHub token once; " +
                    "a fine-grained token with read-only access to public repositories is enough. " +
                    "It is kept only on this phone."
            })
            addView(input)
        }
        val b = AlertDialog.Builder(act).setTitle("GitHub token").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { token = t; onSaved() }
            }
            .setNegativeButton("Cancel", null)
        if (token != null) b.setNeutralButton("Remove token") { _, _ -> token = null }
        b.show()
    }
}
