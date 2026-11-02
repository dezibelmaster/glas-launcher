package de.glas.kids

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** The app kids mode is about (YouTube Kids or YouTube), and the other installed apps. */
object Apps {
    /**
     * Apps for kids mode, best first. YouTube Kids if it is installed; on Google TV it is no longer offered,
     * there the kids profiles live in the normal YouTube app.
     */
    val TARGETS = listOf(
        "com.google.android.youtube.tvkids",
        "com.google.android.apps.youtube.kids",
        "com.google.android.youtube.tv",
        "com.amazon.firetv.youtube",
        "com.google.android.youtube",
    )

    /** Home screens: when one of these appears the Home button was pressed, which is not a forbidden app. */
    val HOME_SCREENS = setOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.amazon.tv.launcher",
        "de.glas.launcher",
    )

    /**
     * Google sign-in and account screens. YouTube opens them for profiles and parent settings;
     * they don't lead to other apps.
     */
    val SYSTEM_ALLOWED = setOf("com.google.android.gms", "com.google.android.gsf")

    class App(val pkg: String, val label: String)

    /** The installed app kids mode opens and allows. */
    fun target(ctx: Context): String? = TARGETS.firstOrNull { installed(ctx, it) && launchIntent(ctx, it) != null }

    /** Name for the texts, e.g. "YouTube Kids" or "YouTube". */
    fun targetName(ctx: Context) = target(ctx)?.let { label(ctx, it) } ?: "YouTube"

    private fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    fun launchIntent(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val intent = launchIntent(ctx, pkg) ?: return false
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    /** Wide TV banner of an app if it has one, otherwise null. */
    fun banner(ctx: Context, pkg: String): Drawable? = runCatching { ctx.packageManager.getApplicationBanner(pkg) }.getOrNull()

    fun icon(ctx: Context, pkg: String): Drawable? = runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Apps that can be opened from the TV's app list, without Glas Kids and the kids mode app. */
    fun launchable(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val found = linkedSetOf<String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), PackageManager.MATCH_ALL)
                .forEach { found += it.activityInfo.packageName }
        }
        val target = target(ctx)
        return found.filter { it != ctx.packageName && it != target }
            .map { App(it, label(ctx, it)) }
            .sortedBy { it.label.lowercase() }
    }
}
