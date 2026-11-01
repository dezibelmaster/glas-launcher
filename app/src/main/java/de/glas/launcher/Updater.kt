package de.glas.launcher

import android.content.Context

/**
 * Looks for a newer Glas Launcher among the releases of the GitHub repository set under
 * Einstellungen → Updates, and installs it. Releases carry an APK named GlasLauncher-x.y.apk.
 */
object Updater {
    class Update(val version: String)

    fun installedVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** The newer version on GitHub, or null if there is none. Blocking; throws when GitHub can't be reached. */
    fun check(ctx: Context, repo: String): Update? {
        val release = PluginStore.latestRelease(repo)
        val version = release.tag.trimStart('v', 'V')
        if (release.apks.none { it.first.startsWith("GlasLauncher", true) }) return null
        return if (newer(version, installedVersion(ctx))) Update(version) else null
    }

    fun install(ctx: Context, repo: String) = PluginStore.installRelease(ctx, "Glas Launcher", repo) { apks ->
        apks.firstOrNull { it.first.startsWith("GlasLauncher", true) }
    }

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
