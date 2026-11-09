package de.glas.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
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
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

/**
 * Glas Browser: web pages and videos on the TV.
 *
 * With the TV remote the arrow keys move a mouse pointer, OK clicks, and at the edge of the screen the
 * page scrolls. The Menu key opens the options. The phone ("Einstellen per Handy", see [BrowserRemote])
 * sends the same commands plus typed addresses and text.
 */
class BrowserActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var address: TextView
    private lateinit var star: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pointer: PointerView

    private var fullscreen: View? = null
    private var fullscreenDone: WebChromeClient.CustomViewCallback? = null
    private var pointerOn = true
    private var downTime = 0L
    private var ready = false
    @Volatile var playing = false
        private set
    @Volatile var currentUrl = Browser.HOME
        private set
    @Volatile var currentTitle = ""
        private set
    @Volatile var canBack = false
        private set

    // Ad blocker: read on WebView's network threads, so kept in fields instead of the settings
    @Volatile private var adBlockOn = true
    @Volatile private var adAllowedHere = false
    @Volatile var adsBlocked = 0
        private set
    private lateinit var shield: TextView

    // In full screen the arrows seek; up or down brings the pointer back for a few seconds
    private var pointerUntil = 0L
    private val hidePointer = Runnable { updatePointer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        web = try {
            WebView(this)
        } catch (e: Exception) {
            // Some TVs ship without a working WebView
            toast(tr("Auf diesem Fernseher fehlt die Android System WebView"))
            finish()
            return
        }
        column.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pointer = PointerView(this)
        root.addView(pointer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        setupWeb()
        adBlockOn = AdBlock.enabled(this)
        updateShield()
        Thread({ AdBlock.prepare(applicationContext) }, "glas-adblock").start()
        root.post { pointer.place(root.width / 2f, root.height / 2.5f) }

        Browser.window = WeakReference(this)
        val reason = Browser.restricted(this)
        if (reason != null && SystemClock.elapsedRealtime() > Browser.unlockedUntil) {
            Pin.verify(this, Prefs(this), reason, { finish() }) { start(intent) }
        } else {
            start(intent)
        }
    }

    private fun start(intent: Intent?) {
        ready = true
        val url = intent?.getStringExtra(EXTRA_URL)
        load(url ?: Browser.lastUrl(this) ?: Browser.HOME)
        val sp = getSharedPreferences("glas", Context.MODE_PRIVATE)
        if (!sp.getBoolean("browserHint", false)) {
            sp.edit().putBoolean("browserHint", true).apply()
            toast(tr("Pfeiltasten bewegen den Zeiger, OK klickt. Menü-Taste: Adresse, Favoriten und Handy-Fernbedienung."))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ready) intent.getStringExtra(EXTRA_URL)?.let { load(it) }
    }

    override fun onResume() {
        super.onResume()
        Browser.window = WeakReference(this)
        if (::web.isInitialized) { web.onResume(); web.resumeTimers() }
    }

    override fun onPause() {
        super.onPause()
        // Stop videos and sound when the TV goes to another app or home
        if (::web.isInitialized) { web.onPause(); web.pauseTimers() }
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (Browser.window?.get() === this) Browser.window = null
        if (::web.isInitialized) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    // ---- Page ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        applyUserAgent()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setBackgroundColor(Color.BLACK)
        web.addJavascriptInterface(Bridge(), "GlasTv")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme) {
                    // A page that sends the whole window to an ad server (pop-under, redirect) stays where it is
                    "http", "https" -> blocking() && AdBlock.blocksPage(uri).also { if (it) countBlocked() }
                    "intent" -> {
                        // Links that want an app: use the web fallback if the page gives one
                        runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }
                            .getOrNull()?.takeIf { it.startsWith("http") }?.let { view.loadUrl(it) }
                        true
                    }
                    else -> true
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame || !blocking() || !AdBlock.blocks(request.url)) return null
                countBlocked()
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                setPlaying(false)
                adsBlocked = 0
                adAllowedHere = AdBlock.isAllowed(this@BrowserActivity, url?.let { Uri.parse(it).host })
                pageChanged(url)
                updateShield()
                hideAds(view)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) = hideAds(view)

            override fun onPageFinished(view: WebView, url: String?) {
                pageChanged(url)
                view.evaluateJavascript(WATCH_MEDIA, null)
                hideAds(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = pageChanged(url)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
                if (newProgress > 60) view.evaluateJavascript(WATCH_MEDIA, null)
                if (newProgress > 30) hideAds(view)
            }

            override fun onReceivedTitle(view: WebView, title: String?) = pageChanged(view.url)

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                exitFullscreen()
                fullscreen = view
                fullscreenDone = callback
                root.addView(view, root.indexOfChild(pointer), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                updatePointer()
            }

            override fun onHideCustomView() = hideFullscreen()

            // Protected videos of some media libraries need the TV's DRM; nothing else is allowed
            override fun onPermissionRequest(request: PermissionRequest) {
                val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
            }
        }
    }

    private fun applyUserAgent() {
        val base = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        web.settings.userAgentString = if (Browser.desktop(this)) {
            val chrome = Regex("Chrome/[\\d.]+").find(base)?.value ?: "Chrome/120.0.0.0"
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        } else base
    }

    private fun blocking() = adBlockOn && !adAllowedHere

    private fun hideAds(view: WebView) { if (blocking() && !isHome(view.url)) view.evaluateJavascript(AdBlock.PAGE_SCRIPT, null) }

    private fun countBlocked() {
        adsBlocked++
        runOnUiThread { updateShield() }
    }

    private fun updateShield() {
        shield.text = when {
            !adBlockOn -> "🛡 " + tr("Aus")
            adAllowedHere -> "🛡 –"
            else -> "🛡 $adsBlocked"
        }
    }

    /** What the ad blocker did on this page, and switches for it. */
    private fun adMenu() {
        val host = Uri.parse(currentUrl).host?.takeIf { currentUrl != Browser.HOME }
        val items = mutableListOf(GlassMenu.Item(tr("Werbeblocker: {0}", if (adBlockOn) tr("An") else tr("Aus"))) {
            adBlockOn = !adBlockOn
            AdBlock.setEnabled(this, adBlockOn)
            updateShield()
            web.reload()
        })
        if (adBlockOn && host != null) {
            val site = AdBlock.site(host)
            items += GlassMenu.Item(if (adAllowedHere) tr("Werbung auf {0} wieder blockieren", site) else tr("Werbung auf {0} erlauben", site)) {
                AdBlock.setAllowed(this, host, !adAllowedHere)
                web.reload()
            }
        }
        GlassMenu.show(this, tr("Werbeblocker"), items, footer = when {
            !adBlockOn -> tr("Der Werbeblocker ist aus.")
            adAllowedHere -> tr("Auf dieser Seite ist Werbung erlaubt.")
            else -> tr("Auf dieser Seite {0} Werbe- und Tracking-Anfragen blockiert. Läuft eine Seite oder ein Video nicht richtig, hier die Werbung für diese Seite erlauben.", adsBlocked)
        })
    }

    private fun isHome(url: String?) = url == null || url == "about:blank" || url.startsWith("data:") || url == START_BASE

    private fun pageChanged(url: String?) {
        val home = isHome(url)
        currentUrl = if (home) Browser.HOME else url!!
        currentTitle = if (home) tr("Startseite") else web.title?.takeIf { it.isNotBlank() && it != url } ?: Uri.parse(url).host.orEmpty()
        canBack = web.canGoBack()
        address.text = if (home) tr("Adresse eingeben oder suchen …") else Uri.parse(url).let { (it.host ?: url) + it.path.orEmpty().takeIf { p -> p.length > 1 }.orEmpty() }
        star.text = if (!home && Browser.favorites(this).any { it.url == url }) "★" else "☆"
        if (!home) Browser.setLastUrl(this, url!!)
    }

    fun load(input: String) {
        val url = Browser.toUrl(input)
        if (url == Browser.HOME) {
            Browser.setLastUrl(this, Browser.HOME)
            web.loadDataWithBaseURL(START_BASE, startPage(), "text/html", "UTF-8", null)
        } else {
            web.loadUrl(url)
        }
        web.requestFocus()
    }

    /** The start page: favourites as large tiles and how to connect the phone. */
    private fun startPage(): String {
        fun esc(s: String) = TextUtils.htmlEncode(s)
        val tiles = Browser.favorites(this).joinToString("") { f ->
            val host = Uri.parse(f.url).host.orEmpty().removePrefix("www.").removePrefix("m.")
            "<a class=t href=\"${esc(f.url)}\"><b>${esc(f.title.take(1).uppercase())}</b><span>${esc(f.title)}</span><i>${esc(host)}</i></a>"
        }
        // While "Einstellen per Handy" is on, its QR code is right here; otherwise a hint where to find it
        val remote = PhoneRemote.url()
        val phone = if (remote != null) {
            val out = ByteArrayOutputStream()
            PhoneRemote.qrCode(remote, 360).compress(Bitmap.CompressFormat.PNG, 100, out)
            val qr = "<img src=\"data:image/png;base64,${Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)}\">"
            "<div class=p>$qr<div><h2>${esc(tr("Handy als Fernbedienung"))}</h2><p>${esc(tr("Code mit der Handy-Kamera scannen, dann oben „Browser“ wählen."))}</p><p>${esc(tr("Handy und Fernseher müssen im selben WLAN sein."))}</p></div></div>"
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
.p img{width:180px;height:180px;border-radius:12px;background:#fff}.p p{opacity:.75;margin:6px 0;font-size:17px}.p .u{opacity:1;font-size:24px;color:#8ab4f8}
</style></head><body><h1>${esc(tr("Glas Browser"))}</h1><div class=g>$tiles</div>$phone</body></html>"""
    }

    fun refreshStart() { if (currentUrl == Browser.HOME) load(Browser.HOME) }

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
        b.addView(button("↻") { web.reload() }, size)
        b.addView(button("⌂") { load(Browser.HOME) }, size)
        address = Ui.text(this, 16f, color = Ui.MUTED).apply {
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
        shield = button("🛡 0") { adMenu() }
        b.addView(shield, LinearLayout.LayoutParams(WRAP_CONTENT, dp(42)).apply { marginEnd = dp(8) })
        shield.setPadding(dp(12), 0, dp(12), 0)
        star = button("☆") { toggleFavorite() }
        b.addView(star, size)
        b.addView(button("📱") { showPhone() }, size)
        b.addView(button("☰") { menu() }, LinearLayout.LayoutParams(dp(48), dp(42)))
        return b
    }

    private fun askAddress() = TextPrompt.ask(this, tr("Adresse oder Suche"), "youtube.com") { if (it.isNotBlank()) load(it) }

    private fun toggleFavorite() {
        val url = currentUrl
        if (url == Browser.HOME) return
        if (Browser.favorites(this).any { it.url == url }) {
            Browser.removeFavorite(this, url)
            toast(tr("Aus den Favoriten entfernt"))
        } else {
            Browser.addFavorite(this, currentTitle, url)
            toast(tr("Zu den Favoriten hinzugefügt"))
        }
        pageChanged(url)
    }

    private fun menu() {
        val items = mutableListOf(
            GlassMenu.Item(tr("Adresse eingeben …")) { askAddress() },
            GlassMenu.Item(tr("Auf YouTube suchen …")) {
                TextPrompt.ask(this, tr("Auf YouTube suchen"), "") { if (it.isNotBlank()) load(Browser.youtubeSearchUrl(it)) }
            },
            GlassMenu.Item(tr("Favoriten …")) { favoritesMenu() },
        )
        if (currentUrl != Browser.HOME) {
            val fav = Browser.favorites(this).any { it.url == currentUrl }
            items += GlassMenu.Item(if (fav) tr("Aus den Favoriten entfernen") else tr("Zu den Favoriten hinzufügen")) { toggleFavorite() }
        }
        if (web.canGoForward()) items += GlassMenu.Item(tr("Vorwärts")) { web.goForward() }
        items += GlassMenu.Item(tr("Neu laden")) { web.reload() }
        items += GlassMenu.Item(tr("Startseite")) { load(Browser.HOME) }
        items += GlassMenu.Item(tr("Werbeblocker …")) { adMenu() }
        items += GlassMenu.Item(tr("Mauszeiger: {0}", if (pointerOn) tr("An") else tr("Aus"))) { setPointer(!pointerOn) }
        items += GlassMenu.Item(tr("Ansicht: {0}", if (Browser.desktop(this)) tr("Computer") else tr("Handy/Tablet"))) {
            Browser.setDesktop(this, !Browser.desktop(this))
            applyUserAgent()
            web.reload()
        }
        items += GlassMenu.Item(tr("Handy als Fernbedienung …")) { showPhone() }
        items += GlassMenu.Item(tr("Browser schließen")) { finish() }
        GlassMenu.show(this, tr("Glas Browser"), items,
            footer = tr("Pfeiltasten bewegen den Zeiger, OK klickt, am Rand wird geblättert. Zurück geht eine Seite zurück."))
    }

    private fun favoritesMenu() {
        val favs = Browser.favorites(this)
        if (favs.isEmpty()) return toast(tr("Noch keine Favoriten"))
        GlassMenu.show(this, tr("Favoriten"), favs.map { f -> GlassMenu.Item(f.title) { load(f.url) } })
    }

    private fun showPhone() = PhoneRemote.show(this)

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
        // Arrow keys and OK drive the pointer, unless the on-screen keyboard or a dialog has them
        if (!usesPointerKeys() || code !in POINTER_KEYS) return super.dispatchKeyEvent(event)
        if (fullscreen != null && SystemClock.uptimeMillis() > pointerUntil) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> media("seek", -10)
                KeyEvent.KEYCODE_DPAD_RIGHT -> media("seek", 10)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> media("toggle", 0)
                else -> showPointerBriefly()
            }
            return true
        }
        if (fullscreen != null) showPointerBriefly()
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
        if (!pointerOn && fullscreen == null) return false
        val imm = getSystemService(InputMethodManager::class.java)
        // While typing into a page field, the arrows belong to the field
        return !(imm?.isAcceptingText == true && web.hasFocus() && imeVisible())
    }

    private fun imeVisible(): Boolean {
        val r = android.graphics.Rect()
        root.getWindowVisibleDisplayFrame(r)
        return root.rootView.height - r.bottom > root.rootView.height / 5
    }

    private fun goBack() {
        when {
            fullscreen != null -> { fullscreenDone?.onCustomViewHidden(); hideFullscreen() }
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    private fun exitFullscreen() { if (fullscreen != null) goBack() }

    private fun hideFullscreen() {
        fullscreen?.let { root.removeView(it) }
        fullscreen = null
        fullscreenDone = null
        updatePointer()
    }

    fun setPointer(on: Boolean) {
        pointerOn = on
        updatePointer()
        if (!on) web.requestFocus()
    }

    val pointerEnabled get() = pointerOn

    private fun showPointerBriefly() {
        pointerUntil = SystemClock.uptimeMillis() + 4_000
        updatePointer()
        handler.removeCallbacks(hidePointer)
        handler.postDelayed(hidePointer, 4_100)
    }

    private val handler get() = root.handler ?: android.os.Handler(mainLooper)

    private fun updatePointer() {
        pointer.visibility = when {
            fullscreen != null -> if (SystemClock.uptimeMillis() < pointerUntil) View.VISIBLE else View.GONE
            pointerOn -> View.VISIBLE
            else -> View.GONE
        }
        bar.visibility = if (fullscreen != null) View.GONE else View.VISIBLE
    }

    /** Moves the pointer; at the screen edge the page under it scrolls instead. */
    fun move(dx: Float, dy: Float, scrollAtEdge: Boolean) {
        if (pointer.visibility != View.VISIBLE) { if (fullscreen != null) showPointerBriefly() else return }
        val x = pointer.x0 + dx
        val y = pointer.y0 + dy
        val maxX = root.width - 1f
        val maxY = root.height - 1f
        if (scrollAtEdge && fullscreen == null) {
            val sx = if (x < 0) x else if (x > maxX) x - maxX else 0f
            val sy = if (y < 0) y else if (y > maxY) y - maxY else 0f
            if (sx != 0f || sy != 0f) scroll(sx * 4, sy * 4)
        }
        pointer.place(x.coerceIn(0f, maxX), y.coerceIn(0f, maxY))
        hover()
    }

    private fun hover() {
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, pointer.x0 - web.left, pointer.y0 - web.top, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        if (fullscreen == null) web.dispatchGenericMotionEvent(ev)
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
        root.postDelayed({ touch(false) }, 40)
    }

    /** Scrolls the part of the page under the pointer (or the whole page) by screen pixels. */
    fun scroll(dx: Float, dy: Float) {
        val w = web.width.coerceAtLeast(1)
        val h = web.height.coerceAtLeast(1)
        val fx = ((pointer.x0 - web.left) / w).coerceIn(0f, 1f)
        val fy = ((pointer.y0 - web.top) / h).coerceIn(0f, 1f)
        web.evaluateJavascript(
            "(function(fx,fy,dx,dy){var k=innerWidth/$w;dx*=k;dy*=k;var e=document.elementFromPoint(fx*innerWidth,fy*innerHeight);" +
                "while(e&&e!==document.body&&e!==document.documentElement){var s=getComputedStyle(e);" +
                "if((dy&&e.scrollHeight>e.clientHeight+2&&/(auto|scroll)/.test(s.overflowY))||(dx&&e.scrollWidth>e.clientWidth+2&&/(auto|scroll)/.test(s.overflowX))){var t=e.scrollTop,l=e.scrollLeft;e.scrollBy(dx,dy);if(e.scrollTop!=t||e.scrollLeft!=l)return}" +
                "e=e.parentElement}window.scrollBy(dx,dy)})($fx,$fy,$dx,$dy)", null)
    }

    /** Play/pause or seek the main video on the page. */
    fun media(action: String, seconds: Int) {
        web.evaluateJavascript(
            "(function(a,s){var v=[].slice.call(document.querySelectorAll('video'));" +
                "var e=v.filter(function(x){return !x.paused})[0]||v.sort(function(x,y){return y.clientWidth*y.clientHeight-x.clientWidth*x.clientHeight})[0];" +
                "if(!e)return;if(a=='toggle'){if(e.paused)e.play();else e.pause()}else{e.currentTime=Math.max(0,e.currentTime+s)}})('$action',$seconds)", null)
    }

    /** Writes text into the field the page has focused (or its search field) and optionally presses Enter. */
    fun type(text: String, enter: Boolean) {
        web.requestFocus()
        web.evaluateJavascript(
            "(function(t){var e=document.activeElement;function ok(x){return x&&(x.isContentEditable||/^(INPUT|TEXTAREA)$/.test(x.tagName))}" +
                "if(!ok(e)){e=document.querySelector('input[type=search],input[name=q],input[name=search_query],input[type=text],textarea');if(e)e.focus()}" +
                "if(!ok(e))return false;if(e.isContentEditable){document.execCommand('selectAll');document.execCommand('insertText',false,t)}" +
                "else{var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');if(d&&d.set)d.set.call(e,t);else e.value=t;" +
                "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}))}return true})(${JSONObject.quote(text)})",
        ) { result ->
            if (result != "true") toast(tr("Auf der Seite ist kein Eingabefeld ausgewählt"))
            else if (enter) pressEnter()
        }
    }

    /** A real Enter key, which pages accept like one typed on a keyboard. */
    fun pressEnter() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(web.windowToken, 0)
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    /** Arrow key or OK from the phone, handled like the TV remote's. */
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

    fun forward() { if (web.canGoForward()) web.goForward() }
    fun reload() = web.reload()
    fun back() = goBack()

    private fun setPlaying(p: Boolean) {
        playing = p
        // Keep the TV awake while a video plays, but let it sleep on a still page
        if (p) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun playing(p: Boolean) = runOnUiThread { setPlaying(p) }
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
        private const val START_BASE = "https://glas.start/"
        private val POINTER_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        )

        /** Tells the browser when a video plays or stops, also inside players that load later. */
        private const val WATCH_MEDIA = "(function(){if(window.__glas)return;window.__glas=1;function s(){var p=[].slice.call(document.querySelectorAll('video,audio')).some(function(e){return !e.paused&&!e.ended});GlasTv.playing(p)}" +
            "['play','playing','pause','ended','emptied'].forEach(function(n){document.addEventListener(n,s,true)})})()"

        fun open(ctx: Context, url: String?) {
            val intent = Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (url != null) intent.putExtra(EXTRA_URL, url)
            ctx.startActivity(intent)
        }
    }
}
