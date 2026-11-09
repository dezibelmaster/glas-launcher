package de.glas.browser

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class GlasBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Gecko shows pages in sandboxed helper processes of this app. Android starts this class there too,
        // but those processes may not touch the app's storage; reading the settings there made them crash.
        if (!isMainProcess()) return
        runCatching { I18n.init(this) }
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrNull()
        return name == null || name == packageName
    }
}

/** The last things that went wrong, for "Mehr → Fehlerbericht" and the Android log (tag GlasBrowser). */
object Diag {
    private val entries = ArrayDeque<String>()
    private val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)

    @Synchronized
    fun add(what: String) {
        android.util.Log.w("GlasBrowser", what)
        entries.addFirst(time.format(java.util.Date()) + "  " + what)
        while (entries.size > 30) entries.removeLast()
    }

    @Synchronized
    fun all(): List<String> = entries.toList()

    /**
     * Everything needed to find a fault: device, versions, the steps above, and the engine's own warnings
     * and errors from the Android log (an app may read its own log without any permission).
     */
    fun report(ctx: Context): String {
        val b = StringBuilder()
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        b.append("Glas Browser ").append(v).append(" · GeckoView ").append(org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION).append('\n')
        b.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(") · ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        val mem = android.app.ActivityManager.MemoryInfo()
        ctx.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mem)
        b.append("RAM frei ").append(mem.availMem / 1_000_000).append(" von ").append(mem.totalMem / 1_000_000).append(" MB\n\n")
        all().reversed().forEach { b.append(it).append('\n') }
        b.append("\n--- logcat ---\n")
        val keep = Regex("Gecko|gecko|Moz|moz|GlasBrowser|AndroidRuntime|ChildProcess|libc|DEBUG|crash|Crash|FATAL|denied|SELinux|avc:")
        val lines = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "3000", "-v", "time"))
            p.inputStream.bufferedReader().readLines()
        }.getOrElse { listOf("logcat: " + it.message) }
        lines.filter { keep.containsMatchIn(it) && !it.contains(" V/") && !it.contains(" D/") }.takeLast(250).forEach { b.append(it).append('\n') }
        return b.toString()
    }
}

/**
 * The Firefox engine (GeckoView) with its WebExtensions: the built-in Glas Bridge that lets the
 * app talk to pages, and the extensions the user installs from addons.mozilla.org.
 */
object Engine {
    const val BRIDGE_ID = "bridge@glas.de"
    private const val AMO = "https://addons.mozilla.org/firefox/downloads/latest/"

    /** Recommended extensions that work well on a TV: name, short description, addons.mozilla.org slug. */
    class Suggestion(val name: String, val about: String, val slug: String)

    val suggestions get() = listOf(
        Suggestion("uBlock Origin", tr("Blockiert Werbung und Tracker, auch auf YouTube"), "ublock-origin"),
        Suggestion("SponsorBlock", tr("Überspringt Sponsoren-Abschnitte in YouTube-Videos"), "sponsorblock"),
        Suggestion("Consent-O-Matic", tr("Beantwortet Cookie-Banner automatisch"), "consent-o-matic"),
        Suggestion("Dark Reader", tr("Dunkle Ansicht für helle Seiten"), "darkreader"),
        Suggestion("Return YouTube Dislike", tr("Zeigt wieder die Daumen-runter-Zahl auf YouTube"), "return-youtube-dislikes"),
        Suggestion("Privacy Badger", tr("Lernt Tracker zu blockieren"), "privacy-badger17"),
    )

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile var bridge: WebExtension? = null
        private set
    private val bridgeWaiters = mutableListOf<(WebExtension) -> Unit>()

    /** Asks the user before an extension gets its permissions; set by the open browser window. */
    var installPrompt: ((WebExtension, Array<String>, (Boolean) -> Unit) -> Unit)? = null

    fun runtime(ctx: Context): GeckoRuntime {
        runtime?.let { return it }
        val settings = GeckoRuntimeSettings.Builder()
            // Firefox's own tracking protection in its standard setting, as in the Firefox app
            .contentBlocking(ContentBlocking.Settings.Builder()
                .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .build())
            .doubleTapZoomingEnabled(false)
            .inputAutoZoomEnabled(false)
            // Page errors go to the Android log, where the error report picks them up
            .consoleOutput(true)
            .locales(arrayOf(I18n.lang))
            .build()
        val rt = GeckoRuntime.create(ctx.applicationContext, settings)
        runtime = rt
        rt.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollection: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                val ask = installPrompt
                if (ask == null) {
                    result.complete(WebExtension.PermissionPromptResponse(false, false, false))
                } else {
                    main.post {
                        ask(extension, permissions + origins) { ok -> result.complete(WebExtension.PermissionPromptResponse(ok, false, false)) }
                    }
                }
                return result
            }

            override fun onUpdatePrompt(extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollection: Array<String>) =
                GeckoResult.allow()
        })
        rt.webExtensionController.ensureBuiltIn("resource://android/assets/bridge/", BRIDGE_ID).accept({ ext ->
            if (ext != null) main.post {
                bridge = ext
                bridgeWaiters.forEach { it(ext) }
                bridgeWaiters.clear()
            }
        }, { e -> Diag.add("Glas Bridge: " + (e?.message ?: e?.javaClass?.simpleName)) })
        return rt
    }

    /** Runs [action] with the bridge extension as soon as it is installed. */
    fun withBridge(action: (WebExtension) -> Unit) {
        bridge?.let { return action(it) }
        bridgeWaiters += action
    }

    /** The extensions the user installed (without the built-in bridge). */
    fun installed(ctx: Context, done: (List<WebExtension>) -> Unit) {
        runtime(ctx).webExtensionController.list().accept({ list ->
            main.post { done(list.orEmpty().filter { !it.isBuiltIn && it.id != BRIDGE_ID }) }
        }, { main.post { done(emptyList()) } })
    }

    /** Installs from addons.mozilla.org by its short name (e.g. "ublock-origin") or a full link. */
    fun install(ctx: Context, slugOrUrl: String, done: (WebExtension?, Throwable?) -> Unit) {
        val s = slugOrUrl.trim()
        val url = when {
            s.endsWith(".xpi") -> s
            s.contains("addons.mozilla.org") -> {
                // A listing page like https://addons.mozilla.org/de/firefox/addon/ublock-origin/
                val slug = Regex("/addon/([^/?#]+)").find(s)?.groupValues?.get(1) ?: s
                "$AMO$slug/latest.xpi"
            }
            else -> "$AMO${s.trim('/')}/latest.xpi"
        }
        runtime(ctx).webExtensionController.install(url).accept(
            { ext -> main.post { done(ext, null) } },
            { e -> Diag.add("Erweiterung $url: " + (e?.message ?: e?.javaClass?.simpleName)); main.post { done(null, e) } },
        )
    }

    fun uninstall(ctx: Context, ext: WebExtension, done: () -> Unit) {
        runtime(ctx).webExtensionController.uninstall(ext).accept({ main.post(done) }, { main.post(done) })
    }

    fun setEnabled(ctx: Context, ext: WebExtension, on: Boolean, done: () -> Unit) {
        val c = runtime(ctx).webExtensionController
        val r = if (on) c.enable(ext, WebExtensionController.EnableSource.USER) else c.disable(ext, WebExtensionController.EnableSource.USER)
        r.accept({ main.post(done) }, { main.post(done) })
    }

    /** Short, readable reason why an install failed. */
    fun reason(e: Throwable?): String {
        val msg = e?.message.orEmpty()
        return when {
            e is WebExtension.InstallException && e.code == WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED -> tr("abgebrochen")
            e is WebExtension.InstallException && e.code == WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE -> tr("keine Verbindung")
            e is WebExtension.InstallException && e.code == WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE -> tr("passt nicht zu dieser Firefox-Version")
            e is WebExtension.InstallException && e.code == WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE -> tr("nicht gefunden")
            msg.isNotBlank() -> msg.take(80)
            else -> e?.javaClass?.simpleName ?: "?"
        }
    }
}
