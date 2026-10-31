package it.snel.melamur.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import it.snel.melamur.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Updates from GitHub. A release build updates to the newest GitHub release
 * (melamur.apk). A debug build updates to the debug APK of the newest
 * successful CI build on main; GitHub only serves those artifacts to signed-in
 * users, so that needs a GitHub token. Both are installed with Android's
 * PackageInstaller, which asks the user to confirm.
 */
object Updater {
    private const val API = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}"
    private const val DEBUG_ARTIFACT = "melamur-debug.apk"
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** An update that can be installed. */
    class Available(val title: String, val details: String, val url: String, val needsToken: Boolean)

    sealed class Result {
        class UpToDate(val message: String) : Result()
        class Update(val available: Available) : Result()
        class Failed(val message: String) : Result()
    }

    /** GitHub refused the download: no token, or a token without access. */
    class AuthException : Exception("GitHub needs a valid token to download this build")

    val isDebugBuild get() = BuildConfig.DEBUG

    /** Which build is installed, e.g. "debug build #57" or "0.2.0". */
    fun installedLabel(): String =
        if (isDebugBuild) (if (BuildConfig.CI_RUN > 0) "debug build #${BuildConfig.CI_RUN}" else "local debug build")
        else BuildConfig.VERSION_NAME

    private fun get(url: String, token: String?): Pair<Int, String> {
        val rb = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MelaMur/${BuildConfig.VERSION_NAME}")
        if (!token.isNullOrBlank()) rb.header("Authorization", "Bearer $token")
        http.newCall(rb.build()).execute().use { r -> return r.code to (r.body?.string() ?: "") }
    }

    /** Look for a newer build. Network: call off the main thread. */
    fun check(token: String?): Result = try {
        if (isDebugBuild) checkDebug(token) else checkRelease(token)
    } catch (e: Exception) {
        Result.Failed("Could not reach GitHub: ${e.message}")
    }

    private fun checkRelease(token: String?): Result {
        val (code, body) = get("$API/releases/latest", token)
        if (code == 404) return Result.UpToDate("No release has been published yet.")
        if (code != 200) return Result.Failed("GitHub answered $code.")
        val j = JSONObject(body)
        val tag = j.getString("tag_name")
        if (tag.removePrefix("v") == BuildConfig.VERSION_NAME) return Result.UpToDate("You have the newest release ($tag).")
        val assets = j.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name") == "melamur.apk")
                return Result.Update(Available("Release $tag", j.optString("name", tag),
                    a.getString("browser_download_url"), needsToken = false))
        }
        return Result.Failed("Release $tag has no melamur.apk.")
    }

    private fun checkDebug(token: String?): Result {
        val (code, body) = get("$API/actions/workflows/android.yml/runs?branch=main&event=push&status=success&per_page=1", token)
        if (code != 200) return Result.Failed("GitHub answered $code.")
        val runs = JSONObject(body).getJSONArray("workflow_runs")
        if (runs.length() == 0) return Result.UpToDate("No successful build on main yet.")
        val run = runs.getJSONObject(0)
        val number = run.getInt("run_number")
        if (number <= BuildConfig.CI_RUN) return Result.UpToDate("You have the newest debug build (#${BuildConfig.CI_RUN}).")
        val (c2, b2) = get("$API/actions/runs/${run.getLong("id")}/artifacts", token)
        if (c2 != 200) return Result.Failed("GitHub answered $c2.")
        val arts = JSONObject(b2).getJSONArray("artifacts")
        for (i in 0 until arts.length()) {
            val a = arts.getJSONObject(i)
            if (a.getString("name") == DEBUG_ARTIFACT && !a.optBoolean("expired")) {
                val title = run.optString("display_title").lineSequence().firstOrNull().orEmpty()
                val sha = run.optString("head_sha").take(7)
                return Result.Update(Available("Debug build #$number", "$title ($sha)",
                    "$API/actions/artifacts/${a.getLong("id")}/zip", needsToken = true))
            }
        }
        return Result.Failed("Build #$number has no $DEBUG_ARTIFACT (expired?).")
    }

    /**
     * Download [a] into [dir] and return the APK. Artifacts may arrive as a zip
     * holding the APK, or as the APK itself; both are handled.
     */
    fun download(a: Available, token: String?, dir: File, progress: (done: Long, total: Long) -> Unit): File {
        dir.mkdirs()
        val raw = File(dir, "download.tmp"); val apk = File(dir, "update.apk")
        raw.delete(); apk.delete()
        val rb = Request.Builder().url(a.url).header("User-Agent", "MelaMur/${BuildConfig.VERSION_NAME}")
        if (a.needsToken) {
            if (token.isNullOrBlank()) throw AuthException()
            rb.header("Authorization", "Bearer $token")   // OkHttp drops it on the redirect to the file host
        }
        http.newCall(rb.build()).execute().use { r ->
            if (r.code == 401 || r.code == 403 || r.code == 404 && a.needsToken) throw AuthException()
            if (!r.isSuccessful) throw IllegalStateException("download failed: HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException("empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                raw.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n; progress(done, total)
                    }
                }
            }
        }
        ZipFile(raw).use { z ->
            if (z.getEntry("AndroidManifest.xml") != null) { raw.renameTo(apk); return apk }   // the APK itself
            val e = z.entries().asSequence().firstOrNull { it.name.endsWith(".apk") }
                ?: throw IllegalStateException("the download contains no APK")
            z.getInputStream(e).use { i -> apk.outputStream().use { o -> i.copyTo(o) } }
        }
        raw.delete()
        return apk
    }

    /** Hand [apk] to the system installer; it asks the user to confirm (see InstallReceiver). */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("melamur.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            s.commit(status.intentSender)
        }
    }
}
