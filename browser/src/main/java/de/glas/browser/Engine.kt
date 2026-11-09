package de.glas.browser

import android.app.Application
import android.content.Context
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
        I18n.init(this)
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
            .consoleOutput(false)
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
        }, { /* Without the bridge the pointer still works; only scrolling and video buttons need it */ })
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
            { e -> main.post { done(null, e) } },
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
