package de.glas.smarthome

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * The FRITZ!Box web interface inside the app, already logged in: the app signs in with the stored user and opens
 * the interface with that session, like the original app does.
 */
class WebActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        val bar = row().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        bar.addView(iconButton(Glyph.Kind.CLOSE, 40) { finish() })
        title = label(17f, C.TEXT, Type.medium).apply {
            text = "FRITZ!Box"
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(title, lp(0, WRAP, 1f).margins(l = dp(12), r = dp(8)))
        bar.addView(iconButton(Glyph.Kind.REFRESH, 40) { web.reload() })
        root.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x00000000)
        }
        root.addView(progress, lp(MATCH, dp(3)))
        val frame = FrameLayout(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                // Pages of the box stay here; links to other sites open in the browser
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host ?: return false
                    val own = android.net.Uri.parse(FritzClient.baseUrl(store.host)).host
                    if (host == own) return false
                    runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)) }
                    return true
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { this@WebActivity.progress.visibility = View.VISIBLE }
                override fun onPageFinished(view: WebView, url: String) {
                    this@WebActivity.progress.visibility = View.GONE
                    autoLogin(view, url, store)
                    view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { this@WebActivity.title.text = it }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, p: Int) { this@WebActivity.progress.progress = p }
            }
        }
        frame.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(frame, lp(MATCH, 0, 1f))
        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.bars()
            root.setPadding(b[0], b[1], b[2], b[3])
            ins
        }
        setContentView(root)

        // Log in first; if that fails, show the normal login page of the box
        thread {
            val url = runCatching {
                FritzClient(FritzClient.Config(store.host, store.user, store.password)).webUiUrl()
            }.getOrElse { FritzClient.baseUrl(store.host) }
            runOnUiThread { if (!isFinishing) web.loadUrl(url) }
        }
    }

    private var loginTries = 0

    /**
     * Fallback when the box shows its login page anyway: fill in user and password and press "Anmelden".
     * Only on the box's own address, and at most twice, so a wrong password does not loop.
     */
    private fun autoLogin(view: WebView, url: String, store: Store) {
        val own = android.net.Uri.parse(FritzClient.baseUrl(store.host)).host
        if (android.net.Uri.parse(url).host != own || loginTries >= 2 || store.password.isEmpty()) return
        val user = org.json.JSONObject.quote(store.user)
        val pass = org.json.JSONObject.quote(store.password)
        val js = """
            (function() {
              var pw = document.querySelector('input[type=password]');
              if (!pw || pw.offsetParent === null) return 'none';
              function put(el, v) {
                var proto = el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
                Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
              }
              var u = $user;
              if (u) {
                var sel = document.querySelector('select[name=username], #uiViewUser, select');
                var txt = document.querySelector('input[name=username], #uiUser');
                if (sel && Array.prototype.some.call(sel.options, function(o) { return o.value === u || o.text === u; })) {
                  var opt = Array.prototype.find.call(sel.options, function(o) { return o.value === u || o.text === u; });
                  put(sel, opt.value);
                } else if (txt) put(txt, u);
              }
              put(pw, $pass);
              var btn = document.querySelector('#submitLoginBtn, button[type=submit], input[type=submit]');
              if (btn) btn.click(); else if (pw.form) pw.form.submit();
              return 'sent';
            })();
        """.trimIndent()
        // The new interface builds the login form with scripts, so wait a moment before looking for it
        view.postDelayed({
            if (isFinishing) return@postDelayed
            view.evaluateJavascript(js) { r -> if (r?.contains("sent") == true) loginTries++ }
        }, 700)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
