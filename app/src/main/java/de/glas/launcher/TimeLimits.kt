package de.glas.launcher

import android.content.Context
import java.util.Calendar

/** Times of day in which an app may be opened without the PIN, e.g. "06:00-20:00". */
object TimeLimits {
    private val presets = listOf(null, "06:00-18:00", "06:00-19:00", "06:00-20:00", "06:00-21:00", "14:00-20:00")

    fun label(window: String?) = window?.let { "${it.substringBefore('-')} bis ${it.substringAfter('-')} Uhr" } ?: "immer erlaubt"

    fun allowedNow(window: String): Boolean {
        fun minutes(t: String) = t.substringBefore(':').toInt() * 60 + t.substringAfter(':').toInt()
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        return now in minutes(window.substringBefore('-')) until minutes(window.substringAfter('-'))
    }

    /** Lets the parent pick a time window for an app; needs the PIN, and sets one up first if there is none. */
    fun choose(ctx: Context, prefs: Prefs, label: String, pkg: String, after: () -> Unit) {
        val pick = {
            val current = prefs.timeWindows[pkg]
            GlassMenu.show(ctx, "Zeitfenster für $label", presets.map { w ->
                GlassMenu.Item(label(w) + if (w == current) "  ✓" else "") {
                    prefs.timeWindows = if (w == null) prefs.timeWindows - pkg else prefs.timeWindows + (pkg to w)
                    after()
                }
            }, focus = presets.indexOf(current).coerceAtLeast(0),
                footer = "Außerhalb des Zeitfensters lässt sich die App nur mit dem PIN öffnen.")
        }
        if (prefs.pinHash == null) Pin.create(ctx, prefs, pick) else Pin.verify(ctx, prefs, "PIN eingeben", pick)
    }
}
