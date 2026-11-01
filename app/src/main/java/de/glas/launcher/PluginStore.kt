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

    val catalog = listOf(
        Entry("Overflight", "Luftaufnahmen von Apple, vom Projectivy-Entwickler", "spocky/projectivy-plugin-wallpaper-overflight", "tv.projectivy.plugin.wallpaperprovider.overflight"),
        Entry("Bing-Foto des Tages", "Tägliche Fotos von Bing, vom Projectivy-Entwickler", "spocky/projectivy-plugin-wallpaper-bingwallpaper", "tv.projectivy.plugin.wallpaperprovider.photooftheday"),
        Entry("Aerial Views", "Luftaufnahmen aus aller Welt, auch als Bildschirmschoner", "theothernt/AerialViews", "com.neilturner.aerialviews"),
        Entry("Jellyfin Wallpapers", "Film-Hintergründe von deinem Jellyfin-Server", "Mikescher/projectivity-jellyfin-wallpaper", "com.blackforestbytes.projectivy.jellyfinwallpaper"),
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
    class Release(val tag: String, val apks: List<Pair<String, String>>)

    fun latestRelease(repo: String): Release {
        val release = JSONObject(get("https://api.github.com/repos/$repo/releases/latest"))
        val assets = release.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".apk", true) }
            .map { it.getString("name") to it.getString("browser_download_url") }
        return Release(release.optString("tag_name"), apks)
    }

    /** Downloads one APK of [repo]'s newest release, chosen by [pick], and opens Android's installer. */
    fun installRelease(ctx: Context, label: String, repo: String, pick: (List<Pair<String, String>>) -> Pair<String, String>?) {
        // Before Android 8 this is one system-wide switch ("unknown sources") that the installer asks about itself
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.toast("Erlaube dem Glas Launcher zuerst, Apps zu installieren, und wähle es dann noch einmal.")
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        ctx.toast("$label wird heruntergeladen …")
        Thread {
            val result = runCatching {
                val apk = pick(latestRelease(repo).apks) ?: throw IllegalStateException("$label bietet gerade keine APK-Datei an")
                download(ctx, apk.second, repo.substringAfter('/') + ".apk")
            }
            main.post {
                val file = result.getOrNull()
                if (file == null) {
                    ctx.toast(result.exceptionOrNull()?.message ?: "$label ließ sich nicht herunterladen")
                    return@post
                }
                val uri = Uri.parse("content://${ctx.packageName}.apk/${file.name}")
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }.onFailure { ctx.toast("Der Installer lässt sich nicht öffnen") }
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
                throw IllegalStateException("GitHub antwortet mit Fehler $code")
            }
        }
        throw IllegalStateException("Zu viele Weiterleitungen")
    }
}

/** Hands a downloaded APK to Android's installer, which may not read the launcher's private files directly. */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val dir = File(context!!.cacheDir, "apk")
        val file = File(dir, uri.lastPathSegment ?: "")
        require(file.parentFile == dir && file.exists()) { "Unbekannte Datei" }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
