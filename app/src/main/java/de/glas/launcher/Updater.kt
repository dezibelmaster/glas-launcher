package de.glas.launcher

import android.content.Context

/**
 * Looks for a newer Glas Launcher among the releases of the GitHub repository set under
 * Einstellungen → Updates, and installs it. Releases carry an APK named GlasLauncher-x.y.apk.
 */
object Updater {
    class Update(val version: String, val notes: String)

    fun installedVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** The newer version on GitHub, or null if there is none. Blocking; throws when GitHub can't be reached. */
    fun check(ctx: Context, repo: String): Update? {
        val newest = newest(PluginStore.recentReleases(repo)) ?: return null
        return if (newer(newest.second, installedVersion(ctx))) Update(newest.second, newest.first.notes) else null
    }

    /**
     * The release with the highest Glas Launcher version that has its APK attached. Not simply
     * the latest release: that may be one for the Glas Browser only, or one without the APK.
     */
    private fun newest(releases: List<PluginStore.Release>): Pair<PluginStore.Release, String>? =
        releases.mapNotNull { r ->
            val apk = r.apks.firstOrNull { isLauncherApk(it.first) } ?: return@mapNotNull null
            // The version from the APK name (GlasLauncher-2.46.apk) or else from the tag
            val version = versionIn(apk.first) ?: versionIn(r.tag) ?: return@mapNotNull null
            r to version
        }.reduceOrNull { a, b -> if (newer(b.second, a.second)) b else a }

    private fun isLauncherApk(name: String) = name.startsWith("GlasLauncher", true) && name.endsWith(".apk", true)

    /** Installs the highest version found among the last releases. */
    fun install(ctx: Context, repo: String) = PluginStore.installRelease(ctx, "Glas Launcher", repo, fromRecent = true) { apks ->
        apks.filter { isLauncherApk(it.first) }
            .reduceOrNull { a, b -> if (newer(versionIn(b.first) ?: "0", versionIn(a.first) ?: "0")) b else a }
    }

    private fun versionIn(text: String) = Regex("""\d+(\.\d+)+""").find(text)?.value

    /** Compares versions like 2.25 and 2.3 part by part as numbers (2.25 is newer than 2.3). */
    fun newer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
