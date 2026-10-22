package it.snel.melamur.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.time.LocalDate

/**
 * Holds the imported MPT dataset. On first run the user picks an .sqlite file
 * (SQL-FORMAT.txt); it is copied into the app's private storage and opened
 * read-only. Reload re-opens it; a fresh import replaces it atomically.
 */
object DbManager {
    @Volatile var db: MptDb? = null
        private set

    private fun file(ctx: Context) = File(ctx.filesDir, "mpt.sqlite")
    fun isImported(ctx: Context) = file(ctx).let { it.exists() && it.length() > 0 }

    /** Open the already-imported database; returns true on success. */
    fun openExisting(ctx: Context): Boolean {
        if (!isImported(ctx)) return false
        return try { db?.close(); db = MptDb.open(file(ctx)); true }
        catch (e: Exception) { Log.w(TAG, "open failed", e); db = null; false }
    }

    fun reload(ctx: Context): Boolean = openExisting(ctx)

    /** Copy [uri] into private storage, validate schema, open. */
    fun import(ctx: Context, uri: Uri): Result<Unit> {
        val tmp = File(ctx.filesDir, "mpt.import.tmp")
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return Result.failure(IllegalStateException("cannot read the selected file"))
            // validate before replacing the live file
            val test = MptDb.open(tmp)
            val schema = test.meta("schema_version"); test.close()
            if (schema != "1") {
                tmp.delete()
                return Result.failure(IllegalStateException("not a MelaMur dataset (schema_version=$schema)"))
            }
            db?.close(); db = null
            val dest = file(ctx)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            db = MptDb.open(dest)
            Result.success(Unit)
        } catch (e: Exception) {
            tmp.delete(); Log.w(TAG, "import failed", e); Result.failure(e)
        }
    }

    /** Current dataset version for today (or null if no DB open). */
    fun versionToday(): Int? = db?.versionFor(LocalDate.now(Malta.ZONE))

    private const val TAG = "DbManager"
}
