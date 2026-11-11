package de.glas.browser

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * Glas Browser on the Firefox engine (GeckoView), with Firefox extensions such as uBlock Origin.
 *
 * With the TV remote the arrow keys move a mouse pointer, OK clicks, and at the edge of the screen the
 * page scrolls. The Menu key opens the options. The launcher's phone remote sends the same commands
 * through [RemoteProvider], plus typed addresses and text.
 */
class BrowserActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var gecko: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var bar: LinearLayout
    private lateinit var address: TextView
    private lateinit var star: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pointer: PointerView
    private val handler = Handler(Looper.getMainLooper())

    private var fullscreen = false
    // Video enlarged to the whole screen by the bridge when the page didn't allow real full screen
    private var filmPort: WebExtension.Port? = null
    private val immersive get() = fullscreen || filmPort != null
    private var pointerOn = true
    private var downTime = 0L
    private var pointerUntil = 0L
    private val hidePointer = Runnable { updatePointer() }

    var currentUrl = Store.HOME
        private set
    var currentTitle = ""
        private set
    private var canBack = false
    private var canForward = false
    val playing get() = playingPorts.isNotEmpty()
    val pointerEnabled get() = pointerOn

    // The bridge extension's connections, one per page and frame
    private val ports = mutableListOf<WebExtension.Port>()
    private val playingPorts = mutableSetOf<WebExtension.Port>()
    private val replies = HashMap<Int, (Any?) -> Unit>()
    private val nextId = AtomicInteger(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeLanguage(intent)
        instance = WeakReference(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bar = buildBar()
        column.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF8AB4F8.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        }
        column.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(3)))
        gecko = GeckoView(this)
        column.addView(gecko, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pointer = PointerView(this)
        root.addView(pointer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        root.post { pointer.place(root.width / 2f, root.height / 2.5f) }

        Engine.installPrompt = { ext, permissions, answer -> askInstall(ext, permissions, answer) }
        session = newSession()
        gecko.setSession(session)
        load(intent.urlExtra() ?: Store.lastUrl(this) ?: Store.HOME)

        if (!Store.hintShown(this)) {
            Store.setHintShown(this)
            toast(tr("Pfeiltasten bewegen den Zeiger, OK klickt. Menü-Taste: Adresse, Favoriten, Erweiterungen."))
        }
        if (!Store.offeredBlocker(this)) handler.postDelayed({ offerBlocker() }, 1500)
    }

    private fun Intent.urlExtra() = getStringExtra(EXTRA_URL) ?: dataString

    private fun takeLanguage(intent: Intent?) {
        val lang = intent?.getStringExtra(EXTRA_LANG) ?: return
        if (lang != Store.language(this)) {
            Store.setLanguage(this, lang)
            I18n.init(this, lang)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLanguage(intent)
        intent.urlExtra()?.let { load(it) }
    }

    override fun onResume() {
        super.onResume()
        instance = WeakReference(this)
        session.setActive(true)
    }

    override fun onPause() {
        super.onPause()
        // Videos and sound stop when the TV goes to another app or home
        session.setActive(false)
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        if (Engine.installPrompt != null) Engine.installPrompt = null
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }

    // ---- Page ----

    private fun newSession(): GeckoSession {
        val desktop = Store.desktop(this)
        val s = GeckoSession(GeckoSessionSettings.Builder()
            .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(true)
            .useTrackingProtection(true)
            .build())
        s.navigationDelegate = navigation
        s.progressDelegate = progressListener
        s.contentDelegate = content
        s.promptDelegate = prompts
        s.permissionDelegate = permissions
        s.open(Engine.runtime(this))
        Engine.withBridge { ext -> s.webExtensionController.setMessageDelegate(ext, bridgeDelegate, "browser") }
        return s
    }

    private fun isHome(url: String?) = url == null || url == "about:blank" || url.startsWith("data:")

    private fun pageChanged(url: String?) {
        val home = isHome(url)
        currentUrl = if (home) Store.HOME else url!!
        if (home) currentTitle = tr("Startseite")
        address.text = if (home) tr("Adresse eingeben oder suchen …") else Uri.parse(url).let { (it.host ?: url) + it.path.orEmpty().takeIf { p -> p.length > 1 }.orEmpty() }
        star.text = if (!home && Store.isFavorite(this, url!!)) "★" else "☆"
        if (!home) Store.setLastUrl(this, url!!)
    }

    fun load(input: String) {
        val url = Store.toUrl(input)
        if (url == Store.HOME) {
            Store.setLastUrl(this, Store.HOME)
            session.load(GeckoSession.Loader().data(startPage(), "text/html"))
        } else {
            session.loadUri(url)
        }
        gecko.requestFocus()
    }

    fun refreshStart() { if (currentUrl == Store.HOME) load(Store.HOME) }

    /** The start page: favourites as large tiles, and the phone's QR code while the launcher offers it. */
    private fun startPage(): String {
        fun esc(s: String) = TextUtils.htmlEncode(s)
        val tiles = Store.favorites(this).joinToString("") { f ->
            val host = Uri.parse(f.url).host.orEmpty().removePrefix("www.").removePrefix("m.")
            "<a class=t href=\"${esc(f.url)}\"><b>${esc(f.title.take(1).uppercase())}</b><span>${esc(f.title)}</span><i>${esc(host)}</i></a>"
        }
        val remote = Phone.current(this)
        val phone = if (remote != null) {
            val out = ByteArrayOutputStream()
            qrCode(remote, 360).compress(Bitmap.CompressFormat.PNG, 100, out)
            "<div class=p><img src=\"data:image/png;base64,${Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)}\"><div><h2>${esc(tr("Handy als Fernbedienung"))}</h2>" +
                "<p>${esc(tr("Code mit der Handy-Kamera scannen, dann oben „Browser“ wählen."))}</p><p>${esc(tr("Handy und Fernseher müssen im selben WLAN sein."))}</p></div></div>"
        } else {
            "<div class=p><div><h2>${esc(tr("Handy als Fernbedienung"))}</h2><p>${esc(tr("Adressen und Text auf dem Handy eintippen: 📱 oben rechts drücken und den QR-Code scannen."))}</p></div></div>"
        }
        return """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>${esc(tr("Startseite"))}</title><style>
body{margin:0;padding:40px 56px;background:linear-gradient(#1a2230,#07090d);color:#fff;font-family:sans-serif;min-height:100vh;box-sizing:border-box}
h1{font-weight:200;font-size:40px;margin:0 0 24px}h2{font-weight:300;margin:0 0 8px}
.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:18px}
.t{display:flex;flex-direction:column;align-items:center;gap:6px;padding:22px 10px;border-radius:20px;background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.1);color:#fff;text-decoration:none}
.t:hover,.t:focus{background:rgba(255,255,255,.18);border-color:#fff;outline:none}
.t b{width:64px;height:64px;border-radius:16px;background:#2d4e74;display:flex;align-items:center;justify-content:center;font-size:32px;font-weight:400}
.t span{font-size:19px}.t i{font-size:13px;opacity:.6;font-style:normal}
.p{display:flex;gap:28px;align-items:center;margin-top:36px;padding:22px;border-radius:20px;background:rgba(255,255,255,.05)}
.p img{width:180px;height:180px;border-radius:12px;background:#fff}.p p{opacity:.75;margin:6px 0;font-size:17px}
</style></head><body><h1>Glas Browser</h1><div class=g>$tiles</div>$phone</body></html>"""
    }

    private val navigation = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(s: GeckoSession, url: String?, perms: MutableList<ContentPermission>, hasUserGesture: Boolean) = pageChanged(url)
        override fun onCanGoBack(s: GeckoSession, can: Boolean) { canBack = can }
        override fun onCanGoForward(s: GeckoSession, can: Boolean) { canForward = can }

        override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
            val uri = Uri.parse(request.uri)
            if (uri.path.orEmpty().endsWith(".xpi")) {
                installExtension(request.uri)
                return GeckoResult.deny()
            }
            return when (uri.scheme) {
                "http", "https", "about", "data", "blob", "moz-extension", "resource" -> GeckoResult.allow()
                "intent" -> {
                    // Links that want an app: use the web fallback if the page gives one
                    runCatching { Intent.parseUri(request.uri, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }
                        .getOrNull()?.takeIf { it.startsWith("http") }?.let { handler.post { s.loadUri(it) } }
                    GeckoResult.deny()
                }
                else -> GeckoResult.deny()
            }
        }

        // A page that can't be loaded gets a short explanation instead of an empty screen
        override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String> {
            Diag.add("Ladefehler ${error.category}/${error.code}: $uri")
            val reason = when (error.category) {
                WebRequestError.ERROR_CATEGORY_NETWORK -> tr("Keine Verbindung zum Server. Ist der Fernseher mit dem Internet verbunden?")
                WebRequestError.ERROR_CATEGORY_URI -> tr("Diese Adresse gibt es nicht.")
                WebRequestError.ERROR_CATEGORY_SECURITY -> tr("Die Verbindung ist nicht sicher (Zertifikat).")
                WebRequestError.ERROR_CATEGORY_CONTENT -> tr("Die Seite liefert Inhalte, die der Browser nicht anzeigen kann.")
                else -> tr("Unbekannter Fehler")
            }
            val page = errorPage(tr("Seite nicht erreichbar"), "$reason\n${uri.orEmpty()} (${error.category}/${error.code})")
            return GeckoResult.fromValue("data:text/html;base64," + Base64.encodeToString(page.toByteArray(), Base64.NO_WRAP))
        }

        // Links that want a new window open in this one
        override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            handler.post { session.loadUri(uri) }
            return null
        }
    }

    private val progressListener = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(s: GeckoSession, url: String) {
            if (!isHome(url)) Diag.add("Lädt: $url")
            pageChanged(url)
            progress.visibility = View.VISIBLE
        }

        override fun onPageStop(s: GeckoSession, success: Boolean) {
            progress.visibility = View.INVISIBLE
            if (currentUrl != Store.HOME) Diag.add(if (success) "Fertig geladen: $currentUrl" else "Seite nicht fertig geladen: $currentUrl")
        }

        override fun onProgressChange(s: GeckoSession, value: Int) {
            progress.progress = value
            progress.visibility = if (value in 1..99) View.VISIBLE else View.INVISIBLE
        }
    }

    private val content = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(s: GeckoSession, title: String?) {
            if (currentUrl != Store.HOME) currentTitle = title?.takeIf { it.isNotBlank() } ?: Uri.parse(currentUrl).host.orEmpty()
        }

        override fun onFullScreen(s: GeckoSession, full: Boolean) {
            fullscreen = full
            updatePointer()
        }

        override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
            val type = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value.orEmpty()
            if (type.contains("x-xpinstall") || response.uri.substringBefore('?').endsWith(".xpi")) installExtension(response.uri)
            else toast(tr("Downloads speichert der Glas Browser nicht"))
        }

        override fun onCloseRequest(s: GeckoSession) = finish()

        // Steps of a page, for the error report: tells "nothing arrives" apart from "nothing is drawn"
        override fun onFirstComposite(s: GeckoSession) = Diag.add("Erstes Bild gezeichnet")
        override fun onFirstContentfulPaint(s: GeckoSession) = Diag.add("Erster Inhalt gezeichnet")

        override fun onCrash(s: GeckoSession) = restartSession(tr("abgestürzt"))
        override fun onKill(s: GeckoSession) = restartSession(tr("vom System beendet (Speicher)"))
    }

    private val restarts = ArrayDeque<Long>()

    /**
     * The page's process ended (memory or a crash): start a fresh one on the same page. If that keeps
     * happening, stop and say so instead of trying forever.
     */
    private fun restartSession(why: String) {
        Diag.add("Seitenprozess $why: $currentUrl")
        val now = SystemClock.elapsedRealtime()
        while (restarts.isNotEmpty() && now - restarts.first() > 60_000) restarts.removeFirst()
        restarts.addLast(now)
        session.close()
        session = newSession()
        gecko.setSession(session)
        if (restarts.size > 2) {
            session.load(GeckoSession.Loader().data(errorPage(tr("Die Seite stürzt immer wieder ab"),
                tr("Die Firefox-Engine konnte diese Seite nicht anzeigen ({0}). Menü → Mehr → Fehlerbericht zeigt Einzelheiten.", why)), "text/html"))
            return
        }
        toast(tr("Die Seite wurde neu geladen"))
        load(currentUrl)
    }

    private fun errorPage(title: String, text: String): String {
        fun esc(s: String) = TextUtils.htmlEncode(s)
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>${esc(title)}</title>" +
            "<style>body{margin:0;padding:60px;background:#07090d;color:#fff;font-family:sans-serif}h1{font-weight:300}p{opacity:.75;font-size:18px;line-height:1.5}</style>" +
            "</head><body><h1>${esc(title)}</h1><p>${esc(text)}</p></body></html>"
    }

    // Autoplay and protected videos (DRM) of the media libraries are allowed; nothing else is asked
    private val permissions = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(s: GeckoSession, perm: ContentPermission): GeckoResult<Int> = GeckoResult.fromValue(
            when (perm.permission) {
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> ContentPermission.VALUE_ALLOW
                else -> ContentPermission.VALUE_DENY
            },
        )
    }

    // ---- Questions from pages: selection lists, alerts, confirmations, text ----

    private val prompts = object : PromptDelegate {
        override fun onChoicePrompt(s: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            val flat = mutableListOf<PromptDelegate.ChoicePrompt.Choice>()
            fun walk(list: Array<PromptDelegate.ChoicePrompt.Choice>) {
                for (c in list) if (c.items != null) walk(c.items!!) else if (!c.separator && !c.disabled) flat += c
            }
            walk(prompt.choices)
            if (flat.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())
            GlassMenu.show(this@BrowserActivity, prompt.title ?: prompt.message ?: tr("Auswählen"),
                flat.map { c -> GlassMenu.Item(c.label + if (c.selected) "  ✓" else "") { result.complete(prompt.confirm(c)) } },
                focus = flat.indexOfFirst { it.selected }.coerceAtLeast(0),
                onCancel = { result.complete(prompt.dismiss()) })
            return result
        }

        override fun onAlertPrompt(s: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            GlassMenu.show(this@BrowserActivity, prompt.title ?: Uri.parse(currentUrl).host.orEmpty(),
                listOf(GlassMenu.Item("OK") { result.complete(prompt.dismiss()) }),
                footer = prompt.message, onCancel = { result.complete(prompt.dismiss()) })
            return result
        }

        override fun onButtonPrompt(s: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            GlassMenu.show(this@BrowserActivity, prompt.title ?: Uri.parse(currentUrl).host.orEmpty(), listOf(
                GlassMenu.Item("OK") { result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) },
                GlassMenu.Item(tr("Abbrechen")) { result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) },
            ), footer = prompt.message, onCancel = { result.complete(prompt.dismiss()) })
            return result
        }

        override fun onTextPrompt(s: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            TextPrompt.ask(this@BrowserActivity, prompt.message ?: prompt.title ?: "", "", prompt.defaultValue.orEmpty(), "OK",
                onCancel = { result.complete(prompt.dismiss()) }) { result.complete(prompt.confirm(it)) }
            return result
        }

        // Pop-up windows a page opens by itself stay closed
        override fun onPopupPrompt(s: GeckoSession, prompt: PromptDelegate.PopupPrompt) = GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
        override fun onBeforeUnloadPrompt(s: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt) = GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        override fun onAuthPrompt(s: GeckoSession, prompt: PromptDelegate.AuthPrompt) = GeckoResult.fromValue(prompt.dismiss())
        override fun onFilePrompt(s: GeckoSession, prompt: PromptDelegate.FilePrompt) = GeckoResult.fromValue(prompt.dismiss())
        override fun onColorPrompt(s: GeckoSession, prompt: PromptDelegate.ColorPrompt) = GeckoResult.fromValue(prompt.dismiss())
        override fun onDateTimePrompt(s: GeckoSession, prompt: PromptDelegate.DateTimePrompt) = GeckoResult.fromValue(prompt.dismiss())
    }

    // ---- Bridge to the pages ----

    private val bridgeDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            ports += port
            // Right away, before the page's video player asks which formats it may use
            runCatching { port.postMessage(videoConfig()) }
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, p: WebExtension.Port) {
                    val m = message as? JSONObject ?: return
                    if (m.has("playing")) {
                        if (m.optBoolean("playing")) playingPorts += p else playingPorts -= p
                        keepAwake()
                    }
                    if (m.has("film")) {
                        filmPort = if (m.optBoolean("film")) p else if (filmPort === p) null else filmPort
                        updatePointer()
                    }
                    if (m.has("id")) replies[m.optInt("id")]?.invoke(m.opt("result"))
                }

                override fun onDisconnect(p: WebExtension.Port) {
                    if (filmPort === p) { filmPort = null; updatePointer() }
                    ports -= p
                    playingPorts -= p
                    keepAwake()
                }
            })
        }
    }

    private fun videoConfig() = JSONObject()
        .put("c", "config")
        .put("maxHeight", Store.maxVideoHeight(this))
        .put("autoFull", Store.autoFullscreen(this))

    /** Sends a command to every page and frame; [onReply] learns whether any of them did it. */
    private fun toPages(cmd: JSONObject, onReply: ((Boolean) -> Unit)? = null) {
        val targets = ports.toList()
        if (onReply != null) {
            if (targets.isEmpty()) return onReply(false)
            val id = nextId.getAndIncrement()
            cmd.put("id", id)
            var answers = 0
            var done = false
            fun finish(ok: Boolean) {
                if (done) return
                done = true
                replies.remove(id)
                onReply(ok)
            }
            replies[id] = { result ->
                answers++
                if (result == true) finish(true) else if (answers >= targets.size) finish(false)
            }
            handler.postDelayed({ finish(false) }, 1500)
        }
        for (p in targets) runCatching { p.postMessage(cmd) }
    }

    private fun keepAwake() {
        // Keep the TV awake while a video plays, but let it sleep on a still page
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- Bar ----

    private fun buildBar(): LinearLayout {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0D1117.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        fun button(glyph: String, onClick: () -> Unit) = Ui.text(this, 20f).apply {
            text = glyph
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = Ui.rowBackground(this@BrowserActivity, false)
            setOnFocusChangeListener { v, f -> v.background = Ui.rowBackground(this@BrowserActivity, f) }
            setOnClickListener { onClick() }
        }
        val size = LinearLayout.LayoutParams(dp(48), dp(42)).apply { marginEnd = dp(8) }
        b.addView(button("←") { goBack() }, size)
        b.addView(button("↻") { session.reload() }, size)
        b.addView(button("⌂") { load(Store.HOME) }, size)
        address = Ui.text(this, 16f, Ui.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.rowBackground(this@BrowserActivity, false)
            setPadding(dp(18), 0, dp(18), 0)
            setOnFocusChangeListener { v, f -> v.background = Ui.rowBackground(this@BrowserActivity, f) }
            setOnClickListener { askAddress() }
        }
        b.addView(address, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) })
        b.addView(button("🧩") { extensionsMenu() }, size)
        star = button("☆") { toggleFavorite() }
        b.addView(star, size)
        b.addView(button("📱") { showPhone() }, size)
        b.addView(button("☰") { menu() }, LinearLayout.LayoutParams(dp(48), dp(42)))
        return b
    }

    private fun askAddress() = TextPrompt.ask(this, tr("Adresse oder Suche"), "youtube.com",
        initial = if (currentUrl == Store.HOME) "" else currentUrl) { if (it.isNotBlank()) load(it) }

    private fun toggleFavorite() {
        val url = currentUrl
        if (url == Store.HOME) return
        if (Store.isFavorite(this, url)) {
            Store.removeFavorite(this, url)
            toast(tr("Aus den Favoriten entfernt"))
        } else {
            Store.addFavorite(this, currentTitle, url)
            toast(tr("Zu den Favoriten hinzugefügt"))
        }
        pageChanged(url)
    }

    private fun menu() {
        val items = mutableListOf(
            GlassMenu.Item(tr("Adresse eingeben …")) { askAddress() },
            GlassMenu.Item(tr("Auf YouTube suchen …")) {
                TextPrompt.ask(this, tr("Auf YouTube suchen"), "", button = tr("Suchen")) { if (it.isNotBlank()) load(Store.youtubeSearchUrl(it)) }
            },
            GlassMenu.Item(tr("Favoriten …")) { favoritesMenu() },
        )
        if (currentUrl != Store.HOME) {
            items += GlassMenu.Item(if (Store.isFavorite(this, currentUrl)) tr("Aus den Favoriten entfernen") else tr("Zu den Favoriten hinzufügen")) { toggleFavorite() }
        }
        if (canForward) items += GlassMenu.Item(tr("Vorwärts")) { session.goForward() }
        items += GlassMenu.Item(tr("Neu laden")) { session.reload() }
        items += GlassMenu.Item(tr("Startseite")) { load(Store.HOME) }
        items += GlassMenu.Item(tr("Erweiterungen …")) { extensionsMenu() }
        items += GlassMenu.Item(tr("Mauszeiger"), if (pointerOn) tr("An") else tr("Aus")) { setPointer(!pointerOn) }
        items += GlassMenu.Item(tr("Ansicht"), if (Store.desktop(this)) tr("Computer") else tr("Handy/Tablet")) { toggleDesktop() }
        items += GlassMenu.Item(tr("Videoqualität höchstens"), qualityName(Store.maxVideoHeight(this))) { chooseQuality() }
        items += GlassMenu.Item(tr("Videos automatisch im Vollbild"), if (Store.autoFullscreen(this)) tr("An") else tr("Aus")) {
            Store.setAutoFullscreen(this, !Store.autoFullscreen(this))
            toPages(videoConfig())
        }
        items += GlassMenu.Item(tr("Handy als Fernbedienung …")) { showPhone() }
        items += GlassMenu.Item(tr("Mehr …")) { moreMenu() }
        items += GlassMenu.Item(tr("Browser schließen")) { finish() }
        GlassMenu.show(this, "Glas Browser", items,
            footer = tr("Pfeiltasten bewegen den Zeiger, OK klickt, am Rand wird geblättert. Zurück geht eine Seite zurück."))
    }

    private fun moreMenu() = GlassMenu.show(this, tr("Mehr"), listOf(
        GlassMenu.Item(tr("Favoriten zurücksetzen")) { Store.resetFavorites(this); refreshStart(); toast(tr("Favoriten zurückgesetzt")) },
        GlassMenu.Item(tr("Browserdaten löschen")) {
            GlassMenu.show(this, tr("Browserdaten löschen"), listOf(
                GlassMenu.Item(tr("Löschen")) {
                    Engine.runtime(this).storageController.clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL)
                    session.purgeHistory()
                    Store.setLastUrl(this, Store.HOME)
                    load(Store.HOME)
                    toast(tr("Browserdaten gelöscht"))
                },
                GlassMenu.Item(tr("Abbrechen")) {},
            ), footer = tr("Löscht Anmeldungen, Cookies, gespeicherte Seiten und den Verlauf. Erweiterungen und Favoriten bleiben."))
        },
        GlassMenu.Item(tr("Fehlerbericht")) {
            val log = Diag.all()
            GlassMenu.show(this, tr("Fehlerbericht"), listOf(GlassMenu.Item("OK") {}),
                footer = (if (log.isEmpty()) tr("Keine Fehler seit dem Start des Browsers.") else log.take(14).joinToString("\n")) +
                    "\n\n" + tr("Den ganzen Bericht zum Kopieren gibt es am Handy: Einstellen per Handy → Browser → Fehlerbericht."))
        },
        GlassMenu.Item(tr("Info")) {
            val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
            GlassMenu.show(this, "Glas Browser $v", listOf(GlassMenu.Item("OK") {}),
                footer = tr("Mit der Firefox-Engine GeckoView von Mozilla (Mozilla Public License 2.0)."))
        },
    ))

    private fun qualityName(h: Int) = when (h) {
        2160 -> "4K (2160p)"
        1440 -> "1440p"
        720 -> "720p"
        else -> "1080p"
    }

    private fun chooseQuality() {
        val options = listOf(720, 1080, 1440, 2160)
        val now = Store.maxVideoHeight(this)
        GlassMenu.show(this, tr("Videoqualität höchstens"), options.map { h ->
            GlassMenu.Item(qualityName(h) + (if (h == 1080) "  · " + tr("empfohlen") else "") + if (h == now) "  ✓" else "") {
                Store.setMaxVideoHeight(this, h)
                toPages(videoConfig())
                // Players pick their format when a video starts, so start it afresh
                session.reload()
            }
        }, focus = options.indexOf(now).coerceAtLeast(0),
            footer = tr("Viele Fernseher zeigen bei 4K-Videos nach kurzer Zeit nur noch ein grünes Bild. Bis 1440p sind auch AV1 und HDR aus; Seiten wie YouTube schicken dann ein Format, das der Fernseher sicher abspielt."))
    }

    private fun toggleDesktop() {
        Store.setDesktop(this, !Store.desktop(this))
        val desktop = Store.desktop(this)
        session.settings.userAgentMode = if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode = if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        session.reload()
    }

    private fun favoritesMenu() {
        val favs = Store.favorites(this)
        if (favs.isEmpty()) return toast(tr("Noch keine Favoriten"))
        GlassMenu.show(this, tr("Favoriten"), favs.map { f -> GlassMenu.Item(f.title) { load(f.url) } })
    }

    // ---- Extensions ----

    private fun extensionsMenu() = Engine.installed(this) { list ->
        val items = list.map { ext ->
            val enabled = ext.metaData.enabled
            GlassMenu.Item(ext.metaData.name ?: ext.id, if (enabled) tr("An") else tr("Aus")) { extensionMenu(ext) }
        } + GlassMenu.Item(tr("Erweiterung hinzufügen …")) { addExtensionMenu() }
        GlassMenu.show(this, tr("Erweiterungen"), items,
            footer = if (list.isEmpty()) tr("Noch keine Erweiterungen. Empfohlen: uBlock Origin gegen Werbung.") else null)
    }

    private fun extensionMenu(ext: WebExtension) {
        val name = ext.metaData.name ?: ext.id
        val items = mutableListOf<GlassMenu.Item>()
        ext.metaData.optionsPageUrl?.let { url -> items += GlassMenu.Item(tr("Einstellungen der Erweiterung")) { session.loadUri(url) } }
        items += GlassMenu.Item(if (ext.metaData.enabled) tr("Ausschalten") else tr("Einschalten")) {
            Engine.setEnabled(this, ext, !ext.metaData.enabled) { toast(if (ext.metaData.enabled) tr("{0} ist aus", name) else tr("{0} ist an", name)); session.reload() }
        }
        items += GlassMenu.Item(tr("Entfernen")) { Engine.uninstall(this, ext) { toast(tr("{0} entfernt", name)); session.reload() } }
        GlassMenu.show(this, name, items, footer = listOfNotNull(
            ext.metaData.description,
            ext.metaData.version?.let { tr("Version {0}", it) },
        ).joinToString("\n"))
    }

    private fun addExtensionMenu() {
        Engine.installed(this) { list ->
            val have = list.map { it.metaData.name.orEmpty().lowercase() }.toSet()
            val items = Engine.suggestions.filter { it.name.lowercase() !in have }.map { s ->
                GlassMenu.Item(s.name, s.about) { installExtension(s.slug) }
            } + listOf(
                GlassMenu.Item(tr("Andere von addons.mozilla.org …")) {
                    TextPrompt.ask(this, tr("Erweiterung von addons.mozilla.org"), "ublock-origin", button = tr("Installieren")) {
                        if (it.isNotBlank()) installExtension(it)
                    }
                },
                GlassMenu.Item(tr("Auf addons.mozilla.org stöbern")) { session.loadUri("https://addons.mozilla.org/android/") },
            )
            GlassMenu.show(this, tr("Erweiterung hinzufügen"), items,
                footer = tr("Bei „Andere“ den Namen aus der Adresse der Erweiterung eingeben, z. B. „ublock-origin“ aus addons.mozilla.org/firefox/addon/ublock-origin. Am Handy über „Einstellen per Handy“ → Browser geht das Tippen leichter."))
        }
    }

    fun installExtension(slugOrUrl: String) {
        toast(tr("Erweiterung wird geladen …"))
        Engine.install(this, slugOrUrl) { ext, error ->
            if (ext != null) {
                toast(tr("{0} ist installiert", ext.metaData.name ?: ext.id))
                session.reload()
            } else {
                toast(tr("Die Erweiterung ließ sich nicht installieren ({0})", Engine.reason(error)))
            }
        }
    }

    /** Before an extension gets its permissions, the user says yes or no. */
    private fun askInstall(ext: WebExtension, permissions: Array<String>, answer: (Boolean) -> Unit) {
        val name = ext.metaData.name ?: ext.id
        val list = permissions.map { permissionText(it) }.distinct().take(8)
        GlassMenu.show(this, tr("{0} installieren?", name), listOf(
            GlassMenu.Item(tr("Installieren")) { answer(true) },
            GlassMenu.Item(tr("Abbrechen")) { answer(false) },
        ), footer = if (list.isEmpty()) null else tr("Die Erweiterung darf:") + "\n• " + list.joinToString("\n• "),
            onCancel = { answer(false) })
    }

    private fun permissionText(p: String) = when {
        p == "<all_urls>" || p.startsWith("*://*/") || p == "*://*/*" -> tr("auf allen Seiten Daten lesen und ändern")
        p.startsWith("*://") || p.startsWith("http") -> tr("auf {0} Daten lesen und ändern", p.substringAfter("://").substringBefore('/').removePrefix("*."))
        p == "tabs" -> tr("geöffnete Seiten sehen")
        p == "storage" || p == "unlimitedStorage" -> tr("Daten speichern")
        p == "webRequest" || p == "webRequestBlocking" || p == "declarativeNetRequest" -> tr("Anfragen von Seiten blockieren oder ändern")
        p == "privacy" -> tr("Datenschutz-Einstellungen ändern")
        p == "cookies" -> tr("Cookies lesen und ändern")
        else -> p
    }

    /** On the first start: offer the ad blocker, which is the main reason for Firefox. */
    private fun offerBlocker() {
        if (isFinishing) return
        Store.setOfferedBlocker(this)
        Engine.installed(this) { list ->
            if (list.any { it.metaData.name.orEmpty().contains("uBlock", true) }) return@installed
            GlassMenu.show(this, tr("Werbung blockieren?"), listOf(
                GlassMenu.Item(tr("uBlock Origin installieren")) { installExtension("ublock-origin") },
                GlassMenu.Item(tr("Später")) {},
            ), footer = tr("uBlock Origin ist die bekannteste Firefox-Erweiterung gegen Werbung und Tracker, auch auf YouTube. Weitere Erweiterungen findest du unter 🧩."))
        }
    }

    // ---- Phone ----

    private fun showPhone() {
        val url = Phone.start(this) ?: return toast(tr("Die Handy-Fernbedienung gibt es mit dem Glas Launcher ab Version 2.43. Handy und Fernseher müssen im selben WLAN sein."))
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(26), dp(32), dp(26))
            isFocusable = true
        }
        column.addView(Ui.text(this, 22f).apply { text = tr("Handy als Fernbedienung") })
        column.addView(ImageView(this).apply { setImageBitmap(qrCode(url, dp(220))) }, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            topMargin = dp(18); bottomMargin = dp(14)
        })
        column.addView(Ui.text(this, 18f).apply { text = url; setTextColor(0xFF8AB4F8.toInt()) })
        column.addView(Ui.text(this, 14f, Ui.MUTED).apply {
            text = tr("Code mit der Handy-Kamera scannen, dann oben „Browser“ wählen.") + "\n" + tr("Handy und Fernseher müssen im selben WLAN sein.")
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(14), 0, 0)
        })
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(Ui.panel(this))
        dialog.show()
        dialog.window?.setLayout(dp(520), WRAP_CONTENT)
        column.requestFocus()
    }

    private fun qrCode(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    // ---- Remote ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) when (code) {
            KeyEvent.KEYCODE_MENU -> { menu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { media("toggle", 0); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { media("seek", 10); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { media("seek", -10); return true }
            KeyEvent.KEYCODE_BACK -> { if (event.repeatCount == 0) goBack(); return true }
        }
        if (code == KeyEvent.KEYCODE_BACK) return true
        // Arrow keys and OK drive the pointer, unless the on-screen keyboard has them
        if (!usesPointerKeys() || code !in POINTER_KEYS) return super.dispatchKeyEvent(event)
        if (immersive && SystemClock.uptimeMillis() > pointerUntil) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> media("seek", -10)
                KeyEvent.KEYCODE_DPAD_RIGHT -> media("seek", 10)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> media("toggle", 0)
                else -> showPointerBriefly()
            }
            return true
        }
        if (immersive) showPointerBriefly()
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) touch(true)
                else if (event.action == KeyEvent.ACTION_UP) touch(false)
            }
            else -> if (event.action == KeyEvent.ACTION_DOWN) {
                val step = dp(9) * (1 + event.repeatCount.coerceAtMost(12) / 2f)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> move(-step, 0f, scrollAtEdge = true)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> move(step, 0f, scrollAtEdge = true)
                    KeyEvent.KEYCODE_DPAD_UP -> move(0f, -step, scrollAtEdge = true)
                    KeyEvent.KEYCODE_DPAD_DOWN -> move(0f, step, scrollAtEdge = true)
                }
            }
        }
        return true
    }

    private fun usesPointerKeys(): Boolean {
        if (!pointerOn && !immersive) return false
        // While typing into a page field, the arrows belong to the field
        val r = android.graphics.Rect()
        root.getWindowVisibleDisplayFrame(r)
        val keyboard = root.rootView.height - r.bottom > root.rootView.height / 5
        return !(keyboard && gecko.hasFocus())
    }

    private fun goBack() {
        when {
            fullscreen -> session.exitFullScreen()
            filmPort != null -> {
                runCatching { filmPort?.postMessage(JSONObject().put("c", "exitFilm")) }
                filmPort = null
                updatePointer()
            }
            canBack -> session.goBack()
            else -> finish()
        }
    }

    fun back() = goBack()
    fun forward() { if (canForward) session.goForward() }
    fun reload() = session.reload()

    fun setPointer(on: Boolean) {
        pointerOn = on
        updatePointer()
        if (!on) gecko.requestFocus()
    }

    private fun showPointerBriefly() {
        pointerUntil = SystemClock.uptimeMillis() + 4_000
        updatePointer()
        handler.removeCallbacks(hidePointer)
        handler.postDelayed(hidePointer, 4_100)
    }

    private fun updatePointer() {
        pointer.visibility = when {
            immersive -> if (SystemClock.uptimeMillis() < pointerUntil) View.VISIBLE else View.GONE
            pointerOn -> View.VISIBLE
            else -> View.GONE
        }
        bar.visibility = if (immersive) View.GONE else View.VISIBLE
        progress.visibility = if (immersive) View.GONE else progress.visibility
    }

    /** Moves the pointer; at the screen edge the page under it scrolls instead. */
    fun move(dx: Float, dy: Float, scrollAtEdge: Boolean) {
        if (pointer.visibility != View.VISIBLE) { if (immersive) showPointerBriefly() else return }
        val x = pointer.x0 + dx
        val y = pointer.y0 + dy
        val maxX = root.width - 1f
        val maxY = root.height - 1f
        if (scrollAtEdge && !immersive) {
            val sx = if (x < 0) x else if (x > maxX) x - maxX else 0f
            val sy = if (y < 0) y else if (y > maxY) y - maxY else 0f
            if (sx != 0f || sy != 0f) scroll(sx * 4, sy * 4)
        }
        pointer.place(x.coerceIn(0f, maxX), y.coerceIn(0f, maxY))
        hover()
    }

    private fun geckoTop() = (gecko.parent as View).top + gecko.top

    private fun hover() {
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, pointer.x0 - gecko.left, pointer.y0 - geckoTop(), 0)
        ev.source = InputDevice.SOURCE_MOUSE
        runCatching { gecko.panZoomController.onMouseEvent(ev) }
        ev.recycle()
    }

    /** Presses or releases at the pointer, as a finger on a touch screen would. */
    fun touch(down: Boolean) {
        val t = SystemClock.uptimeMillis()
        if (down) downTime = t
        val ev = MotionEvent.obtain(downTime, t, if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, pointer.x0, pointer.y0, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(ev)
        ev.recycle()
    }

    fun click() {
        touch(true)
        handler.postDelayed({ touch(false) }, 40)
    }

    /** Scrolls the part of the page under the pointer (or the whole page) by screen pixels. */
    fun scroll(dx: Float, dy: Float) {
        val w = gecko.width.coerceAtLeast(1)
        val h = gecko.height.coerceAtLeast(1)
        if (ports.isEmpty()) {
            // Without the bridge: scroll the page as a whole
            gecko.panZoomController.scrollBy(ScreenLength.fromPixels(dx.toDouble()), ScreenLength.fromPixels(dy.toDouble()))
            return
        }
        val css = 1f / resources.displayMetrics.density
        toPages(JSONObject()
            .put("c", "scroll")
            .put("fx", ((pointer.x0 - gecko.left) / w).coerceIn(0f, 1f))
            .put("fy", ((pointer.y0 - geckoTop()) / h).coerceIn(0f, 1f))
            .put("dx", dx * css)
            .put("dy", dy * css))
    }

    /** Play/pause or seek the main video on the page. */
    fun media(action: String, seconds: Int) = toPages(JSONObject().put("c", "media").put("a", action).put("s", seconds))

    /** Writes text into the field the page has focused (or its search field) and optionally presses Enter. */
    fun type(text: String, enter: Boolean) {
        gecko.requestFocus()
        toPages(JSONObject().put("c", "type").put("t", text)) { ok ->
            if (!ok) toast(tr("Auf der Seite ist kein Eingabefeld ausgewählt"))
            else if (enter) pressEnter()
        }
    }

    /** A real Enter key, which pages accept like one typed on a keyboard. */
    fun pressEnter() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(gecko.windowToken, 0)
        gecko.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        gecko.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    /** Arrow key, OK, Back or Menu from the phone, handled like the TV remote's. */
    fun key(name: String) {
        val code = when (name) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> return goBack()
            "menu" -> return menu()
            else -> return
        }
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** The mouse pointer: an arrow drawn over the page. */
    private class PointerView(ctx: Context) : View(ctx) {
        var x0 = 0f
            private set
        var y0 = 0f
            private set
        private val s = ctx.dp(1f)
        private val arrow = Path().apply {
            moveTo(0f, 0f); lineTo(0f, 22 * s); lineTo(6 * s, 16.5f * s); lineTo(10 * s, 25 * s)
            lineTo(13.5f * s, 23.5f * s); lineTo(9.5f * s, 15 * s); lineTo(17 * s, 15 * s); close()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF101418.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * s
            strokeJoin = Paint.Join.ROUND
        }

        init {
            isFocusable = false
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            fill.setShadowLayer(4 * s, 0f, 1.5f * s, 0x80000000.toInt())
        }

        fun place(x: Float, y: Float) {
            x0 = x
            y0 = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(x0, y0)
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, edge)
            canvas.restore()
        }

        // Clicks go to the page underneath
        override fun onTouchEvent(event: MotionEvent?) = false
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_LANG = "lang"
        private val POINTER_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        )

        /** The browser window on screen, if any; commands from the phone go there. */
        @Volatile var instance: WeakReference<BrowserActivity>? = null
        val open: BrowserActivity? get() = instance?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
    }
}
