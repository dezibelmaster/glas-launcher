package de.glas.kids

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/**
 * Start screen of Glas Kids. In kids mode it opens YouTube Kids (or YouTube with its kids profile) after two seconds, says so when another app
 * was blocked, and shows "time is up" when the viewing time is used up. Parents get in by holding OK or the
 * Menu button and entering the PIN.
 */
class HomeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var clock: TextView
    private lateinit var remaining: TextView
    private lateinit var hint: TextView
    private var reason: String? = null
    private var parentOpen = false
    private var longPress = false
    private var dialog: Dialog? = null
    private var countdown: TextView? = null

    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = I18n.dateFormat("EEEEdMMMM").format(Date()) + "    " + I18n.dateFormat("Hm").format(Date())
            handler.postDelayed(this, 10_000)
        }
    }
    private val openKids = Runnable { startYouTubeKids() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        reason = intent?.getStringExtra(EXTRA_REASON)

        root = FrameLayout(this).apply { background = Ui.sky() }
        clock = Ui.text(this, 20f).apply {
            background = Ui.glass(this@HomeActivity, 20f)
            setPadding(dp(26), dp(10), dp(26), dp(10))
        }
        root.addView(clock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(28) })
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(content, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        remaining = Ui.text(this, 18f).apply {
            background = Ui.glass(this@HomeActivity, 18f)
            setPadding(dp(22), dp(10), dp(22), dp(10))
        }
        root.addView(remaining, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(40); bottomMargin = dp(32)
        })
        hint = Ui.text(this, 13f, color = 0x80FFFFFF.toInt()).apply { text = tr("Eltern: OK gedrückt halten") }
        root.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(40); bottomMargin = dp(36)
        })
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reason = intent.getStringExtra(EXTRA_REASON)
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
        render()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    // ---- Screens ----

    private fun render() {
        handler.removeCallbacks(openKids)
        content.removeAllViews()
        countdown = null
        val why = reason
        reason = null
        val kids = Apps.target(this)
        hint.visibility = if (prefs.enabled) View.VISIBLE else View.GONE
        showRemaining()
        when {
            !prefs.enabled -> renderOff()
            kids == null -> renderMessage(tr("YouTube fehlt"), tr("Bitte YouTube auf dem Fernseher installieren."), listOf(tr("Eltern") to { openParent() }))
            ScreenTime.state(prefs) != ScreenTime.State.OK -> renderTimeUp()
            why == REASON_BLOCKED -> renderBlocked()
            else -> renderStart(kids)
        }
    }

    private fun showRemaining() {
        val left = if (prefs.enabled) ScreenTime.remainingMs(prefs) else null
        remaining.visibility = if (left != null && left > 0) View.VISIBLE else View.GONE
        if (left != null) remaining.text = tr("Heute noch {0}", ScreenTime.durationLabel(ScreenTime.minutesLeft(left)))
    }

    /** Kids mode: big YouTube Kids tile, opens by itself after two seconds. */
    private fun renderStart(pkg: String) {
        val banner = Apps.banner(this, pkg)
        val tile = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            setPadding(dp(6), dp(6), dp(6), dp(6))
            fun ring(f: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(30f)
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(if (f) 5 else 1), if (f) Color.WHITE else 0x40FFFFFF)
                }
                animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(150).start()
            }
            ring(false)
            setOnFocusChangeListener { _, f -> ring(f) }
            setOnClickListener { startYouTubeKids() }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply { cornerRadius = dp(24f); setColor(0x33000000) }
        }
        if (banner != null) {
            image.setImageDrawable(banner)
            tile.addView(image, FrameLayout.LayoutParams(dp(400), dp(225)))
        } else {
            Apps.icon(this, pkg)?.let { image.setImageDrawable(BitmapDrawable(resources, Ui.roundedIcon(it, dp(220)))) }
            tile.addView(image, FrameLayout.LayoutParams(dp(220), dp(220)))
        }
        content.addView(tile, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        content.addView(Ui.text(this, 26f).apply {
            text = Apps.label(this@HomeActivity, pkg)
            setPadding(0, dp(22), 0, dp(6))
        })
        countdown = Ui.text(this, 17f, color = Ui.MUTED).apply { text = tr("Startet automatisch in 2 Sekunden …") }
        content.addView(countdown)
        addExtraApps()
        tile.requestFocus()
        if (!parentOpen) handler.postDelayed(openKids, 2_000)
    }

    /** Apps the parents allowed besides YouTube Kids, as small tiles below. */
    private fun addExtraApps() {
        val extras = prefs.extraApps.filter { Apps.launchIntent(this, it) != null }.sortedBy { Apps.label(this, it).lowercase() }
        if (extras.isEmpty()) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, 0)
        }
        for (pkg in extras) {
            val icon = Apps.icon(this, pkg) ?: continue
            val item = ImageView(this).apply {
                setImageDrawable(BitmapDrawable(resources, Ui.roundedIcon(icon, dp(84))))
                isFocusable = true
                isClickable = true
                contentDescription = Apps.label(this@HomeActivity, pkg)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                fun ring(f: Boolean) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(24f)
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(if (f) 4 else 0), Color.WHITE)
                    }
                }
                ring(false)
                setOnFocusChangeListener { _, f -> ring(f) }
                setOnClickListener {
                    handler.removeCallbacks(openKids)
                    if (!Apps.launch(this@HomeActivity, pkg)) toast(tr("{0} lässt sich nicht öffnen", Apps.label(this@HomeActivity, pkg)))
                }
            }
            row.addView(item, LinearLayout.LayoutParams(dp(94), dp(94)).apply { leftMargin = dp(8); rightMargin = dp(8) })
        }
        content.addView(row)
    }

    /** Another app was opened: friendly note, then back to YouTube Kids. */
    private fun renderBlocked() {
        panel(
            tr("Das geht hier nicht"),
            tr("Im Kindermodus gibt es nur {0}.", Apps.targetName(this)),
            listOf(tr("Zurück zu {0}", Apps.targetName(this)) to { startYouTubeKids() }),
            tr("Geht in 3 Sekunden von selbst zurück"),
        )
        if (!parentOpen) handler.postDelayed(openKids, 3_000)
    }

    private fun renderTimeUp() {
        val outside = ScreenTime.state(prefs) == ScreenTime.State.OUTSIDE_WINDOW
        val buttons = mutableListOf<Pair<String, () -> Unit>>()
        if (GuardService.instance != null && android.os.Build.VERSION.SDK_INT >= 28) {
            val sleep: () -> Unit = { GuardService.instance?.sleep() }
            buttons += tr("Fernseher ausschalten") to sleep
        }
        buttons += tr("Eltern") to { openParent() }
        panel(
            tr("Fernsehzeit ist vorbei"),
            (if (outside) "" else tr("Die Fernsehzeit für heute ist aufgebraucht.") + "\n") + ScreenTime.nextStart(prefs),
            buttons,
        )
    }

    /** Kids mode is off: start it, or go to the settings. */
    private fun renderOff() {
        val guard = GuardService.isOn(this)
        val buttons = mutableListOf<Pair<String, () -> Unit>>(
            tr("Kindermodus starten") to { startKidsMode() },
            tr("Einstellungen") to { withPin { openSettings() } },
        )
        panel(
            "Glas Kids",
            tr("Der Kindermodus ist aus. Wenn er läuft, lässt der Fernseher nur {0} zu.", Apps.targetName(this)) +
                if (guard) "" else "\n\n" + tr("Zuerst muss der Wächter in den Bedienungshilfen eingeschaltet werden."),
            buttons,
        )
    }

    private fun renderMessage(title: String, text: String, buttons: List<Pair<String, () -> Unit>>) = panel(title, text, buttons)

    private fun panel(title: String, text: String, buttons: List<Pair<String, () -> Unit>>, note: String? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.glass(this@HomeActivity, 28f)
            setPadding(dp(56), dp(36), dp(56), dp(36))
        }
        box.addView(Ui.text(this, 40f, Ui.medium).apply {
            this.text = title
            gravity = Gravity.CENTER
        })
        box.addView(Ui.text(this, 19f).apply {
            this.text = text
            gravity = Gravity.CENTER
            maxWidth = dp(620)
            setPadding(0, dp(12), 0, dp(26))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val views = buttons.map { (label, action) -> Ui.button(this, label, action) }
        views.forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(14) })
        }
        box.addView(row)
        if (note != null) box.addView(Ui.text(this, 15f, color = Ui.MUTED).apply {
            this.text = note
            setPadding(0, dp(18), 0, 0)
        })
        content.addView(box, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        views.firstOrNull()?.requestFocus()
    }

    // ---- Actions ----

    private fun startYouTubeKids() {
        handler.removeCallbacks(openKids)
        if (prefs.enabled && ScreenTime.state(prefs) != ScreenTime.State.OK) return render()
        val pkg = Apps.target(this)
        if (pkg == null || !Apps.launch(this, pkg)) toast(tr("{0} lässt sich nicht öffnen", Apps.targetName(this)))
    }

    private fun startKidsMode() {
        val go = {
            if (!GuardService.isOn(this)) {
                showGuardHelp()
            } else {
                prefs.enabled = true
                GuardService.resume()
                render()
            }
        }
        if (prefs.pinHash == null) {
            GlassMenu.info(this, tr("Eltern-PIN"), tr("Lege zuerst einen PIN fest. Mit ihm beenden Eltern den Kindermodus und ändern die Einstellungen."),
                listOf(GlassMenu.Item(tr("PIN festlegen")) { Pin.create(this, prefs, onDone = go) }))
        } else go()
    }

    private fun showGuardHelp() {
        dialog = GlassMenu.info(this, tr("Wächter einschalten"), guardHelp(),
            listOf(GlassMenu.Item(tr("Bedienungshilfen öffnen")) { openAccessibility(this) }))
    }

    private fun withPin(onOk: () -> Unit) {
        parentOpen = true
        handler.removeCallbacks(openKids)
        Pin.verify(this, prefs, tr("Eltern-PIN eingeben"), onCancel = { parentOpen = false }) {
            parentOpen = false
            onOk()
        }
    }

    /** Parent menu: more time, settings, end kids mode. */
    private fun openParent() {
        if (parentOpen) return
        if (!prefs.enabled) return withPin { openSettings() }
        withPin {
            parentOpen = true
            val items = mutableListOf<GlassMenu.Item>()
            if (prefs.dailyMinutes > 0 || prefs.window != null) {
                for (m in listOf(15, 30, 60)) items += GlassMenu.Item(tr("{0} freigeben", ScreenTime.durationLabel(m))) {
                    ScreenTime.grant(prefs, m)
                    parentOpen = false
                    startYouTubeKids()
                }
            }
            items += GlassMenu.Item(tr("Einstellungen")) { parentOpen = false; openSettings() }
            items += GlassMenu.Item(tr("Kindermodus beenden")) { parentOpen = false; endKidsMode() }
            if (!GuardService.isOn(this)) items += GlassMenu.Item(tr("Wächter einschalten")) { parentOpen = false; showGuardHelp() }
            val footer = tr("Heute geschaut: {0}", ScreenTime.durationLabel((ScreenTime.usedMs(prefs) / 60_000).toInt()))
            dialog = GlassMenu.show(this, tr("Eltern"), items, footer = footer).apply {
                setOnCancelListener { parentOpen = false; render() }
            }
        }
    }

    private fun endKidsMode() {
        prefs.enabled = false
        toast(tr("Kindermodus beendet"))
        // Back to the TV's normal home screen
        runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { render() }
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Moving around (e.g. to an allowed extra app) stops YouTube Kids from opening by itself
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in DPAD_MOVES && countdown != null) {
            handler.removeCallbacks(openKids)
            countdown?.text = tr("Mit OK starten")
            countdown = null
        }
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            if (!longPress) {
                longPress = true
                openParent()
            }
            return true
        }
        if (ok && event.action == KeyEvent.ACTION_UP && longPress) {
            longPress = false
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) openParent()
            return true
        }
        // In kids mode there is no way back out of this screen
        if (event.keyCode == KeyEvent.KEYCODE_BACK && prefs.enabled) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_REASON = "reason"
        const val REASON_BLOCKED = "blocked"
        private val DPAD_MOVES = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

        fun guardHelp() = tr(
            "In den Bedienungshilfen des Fernsehers „Glas Kids: Kindermodus“ einschalten. Kommt die Meldung „Eingeschränkte Einstellung“, " +
                "zuerst unter Einstellungen → Apps → Glas Kids → ⋮ „Eingeschränkte Einstellungen zulassen“ wählen.\n\n" +
                "Oder am Computer bzw. in der Bugjaeger-Shell:\nadb shell settings put secure enabled_accessibility_services de.glas.kids/.GuardService",
        )

        /** Opens the TV's accessibility settings; the guard lets the parent through for a few minutes. */
        fun openAccessibility(activity: Activity) {
            GuardService.pauseForParent()
            runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { activity.toast(tr("Die Bedienungshilfen lassen sich nicht öffnen")) }
        }
    }
}
