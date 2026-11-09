package de.glas.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.json.JSONObject

/**
 * The Glas Browser: a separate app on the Firefox engine (module "browser"). The launcher opens it,
 * installs and updates it from the GitHub releases (the APK that fits the TV's processor), checks the
 * parental lock, and passes on the phone remote's commands.
 */
object GlasBrowser {
    const val PKG = "de.glas.browser"
    private const val ACTIVITY = "de.glas.browser.BrowserActivity"
    private val REMOTE = Uri.parse("content://de.glas.browser.remote")

    /** The built-in browser of launcher 2.41/2.42, which some docks still hold. */
    const val OLD_COMPONENT = "de.glas.launcher/de.glas.launcher.BrowserActivity"

    fun version(ctx: Context): String? =
        runCatching { ctx.packageManager.getPackageInfo(PKG, 0).versionName }.getOrNull()

    fun installed(ctx: Context) = version(ctx) != null

    /** Why the browser needs the PIN right now (locked, or outside its time window), or null. */
    fun restricted(ctx: Context): String? {
        val prefs = Prefs(ctx)
        if (prefs.pinHash == null) return null
        if (PKG in prefs.locked) return tr("{0} ist gesperrt", "Glas Browser")
        val window = prefs.timeWindows[PKG]
        if (window != null && !TimeLimits.allowedNow(window)) return tr("{0} ist nur {1} erlaubt", "Glas Browser", TimeLimits.label(window))
        return null
    }

    fun open(ctx: Context, url: String?) {
        val intent = Intent(Intent.ACTION_MAIN).setClassName(PKG, ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("lang", I18n.lang)
        if (!url.isNullOrBlank()) intent.putExtra("url", url)
        ctx.startActivity(intent)
    }

    /** Sends one command to the browser app; answers with its state, or null if it isn't there. */
    fun call(ctx: Context, cmd: JSONObject): JSONObject? = runCatching {
        val reply = ctx.contentResolver.call(REMOTE, "cmd", cmd.put("lang", I18n.lang).toString(), null)
        JSONObject(reply?.getString("json") ?: return null)
    }.getOrNull()

    // ---- Installing from GitHub ----

    class Available(val version: String, val name: String, val url: String)

    /** The newest browser APK for this TV's processor among the recent releases. Blocking. */
    fun available(repo: String): Available? {
        val apks = PluginStore.recentApks(repo)
        for (abi in Build.SUPPORTED_ABIS) {
            val hit = apks.firstOrNull { it.first.startsWith("GlasBrowser-", true) && it.first.endsWith("-$abi.apk", true) } ?: continue
            val version = Regex("""GlasBrowser-(\d+(\.\d+)*)""").find(hit.first)?.groupValues?.get(1) ?: continue
            return Available(version, hit.first, hit.second)
        }
        return null
    }

    /** Installs the browser or an update, after looking what GitHub offers. */
    fun installOrUpdate(ctx: Context, repo: String?) {
        if (repo.isNullOrBlank()) return ctx.toast(tr("Zuerst unter Updates die Update-Quelle (GitHub) festlegen"))
        ctx.toast(tr("Suche Glas Browser …"))
        Thread {
            val found = runCatching { available(repo) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val a = found.getOrNull()
                val have = version(ctx)
                when {
                    found.isFailure -> ctx.toast(tr("GitHub ist gerade nicht erreichbar"))
                    a == null -> ctx.toast(tr("Für diesen Fernseher ({0}) gibt es noch keine Glas-Browser-Datei in den Releases", Build.SUPPORTED_ABIS.firstOrNull() ?: "?"))
                    have != null && !Updater.newer(a.version, have) -> ctx.toast(tr("Glas Browser {0} ist aktuell", have))
                    else -> PluginStore.installRelease(ctx, "Glas Browser", repo, fromRecent = true) { apks -> apks.firstOrNull { it.first == a.name } }
                }
            }
        }.start()
    }
}

/**
 * Lets the Glas Browser show the QR code of "Einstellen per Handy": call("current") gives the address
 * while it runs, call("start") switches it on first. Only Glas apps may ask (de.glas.permission.REMOTE).
 */
class PhoneProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!
        if (method == "start") PhoneRemote.start(ctx)
        return Bundle().apply { putString("url", if (PhoneRemote.running) PhoneRemote.url() else null) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
