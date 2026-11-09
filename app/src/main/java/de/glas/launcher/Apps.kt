package de.glas.launcher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import java.util.Locale

class AppEntry(val component: String, val pkg: String, val label: String, val icon: Drawable, val launch: Intent)

object Apps {
    /** Label pieces for the first-start dock. Shortest match wins, so "YouTube" beats "YouTube Kids". */
    private val defaults = listOf("youtube", "netflix", "magenta", "jellyfin")

    fun installed(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                AppEntry("$pkg/${ri.activityInfo.name}", pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), launch)
            }
            .sortedBy { it.label.lowercase(Locale.GERMANY) }
    }

    fun defaultDock(apps: List<AppEntry>): List<String> = defaults.mapNotNull { key ->
        apps.filter { it.label.lowercase(Locale.ROOT).contains(key) }.minByOrNull { it.label.length }?.component
    }
}
