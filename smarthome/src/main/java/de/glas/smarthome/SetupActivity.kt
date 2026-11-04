package de.glas.smarthome

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Connection to the FRITZ!Box: address, user and password, checked before saving. */
class SetupActivity : Activity() {
    private lateinit var store: Store
    private lateinit var host: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var message: TextView
    private lateinit var connect: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        val root = FrameLayout(this).apply { background = Backdrop() }
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val col = column()
        scroll.addView(col)
        root.addView(scroll)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.bars()
            val ime = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(android.view.WindowInsets.Type.ime()).bottom else 0
            col.setPadding(dp(22) + b[0], b[1] + dp(14), dp(22) + b[2], maxOf(b[3], ime) + dp(28))
            ins
        }
        setContentView(root)
        // No keyboard popping up on opening: the column takes the focus instead of the first field
        col.isFocusableInTouchMode = true
        col.requestFocus()

        val top = row()
        top.addView(iconButton(Glyph.Kind.CHEVRON, 44) { finish() }.apply { rotation = 180f })
        col.addView(top)

        col.addView(bubble(Glyph.Kind.HOME, C.ACCENT, 60), lp(dp(60), dp(60)).margins(t = dp(24)))
        col.addView(label(30f, C.TEXT, Type.medium).apply { text = if (store.configured) "Einstellungen" else "FRITZ!Box verbinden" }, lp().margins(t = dp(20)))
        col.addView(label(14.5f, C.MUTED).apply {
            text = "Die App spricht direkt mit deiner FRITZ!Box im Heimnetz. Dein Kennwort bleibt verschlüsselt auf diesem Handy."
            setLineSpacing(0f, 1.25f)
        }, lp().margins(t = dp(10)))

        if (store.configured && !store.demo) col.addView(boxCard(), lp().margins(t = dp(22)))

        host = field("Adresse", "fritz.box", store.host.ifEmpty { "fritz.box" }, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, col)
        user = field("Benutzername", "leer lassen für den Standardbenutzer", store.user, InputType.TYPE_CLASS_TEXT, col)
        password = field("Kennwort", "FRITZ!Box-Kennwort", store.password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, col)
        password.imeOptions = EditorInfo.IME_ACTION_GO
        password.setOnEditorActionListener { _, _, _ -> connect(); true }

        message = label(13.5f, C.RED).apply { visibility = View.GONE; setLineSpacing(0f, 1.2f) }
        col.addView(message, lp().margins(t = dp(14)))

        connect = primaryButton("Verbinden") { connect() }
        col.addView(connect, lp().margins(t = dp(22)))

        col.addView(label(14.5f, C.TEXT, Type.medium).apply {
            text = if (store.demo) "Demo beenden" else "Demo ansehen"
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener {
                store.demo = !store.demo
                finish()
            }
        }, lp().margins(t = dp(4)))

        if (store.configured) {
            col.addView(label(14.5f, C.RED, Type.medium).apply {
                text = "Abmelden"
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(16))
                setOnClickListener {
                    store.logout()
                    finish()
                }
            })
        }

        // Help
        val help = column().apply {
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = card(null, 22f)
        }
        help.addView(label(15f, C.TEXT, Type.medium).apply { text = "So klappt die Anmeldung" })
        for (t in listOf(
            "Das Handy muss im WLAN der FRITZ!Box sein (oder per VPN verbunden).",
            "Unter fritz.box → System → FRITZ!Box-Benutzer einen Benutzer mit dem Recht „Smart Home“ anlegen oder einen vorhandenen nehmen.",
            "Nur mit Kennwort angemeldet? Dann den Benutzernamen leer lassen.",
            "Vorlagen aus Smart Home → Vorlagen erscheinen in der App als Szenen.",
        )) {
            help.addView(label(13.5f, C.MUTED).apply { text = "•  $t"; setLineSpacing(0f, 1.2f) }, lp().margins(t = dp(10)))
        }
        col.addView(help, lp().margins(t = dp(22)))
        col.addView(label(12f, C.FAINT).apply {
            text = "Glas Smart Home " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")
            gravity = Gravity.CENTER
        }, lp().margins(t = dp(22)))
    }

    /** Model, FRITZ!OS and address of the connected box, and a button to its own web interface. */
    private fun boxCard() = column().apply {
        background = card(null, 22f)
        val head = column().apply { setPadding(dp(18), dp(18), dp(18), dp(16)) }
        val name = label(22f, C.TEXT, Type.medium).apply { text = "FRITZ!Box" }
        val sub = label(13.5f, C.MUTED).apply { text = "Verbinde …" }
        head.addView(name)
        head.addView(sub, lp().margins(t = dp(6)))
        addView(head)
        addView(View(context).apply { setBackgroundColor(C.STROKE) }, lp(MATCH, 1))
        val open = row(Gravity.CENTER).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(label(15.5f, C.ACCENT, Type.medium).apply { text = "Benutzeroberfläche aufrufen" })
            addView(android.widget.ImageView(context).apply { setImageDrawable(Glyph(Glyph.Kind.EXTERNAL, C.ACCENT)) },
                lp(dp(20), dp(20)).margins(l = dp(10)))
            pressable(22f)
            setOnClickListener { openWebUi() }
        }
        addView(open)
        val url = FritzClient.baseUrl(store.host)
        val client = FritzClient(FritzClient.Config(store.host, store.user, store.password))
        thread {
            val info = client.boxInfo()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (info != null) name.text = info.first
                sub.text = listOfNotNull(
                    info?.second?.takeIf { it.isNotEmpty() }?.let { "FRITZ!OS $it" },
                    url.removePrefix("http://").removePrefix("https://"),
                ).joinToString(" · ")
            }
        }
    }

    /** Opens the web interface inside the app, already logged in with the stored user. */
    private fun openWebUi() = startActivity(android.content.Intent(this, WebActivity::class.java))

    private fun field(title: String, hint: String, value: String, type: Int, parent: LinearLayout): EditText {
        parent.addView(label(13f, C.MUTED, Type.medium).apply { text = title }, lp().margins(t = dp(20), l = dp(4)))
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            inputType = type
            setSingleLine()
            setTextColor(C.TEXT)
            setHintTextColor(C.FAINT)
            textSize = 16f
            typeface = Type.regular
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(C.GLASS_STRONG, 16f, C.STROKE)
            setOnFocusChangeListener { v, f -> v.background = rounded(C.GLASS_STRONG, 16f, if (f) C.ACCENT else C.STROKE) }
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        parent.addView(e, lp().margins(t = dp(8)))
        return e
    }

    private fun connect() {
        if (busy) return
        val h = host.text.toString().trim().ifEmpty { "fritz.box" }
        val u = user.text.toString().trim()
        val p = password.text.toString()
        busy = true
        connect.text = "Verbinde …"
        connect.alpha = .7f
        message.visibility = View.GONE
        thread {
            val err = try {
                val c = FritzClient(FritzClient.Config(h, u, p))
                c.login()
                c.aha("getdevicelistinfos")
                null
            } catch (e: FritzException) {
                e.message
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                busy = false
                connect.text = "Verbinden"
                connect.alpha = 1f
                if (err == null) {
                    store.host = h
                    store.user = u
                    store.password = p
                    store.demo = false
                    finish()
                } else {
                    message.text = err
                    message.visibility = View.VISIBLE
                }
            }
        }
    }
}
