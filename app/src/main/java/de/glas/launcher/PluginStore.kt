package de.glas.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Projectivy wallpaper plugins that can be installed from the launcher. Each one is fetched
 * from the latest release on its author's GitHub page and handed to Android's installer.
 */
object PluginStore {
    class Entry(val name: String, val about: String, val repo: String, val pkg: String)

    val catalog get() = listOf(
        Entry("Overflight", tr("Luftaufnahmen von Apple, vom Projectivy-Entwickler"), "spocky/projectivy-plugin-wallpaper-overflight", "tv.projectivy.plugin.wallpaperprovider.overflight"),
        Entry(tr("Bing-Foto des Tages"), tr("Tägliche Fotos von Bing, vom Projectivy-Entwickler"), "spocky/projectivy-plugin-wallpaper-bingwallpaper", "tv.projectivy.plugin.wallpaperprovider.photooftheday"),
        Entry("Aerial Views", tr("Luftaufnahmen aus aller Welt, auch als Bildschirmschoner"), "theothernt/AerialViews", "com.neilturner.aerialviews"),
        Entry("Jellyfin Wallpapers", tr("Film-Hintergründe von deinem Jellyfin-Server"), "Mikescher/projectivity-jellyfin-wallpaper", "com.blackforestbytes.projectivy.jellyfinwallpaper"),
    )

    private val main = Handler(Looper.getMainLooper())

    fun isInstalled(ctx: Context, e: Entry) =
        e.pkg.isNotEmpty() && runCatching { ctx.packageManager.getPackageInfo(e.pkg, 0) }.isSuccess

    /** Downloads the plugin's newest APK and opens the installer. Reports progress as toasts. */
    fun install(ctx: Context, e: Entry) = installRelease(ctx, e.name, e.repo) { apks ->
        // Some projects publish several builds; prefer the plain or GitHub one over debug builds
        apks.firstOrNull { it.first.contains("github", true) }
            ?: apks.firstOrNull { !it.first.contains("debug", true) }
            ?: apks.firstOrNull()
    }

    /** Newest release on GitHub: its tag and APK files as name to download address. Blocking. */
    class Release(val tag: String, val apks: List<Pair<String, String>>, val notes: String = "")

    fun latestRelease(repo: String): Release {
        val release = JSONObject(get("https://api.github.com/repos/$repo/releases/latest"))
        val assets = release.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".apk", true) }
            .map { it.getString("name") to it.getString("browser_download_url") }
        return Release(release.optString("tag_name"), apks, release.optString("body").trim())
    }

    /** An install that waits for the permission to install apps. */
    private var pending: ((Context) -> Unit)? = null

    /** Called when a screen comes back: continues a waiting install if the permission is there now. */
    /** APK files of the last releases, newest first; for apps that aren't attached to every release. */
    fun recentApks(repo: String): List<Pair<String, String>> {
        val releases = org.json.JSONArray(get("https://api.github.com/repos/$repo/releases?per_page=15"))
        return (0 until releases.length()).map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            .flatMap { r ->
                val assets = r.getJSONArray("assets")
                (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { it.getString("name").endsWith(".apk", true) }
                    .map { it.getString("name") to it.getString("browser_download_url") }
            }
    }

    fun resumePending(ctx: Context) {
        val action = pending ?: return
        pending = null
        // Try even if Android says no: on some TVs the answer is wrong, and the installer asks again if needed
        action(ctx)
    }

    /** Downloads one APK of [repo]'s newest release, chosen by [pick], and opens Android's installer. */
    fun installRelease(ctx: Context, label: String, repo: String, fromRecent: Boolean = false, pick: (List<Pair<String, String>>) -> Pair<String, String>?) {
        // Some TVs (TCL) show the app as allowed to install apps while Android still reports it isn't.
        // So the permission screen comes only once; after that the download goes ahead and Android's
        // installer checks the permission itself.
        val prefs = ctx.getSharedPreferences("glas", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls() &&
            !prefs.getBoolean("install.asked", false)
        ) {
            prefs.edit().putBoolean("install.asked", true).apply()
            // Carry on by itself once the user comes back
            pending = { c -> installRelease(c, label, repo, fromRecent, pick) }
            ctx.toast(tr("Erlaube dem Glas Launcher, Apps zu installieren, und drücke dann Zurück."))
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        ctx.toast(tr("{0} wird heruntergeladen …", label))
        Thread {
            val result = runCatching {
                val apk = pick(if (fromRecent) recentApks(repo) else latestRelease(repo).apks) ?: throw IllegalStateException(tr("{0} bietet gerade keine APK-Datei an", label))
                download(ctx, apk.second, if (fromRecent) apk.first else repo.substringAfter('/') + ".apk")
            }
            main.post {
                val file = result.getOrNull()
                if (file == null) {
                    // Say what went wrong, so a failure on the TV can be told apart
                    val e = result.exceptionOrNull()
                    ctx.toast(tr("{0} ließ sich nicht herunterladen", label) + (e?.let { " (${it.message ?: it.javaClass.simpleName})" } ?: ""))
                    return@post
                }
                ctx.toast(tr("Heruntergeladen ({0}). Der Installer öffnet sich …", Memory.mb(file.length())))
                val uri = Uri.parse("content://${ctx.packageName}.apk/${file.name}")
                fun installer(action: String) = Intent(action)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                @Suppress("DEPRECATION")
                runCatching { ctx.startActivity(installer(Intent.ACTION_VIEW)) }
                    .recoverCatching { ctx.startActivity(installer(Intent.ACTION_INSTALL_PACKAGE)) }
                    .onFailure { ctx.toast(tr("Der Installer lässt sich nicht öffnen") + " (${it.javaClass.simpleName})") }
            }
        }.start()
    }

    private fun download(ctx: Context, url: String, name: String): File {
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, name)
        val conn = open(url)
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it, 64 * 1024) } }
        conn.disconnect()
        return file
    }

    private fun get(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "GlasLauncher (Android TV)")
            conn.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            val code = conn.responseCode
            if (code in 300..399) {
                u = URL(u, conn.getHeaderField("Location"))
                conn.disconnect()
            } else if (code == 200) {
                return conn
            } else {
                conn.disconnect()
                throw IllegalStateException(tr("GitHub antwortet mit Fehler {0}", code))
            }
        }
        throw IllegalStateException(tr("Zu viele Weiterleitungen"))
    }
}

/** Hands a downloaded APK to Android's installer, which may not read the launcher's private files directly. */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val dir = File(context!!.cacheDir, "apk")
        val file = File(dir, uri.lastPathSegment ?: "")
        require(file.parentFile == dir && file.exists()) { tr("Unbekannte Datei") }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
