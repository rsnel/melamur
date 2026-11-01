package it.snel.melamur

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * App language: Maltese (default) or British English, chosen with the flag in
 * the top bar. Every activity and service wraps its context with [wrap], so
 * resources (values-mt / values) follow the choice; [T] gives code without a
 * context (database labels, planner progress) the same strings.
 */
object Lang {
    const val MT = "mt"
    const val EN = "en"
    private const val KEY = "lang"

    fun current(ctx: Context): String =
        ctx.getSharedPreferences("melamur", Context.MODE_PRIVATE).getString(KEY, MT) ?: MT

    fun set(ctx: Context, lang: String) =
        ctx.getSharedPreferences("melamur", Context.MODE_PRIVATE).edit().putString(KEY, lang).commit()

    fun locale(lang: String): Locale = if (lang == EN) Locale.UK else Locale("mt", "MT")

    /** [base] with the chosen language applied; also makes it the source for [T]. */
    fun wrap(base: Context): Context {
        val loc = locale(current(base))
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        val ctx = base.createConfigurationContext(cfg)
        T.ctx = ctx
        return ctx
    }
}

/** Localised strings for code that has no context of its own. */
object T {
    @Volatile var ctx: Context? = null
    fun s(id: Int, vararg args: Any): String = ctx?.getString(id, *args) ?: ""
    /** Language tag of the strings in use (part of cache keys for generated labels). */
    val tag: String get() = ctx?.resources?.configuration?.locales?.get(0)?.language ?: ""
}
