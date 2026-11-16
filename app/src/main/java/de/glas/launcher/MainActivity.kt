package de.glas.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.GERMANY)
    private val dateFormat = I18n.dateFormat("EEEEdMMMM")

    private lateinit var root: FrameLayout
    private lateinit var backdrop: Backdrop
    private var backdropKey = ""
    private lateinit var glassLayer: GlassLayer
    private lateinit var cardGlass: GlassLayer.Glass
    private lateinit var dockGlass: GlassLayer.Glass
    private lateinit var drawerGlass: GlassLayer.Glass
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var temp: TextView
    private lateinit var cond: TextView
    private lateinit var extra: TextView
    private lateinit var others: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var barTitle: TextView
    private val barBrowse = ArrayList<View>()
    private lateinit var barClock: View
    private lateinit var barGlass: GlassLayer.Glass
    private lateinit var picker: LinearLayout
    private lateinit var pickerList: LinearLayout
    private lateinit var pickerGlass: GlassLayer.Glass
    private var shownPlace = ""
    private lateinit var timeBox: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var status: LinearLayout
    private lateinit var statusGlass: GlassLayer.Glass
    private lateinit var statusWeather: WeatherIcon
    private lateinit var statusTemp: TextView
    private lateinit var statusNet: NetworkIcon
    private lateinit var statusDate: TextView
    private lateinit var statusTime: TextView
    private lateinit var corner: LinearLayout
    private lateinit var cornerTime: TextView
    private val statusDateFormat = I18n.dateFormat("EEdMMM")

    /** What makes up the home screen: the status bar or the large clock with weather card, plus the dock. */
    private val home: List<View>
        get() = when {
            prefs.topStyle == "duo" -> listOf(duo, dock)
            prefs.topStyle == "classic" -> listOf(timeBox, card, dock)
            prefs.statusHidden -> listOf(corner, dock)
            else -> listOf(status, dock)
        } + listOf(topRight) + if (warnings.isNotEmpty()) listOf(warningPill) else emptyList()

    // Large clock in the middle (phone lock screen style) and the round buttons top right
    private lateinit var duo: DuoClock
    private lateinit var topShade: View
    private lateinit var topRight: LinearLayout
    private lateinit var cloudButton: View
    private lateinit var panelButton: View
    private val roundGlass = mutableListOf<GlassLayer.Glass>()
    private var weatherLine = ""

    private lateinit var warningPill: LinearLayout
    private lateinit var warningText: TextView
    private lateinit var warningGlass: GlassLayer.Glass
    private var warnings = emptyList<WeatherApi.Warning>()
    private var warningsFetched = 0L
    private lateinit var dock: LinearLayout
    private lateinit var drawer: FrameLayout
    private lateinit var grid: GridLayout
    private var apps = emptyList<AppEntry>()
    private var haveWeather = false

    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = timeFormat.format(now)
            date.text = dateFormat.format(now)
            statusTime.text = timeFormat.format(now)
            cornerTime.text = timeFormat.format(now)
            statusDate.text = statusDateFormat.format(now)
            duo.time = timeFormat.format(now)
            duo.line = listOf(dateFormat.format(now), weatherLine).filter { it.isNotEmpty() }.joinToString("  ·  ")
            statusNet.set(Network.kind(this@MainActivity), Network.wifiLevel(this@MainActivity))
            handler.postDelayed(this, 10_000)
        }
    }

    private val weatherTick = object : Runnable {
        override fun run() {
            val places = prefs.places
            val main = places.firstOrNull()
            if (main == null) showNoPlace() else {
                WeatherApi.load(this@MainActivity, main) { showWeather(main, it) }
                if (System.currentTimeMillis() - warningsFetched > 30 * 60_000L) loadWarnings(main)
            }
            showOtherPlaces(places.drop(1))
            checkForUpdate()
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    private val idle = Runnable { startActivity(Intent(this, ScreensaverActivity::class.java)) }

    private val builtLanguage = I18n.lang
    private var started = false
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = FrameLayout(this).apply { clipChildren = false }

        glassLayer = GlassLayer(this)
        root.addView(glassLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Slight darkening at the top keeps white text readable on bright backgrounds
        topShade = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0x00000000),
            )
        }
        root.addView(topShade, FrameLayout.LayoutParams(MATCH_PARENT, dp(260), Gravity.TOP))

        // Middle: large clock that can disappear behind the landscape of the weather photos
        duo = DuoClock(this) { if (::backdrop.isInitialized) backdrop else null }
        root.addView(duo, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Top left: clock and date
        timeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // Date centred under the clock
        clock = Ui.readable(Ui.text(this, 78f, Ui.thin)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        date = Ui.readable(Ui.text(this, 18f, color = Ui.MUTED)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        timeBox.addView(clock)
        timeBox.addView(date)
        root.addView(timeBox, frame(Gravity.TOP or Gravity.START).apply { setMargins(dp(56), dp(40), 0, 0) })

        // Top right: weather card, with further places listed below the main one
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(24), dp(14))
            background = Ui.outline(this@MainActivity, dp(18f))
            // Selectable, opens the detailed weather
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, f ->
                v.background = if (f) Ui.rowBackground(this@MainActivity, true).apply { setColor(0) } else Ui.outline(this@MainActivity, dp(18f))
            }
            setOnClickListener { weatherDetails() }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        others = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        temp = Ui.text(this, 40f).apply { text = "–" }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        cond = Ui.text(this, 16f).apply { text = tr("Wetter lädt …") }
        extra = Ui.text(this, 13f, color = Ui.MUTED)
        meta.addView(cond)
        meta.addView(extra)
        head.addView(temp)
        head.addView(meta)
        card.addView(head)
        card.addView(others)
        root.addView(card, frame(Gravity.TOP or Gravity.END).apply { setMargins(0, dp(48), dp(56), 0) })
        cardGlass = GlassLayer.Glass(card, dp(18f), 0)

        // Top right, alternative to clock and card: one slim bar with weather, network, settings, date and time
        status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(6), dp(10), dp(6))
            background = Ui.outline(this@MainActivity, dp(26f))
        }
        statusWeather = WeatherIcon(this)
        statusTemp = Ui.text(this, 19f).apply { text = "–" }
        val weatherItem = statusItem {
            addView(statusWeather, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(statusTemp.apply { setPadding(dp(8), 0, 0, 0) })
        }.apply { setOnClickListener { weatherDetails() } }
        statusNet = NetworkIcon(this)
        val netItem = statusItem { addView(statusNet, LinearLayout.LayoutParams(dp(24), dp(24))) }
            .apply { setOnClickListener { openNetworkSettings() } }
        // The control centre takes the place of the gear; the TV settings are inside it
        val gearItem = statusItem { addView(ImageView(this@MainActivity).apply { setImageDrawable(Glyph(Glyph.Kind.PANEL)) }, LinearLayout.LayoutParams(dp(24), dp(24))) }
            .apply { setOnClickListener { Actions.run(this@MainActivity, "panel") } }
        // Date and time first, then the controls
        statusDate = Ui.text(this, 19f).apply { setPadding(0, 0, dp(16), 0) }
        val medium = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        statusTime = Ui.text(this, 19f, medium)
        // Clicking the time hides the bar and leaves only the time in the corner
        val timeItem = statusItem { addView(statusTime) }.apply { setOnClickListener { setStatusHidden(true) } }
        status.addView(statusDate)
        for (v in listOf(timeItem, weatherItem, netItem, gearItem)) status.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(2), 0, dp(2), 0) })
        root.addView(status, frame(Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, dp(32), 0, 0) })

        // Top right: round glass buttons for the weather and the control centre, and the time when the bar is folded away
        topRight = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        cloudButton = roundButton(Glyph.Kind.CLOUD) { weatherDetails() }
        panelButton = roundButton(Glyph.Kind.PANEL) { Actions.run(this, "panel") }
        cornerTime = Ui.readable(Ui.text(this, 22f, medium))
        corner = statusItem { addView(cornerTime) }.apply { setOnClickListener { setStatusHidden(false) } }
        topRight.addView(cloudButton, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) })
        topRight.addView(panelButton, LinearLayout.LayoutParams(dp(56), dp(56)))
        topRight.addView(corner, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        root.addView(topRight, frame(Gravity.TOP or Gravity.END).apply { setMargins(0, dp(30), dp(44), 0) })
        statusGlass = GlassLayer.Glass(status, dp(26f), 0)

        // Bottom: dock
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(14), dp(10), dp(14))
            background = Ui.outline(this@MainActivity, dp(28f))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(dock, frame(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, dp(44)) })
        dockGlass = GlassLayer.Glass(dock, dp(28f), 0)

        // All apps: full panel that replaces the home screen while open
        grid = GridLayout(this).apply {
            clipChildren = false
        }
        val scroll = ScrollView(this).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(16), dp(22), dp(16), dp(22))
            addView(grid)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(Ui.text(this@MainActivity, 22f).apply { text = tr("Alle Apps") })
            addView(Ui.text(this@MainActivity, 13f, color = Ui.MUTED).apply {
                text = tr("OK gedrückt halten oder Menü-Taste: zum Dock hinzufügen oder mit PIN sperren")
                setPadding(0, dp(4), 0, 0)
            })
            addView(scroll, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        drawer = FrameLayout(this).apply {
            background = Ui.outline(this@MainActivity, dp(28f))
            setPadding(dp(32), dp(28), dp(32), dp(12))
            visibility = View.GONE
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        root.addView(drawer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            setMargins(dp(96), dp(56), dp(96), dp(56))
        })
        drawerGlass = GlassLayer.Glass(drawer, dp(28f), 0)

        // Above the dock: browse the background when it has several pictures or videos
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = Ui.outline(this@MainActivity, dp(22f))
            visibility = View.GONE
        }
        barTitle = Ui.text(this, 15f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(520)
        }
        barBrowse += barButton(Ui.text(this, 18f).apply { text = "◀" }) { backdrop.previous() }
        barBrowse += barButton(barTitle) { openPicker() }
        barBrowse += barButton(Ui.text(this, 18f).apply { text = "▶" }) { backdrop.next() }
        barBrowse.forEach { bar.addView(it) }
        // Move the large clock up or down for this picture
        barClock = barButton(Ui.text(this, 15f).apply { text = "↕  " + tr("Uhr") }) { startClockMove() }
        bar.addView(barClock)
        root.addView(bar, frame(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, dp(44 + 124 + 16)) })
        barGlass = GlassLayer.Glass(bar, dp(22f), 0)

        // Right side: list of the background's pictures or videos with live preview
        pickerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        picker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = Ui.outline(this@MainActivity, dp(26f))
            visibility = View.GONE
            addView(Ui.text(this@MainActivity, 22f).apply { text = tr("Hintergrund wählen") })
            addView(Ui.text(this@MainActivity, 13f, color = Ui.MUTED).apply {
                text = tr("Markieren zeigt eine Vorschau. OK übernimmt, Zurück bricht ab.\nOK gedrückt halten oder Menü-Taste: ★ Favorit.")
                setPadding(0, dp(4), 0, dp(12))
            })
            addView(ScrollView(this@MainActivity).apply { addView(pickerList) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        root.addView(picker, FrameLayout.LayoutParams(dp(480), MATCH_PARENT, Gravity.END).apply {
            setMargins(0, dp(40), dp(40), dp(40))
        })
        pickerGlass = GlassLayer.Glass(picker, dp(26f), 0)

        // Weather warning: visible in every layout, also when the status bar is folded away
        warningText = Ui.readable(Ui.text(this, 16f, color = 0xFFFFD54F.toInt()))
        warningPill = statusItem { addView(warningText) }.apply {
            setPadding(dp(16), dp(8), dp(18), dp(8))
            visibility = View.GONE
            setOnClickListener { showWarnings() }
        }
        root.addView(warningPill, frame(Gravity.TOP or Gravity.START).apply { setMargins(dp(48), dp(32), 0, 0) })
        warningGlass = GlassLayer.Glass(warningPill, dp(22f), 0)

        glassLayer.glass += listOf(cardGlass, statusGlass, dockGlass, drawerGlass, barGlass, pickerGlass, warningGlass) + roundGlass
        // Leaving the bar, e.g. down to the dock, hides it
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (bar.visibility == View.VISIBLE && now != null && !isInside(now, bar)) hideBar(focusDock = false)
        }
        setContentView(root)
        PhoneRemote.addListener(phoneListener)
        pointOutWhatsNew()
        handler.post { handleRequest(intent) }
        if (prefs.places.isEmpty()) handler.postDelayed({ welcome() }, 800)
    }

    /** First start: there is no place for the weather yet, so ask for one. */
    private fun welcome() {
        if (isDestroyed || prefs.places.isNotEmpty()) return
        GlassMenu.show(this, tr("Willkommen beim Glas Launcher"), listOf(
            GlassMenu.Item(tr("Ort für das Wetter suchen …")) { choosePlace() },
            GlassMenu.Item(tr("Später")) {},
        ), footer = tr("Der Glas Launcher zeigt das Wetter deines Ortes als Hintergrund und oben in der Statusleiste. Weitere Orte und alle Einstellungen findest du unter ⚙ Einstellungen."))
    }

    private fun choosePlace() = PlaceSearch.ask(this, tr("Ort für das Wetter suchen")) {
        shownPlace = ""
        handler.removeCallbacks(weatherTick)
        handler.post(weatherTick)
    }

    /** No place chosen yet: the weather card and status bar invite to choose one. */
    private fun showNoPlace() {
        shownPlace = ""
        haveWeather = false
        temp.text = "–"
        cond.text = tr("Ort festlegen")
        extra.text = tr("Für das Wetter einen Ort wählen")
        statusTemp.text = tr("Ort?")
        weatherLine = ""
    }

    /** After an update, once: says where to see what is new. */
    private fun pointOutWhatsNew() {
        val version = Updater.installedVersion(this)
        val seen = prefs.seenVersion
        if (seen == version) return
        prefs.seenVersion = version
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull() ?: return
        val updated = seen != null || info.lastUpdateTime > info.firstInstallTime
        if (updated && Changelog.of(this, version) != null) {
            toast(tr("Glas Launcher {0} ist installiert. Was neu ist: Einstellungen → Updates", version))
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        // A new language in the settings: build the home screen again in it
        if (builtLanguage != I18n.lang) { recreate(); return }
        applyPrefs()
    }

    override fun onStop() {
        super.onStop()
        Snapshot.homeBehind = false
        // Also when an app was opened from the control centre: now the decoder must be free
        if (::backdrop.isInitialized) backdrop.pause()
        started = false
    }

    /** Settings changed on the phone while the home screen shows: apply them right away. */
    private val phoneListener: (Boolean) -> Unit = { settings ->
        if (settings && started) {
            if (builtLanguage != I18n.lang) recreate()
            else {
                applyPrefs()
                if (resumed) {
                    backdrop.resume()
                    handler.removeCallbacks(weatherTick)
                    handler.post(weatherTick)
                    resetIdle()
                }
            }
        }
    }

    /** Brings the home screen in line with the settings. */
    private fun applyPrefs() {
        applyBackdrop()
        // Own videos may have been deleted or added in the meantime
        if (prefs.background == "video") (backdrop as? VideoBackdrop)?.refresh()
        if (prefs.background == "image") (backdrop as? OwnPhotosBackdrop)?.refresh()
        cardGlass.tint = Ui.glassTint(prefs.glass, -0x18)
        statusGlass.tint = Ui.glassTint(prefs.glass)
        // Only the chosen top style is part of the home screen
        val classic = prefs.topStyle == "classic"
        val middle = prefs.topStyle == "duo"
        val bar = !classic && !middle
        timeBox.visibility = if (classic) View.VISIBLE else View.GONE
        card.visibility = if (classic) View.VISIBLE else View.GONE
        // The weather card sits below the round buttons
        card.layoutParams = frame(Gravity.TOP or Gravity.END).apply { setMargins(0, dp(104), dp(56), 0) }
        status.visibility = if (bar && !prefs.statusHidden) View.VISIBLE else View.GONE
        corner.visibility = if (bar && prefs.statusHidden) View.VISIBLE else View.GONE
        duo.visibility = if (middle) View.VISIBLE else View.GONE
        cloudButton.visibility = if (middle) View.VISIBLE else View.GONE
        // With the status bar showing, its own switch opens the control centre
        panelButton.visibility = if (bar && !prefs.statusHidden) View.GONE else View.VISIBLE
        // The dark shade at the top would show as a band behind the landscape in front of the clock
        topShade.visibility = if (middle) View.GONE else View.VISIBLE
        glassLayer.liquid = prefs.glass == Ui.LIQUID
        for (g in roundGlass) g.tint = Ui.glassTint(prefs.glass, -0x10)
        // With the large clock top left, the warning moves to the top centre
        warningPill.layoutParams = frame(if (classic) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.TOP or Gravity.START).apply {
            setMargins(if (classic) 0 else dp(48), dp(32), 0, 0)
        }
        warningGlass.tint = Ui.glassTint(prefs.glass)
        updateWarning()
        dockGlass.tint = Ui.glassTint(prefs.glass)
        drawerGlass.tint = Ui.glassTint(prefs.glass, 0x28)
        barGlass.tint = Ui.glassTint(prefs.glass)
        pickerGlass.tint = Ui.glassTint(prefs.glass, 0x28)
        closePicker(keep = true)
        hideBar(focusDock = false)
        apps = Apps.installed(this)
        closeDrawer()
        buildDock()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        backdrop.resume()
        handler.post(clockTick)
        handler.post(weatherTick)
        resetIdle()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        endClockMove(keep = true)
        // A picture of the background for the screen that opens now, so it doesn't start black
        if (::backdrop.isInitialized) Snapshot.take(backdrop)
        Snapshot.homeBehind = true
        // Free the video decoder right away: TVs often have only one for 4K, and the app being
        // opened (e.g. YouTube) crashes if the launcher still holds it. The control centre is only
        // a panel over the home screen, so there the video keeps running.
        if (Snapshot.keepPlaying) Snapshot.keepPlaying = false else backdrop.pause()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        PhoneRemote.removeListener(phoneListener)
        if (::backdrop.isInitialized) backdrop.release()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdle()
        if (bar.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideBar)
            handler.postDelayed(hideBar, 5_000)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button while the launcher is already showing
        if (picker.visibility == View.VISIBLE) closePicker(keep = false)
        if (drawer.visibility == View.VISIBLE) closeDrawer()
        handleRequest(intent)
    }

    /** Requests from a remote key or the control centre: open an app or a screen through the home screen. */
    private fun handleRequest(intent: Intent?) {
        val launchPkg = intent?.getStringExtra(Actions.EXTRA_LAUNCH)
        val open = intent?.getStringExtra(Actions.EXTRA_OPEN)
        intent?.removeExtra(Actions.EXTRA_LAUNCH)
        intent?.removeExtra(Actions.EXTRA_OPEN)
        if (launchPkg != null) {
            // The app's own lock and time window apply as when it is opened from the dock
            Apps.installed(this).firstOrNull { it.pkg == launchPkg }?.let { launch(it) } ?: toast(tr("{0} lässt sich nicht öffnen", launchPkg))
        }
        when (open) {
            "settings" -> openSettings()
            "weather" -> weatherDetails()
        }
    }

    /** Small round glass button with a symbol, as in the top right corner of tvOS. */
    private fun roundButton(kind: Glyph.Kind, onClick: () -> Unit): View {
        val v = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            background = Ui.outline(this@MainActivity, dp(28f))
            addView(ImageView(this@MainActivity).apply { setImageDrawable(Glyph(kind)) }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            setOnFocusChangeListener { b, f ->
                b.background = if (f) GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x26FFFFFF)
                    setStroke(dp(3), 0xFFFFFFFF.toInt())
                } else Ui.outline(this@MainActivity, dp(28f))
                b.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(150).start()
            }
            setOnClickListener { onClick() }
        }
        roundGlass += GlassLayer.Glass(v, dp(28f), 0)
        return v
    }

    @Deprecated("Launcher handles Back itself")
    override fun onBackPressed() {
        // A launcher must never finish on Back
        if (picker.visibility == View.VISIBLE) closePicker(keep = false)
        else if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Up from the dock brings the bar for browsing the background
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && bar.visibility != View.VISIBLE && dock.hasFocus() && barAvailable()) {
            showBar()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            (currentFocus?.tag as? Runnable)?.run()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun resetIdle() {
        handler.removeCallbacks(idle)
        if (prefs.idleMinutes > 0) handler.postDelayed(idle, prefs.idleMinutes * 60_000L)
    }

    private fun applyBackdrop() {
        val key = Backdrops.key(prefs)
        if (key == backdropKey) return
        if (backdropKey.isNotEmpty()) {
            backdrop.release()
            root.removeView(backdrop)
        }
        backdropKey = key
        backdrop = Backdrops.create(this, prefs)
        root.addView(backdrop, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        glassLayer.backdrop = backdrop
        backdrop.onChange = { updateBar() }
        updateBar()
    }

    // ---- Browsing the background ----

    private fun isInside(view: View, parent: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    private fun barButton(content: TextView, onClick: () -> Unit) = FrameLayout(this).apply {
        isFocusable = true
        isClickable = true
        background = Ui.rowBackground(this@MainActivity, false)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        addView(content)
        setOnFocusChangeListener { v, f -> v.background = Ui.rowBackground(this@MainActivity, f) }
        setOnClickListener { onClick() }
    }.also {
        (it.layoutParams ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)).let { lp ->
            it.layoutParams = LinearLayout.LayoutParams(lp).apply { setMargins(dp(3), 0, dp(3), 0) }
        }
    }

    /** The bar can only appear when the background has several items and nothing covers the home screen. */
    private fun barAvailable() = ::backdrop.isInitialized && (backdrop.titles.size > 1 || prefs.topStyle == "duo") &&
        drawer.visibility != View.VISIBLE && picker.visibility != View.VISIBLE

    private val hideBar = Runnable { hideBar(focusDock = true) }

    /** Shows the bar above the dock; it hides again after 5 seconds without a key press. */
    private fun showBar() {
        bar.visibility = View.VISIBLE
        (if (barBrowse[1].visibility == View.VISIBLE) barBrowse[1] else barClock).requestFocus()
        handler.removeCallbacks(hideBar)
        handler.postDelayed(hideBar, 5_000)
    }

    private fun hideBar(focusDock: Boolean) {
        handler.removeCallbacks(hideBar)
        if (clockMove != null) return
        if (bar.visibility != View.VISIBLE) return
        val hadFocus = bar.hasFocus()
        bar.visibility = View.GONE
        if (focusDock && hadFocus) dock.getChildAt(0)?.requestFocus()
    }

    private fun updateBar() {
        val titles = backdrop.titles
        if (!barAvailable()) hideBar(focusDock = false)
        val i = backdrop.current
        val mark = when {
            backdrop.pinned -> "📌  "
            prefs.favoritesOnly -> "★  "
            else -> ""
        }
        if (i in titles.indices) barTitle.text = mark + titles[i]
        barBrowse.forEach { it.visibility = if (titles.size > 1) View.VISIBLE else View.GONE }
        barClock.visibility = if (prefs.topStyle == "duo") View.VISIBLE else View.GONE
        if (clockMove == null) duo.manualTop = prefs.clockSpots[clockSpot()]
        if (picker.visibility == View.VISIBLE) markPicker()
    }

    // ---- Moving the large clock ----

    /** While moving the clock: its height so far; null otherwise. */
    private var clockMove: Float? = null
    private var clockBefore: Float? = null

    /** The clock's height is remembered for each picture (or for the background as a whole). */
    private fun clockSpot() = backdrop.uriAt(backdrop.current) ?: ("bg:" + prefs.background + "/" + prefs.weatherStyle)

    private fun startClockMove() {
        clockBefore = duo.manualTop
        clockMove = duo.currentTop()
        duo.moving = true
        handler.removeCallbacks(hideBar)
        barBrowse.forEach { it.visibility = View.GONE }
        barClock.visibility = View.GONE
        barBrowse[1].visibility = View.VISIBLE
        barTitle.text = tr("▲ ▼ Uhr verschieben  ·  ◀ automatisch  ·  OK fertig")
        barBrowse[1].requestFocus()
    }

    /** OK keeps the new height, Back the old one; ◀ hands the clock back to the automatic placement. */
    private fun endClockMove(keep: Boolean, auto: Boolean = false) {
        val f = clockMove ?: return
        clockMove = null
        duo.moving = false
        val spots = prefs.clockSpots
        if (auto) prefs.clockSpots = spots - clockSpot()
        else if (keep) prefs.clockSpots = spots + (clockSpot() to f)
        duo.manualTop = if (keep || auto) prefs.clockSpots[clockSpot()] else clockBefore
        if (auto) toast(tr("Uhr steht wieder automatisch"))
        updateBar()
        barClock.requestFocus()
        handler.postDelayed(hideBar, 5_000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val f = clockMove ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val moved = (f + if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -.015f else .015f).coerceIn(.10f, .60f)
                duo.manualTop = moved
                // The clock stops below the top and above the dock; carry on from where it really is
                clockMove = duo.currentTop()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> endClockMove(keep = false, auto = true)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> endClockMove(keep = true)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> endClockMove(keep = false)
        }
        return true
    }

    /** One line of the picker: switch through all, switch through favourites, or one item. */
    private class PickerRow(val mode: Int, val index: Int)

    private val pickerRows = mutableMapOf<View, PickerRow>()
    private var pickerStart = -1
    private var pickerWasPinned = false
    private var pickerWasFavorites = false
    private val preview = Runnable { pickerPreview?.let { backdrop.select(it, pin = false) } }
    private var pickerPreview: Int? = null

    private fun isFavorite(index: Int) = backdrop.uriAt(index)?.let { it in prefs.favorites } == true

    private fun openPicker(focus: Int? = null) {
        val titles = backdrop.titles
        if (titles.size < 2) return
        if (picker.visibility != View.VISIBLE) {
            pickerStart = backdrop.current
            pickerWasPinned = backdrop.pinned
            pickerWasFavorites = prefs.favoritesOnly
        }
        pickerList.removeAllViews()
        pickerRows.clear()
        val favorites = titles.indices.filter { isFavorite(it) }

        fun modeRow(label: String, mode: Int) {
            val row = Ui.row(this, label) { choose(PickerRow(mode, -1)) }
            pickerRows[row] = PickerRow(mode, -1)
            pickerList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        fun heading(text: String) = pickerList.addView(Ui.text(this, 13f, color = Ui.MUTED).apply {
            this.text = text.uppercase()
            letterSpacing = .15f
            setPadding(dp(8), dp(16), 0, dp(4))
        })
        fun itemRow(i: Int) {
            val row = Ui.row(this, (if (i in favorites) "★  " else "") + titles[i]) { choose(PickerRow(ROW_ITEM, i)) }
            row.setOnFocusChangeListener { v, f ->
                v.background = Ui.rowBackground(this, f)
                if (f) {
                    // Preview after a short pause, so scrolling through the list stays smooth
                    pickerPreview = i
                    handler.removeCallbacks(preview)
                    handler.postDelayed(preview, 700)
                }
            }
            // Hold OK or press Menu to add or remove a favourite
            row.setOnLongClickListener { toggleFavorite(i); true }
            row.tag = Runnable { toggleFavorite(i) }
            pickerRows[row] = PickerRow(ROW_ITEM, i)
            pickerList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        }

        modeRow(tr("Zufällig wechseln"), ROW_ALL)
        if (favorites.isNotEmpty()) {
            modeRow(tr("Nur Favoriten wechseln"), ROW_FAVORITES)
            heading(tr("Favoriten"))
            favorites.forEach { itemRow(it) }
            heading(tr("Alle"))
        }
        titles.indices.forEach { itemRow(it) }

        markPicker()
        home.forEach { it.visibility = View.INVISIBLE }
        bar.visibility = View.GONE
        picker.visibility = View.VISIBLE
        val target = focus ?: if (pickerWasPinned) pickerStart else -1
        val row = pickerRows.entries.firstOrNull { it.value.mode == ROW_ITEM && it.value.index == target }?.key
            ?: pickerRows.keys.first()
        row.requestFocus()
    }

    private fun toggleFavorite(index: Int) {
        val uri = backdrop.uriAt(index) ?: return
        val favs = prefs.favorites
        prefs.favorites = if (uri in favs) favs - uri else favs + uri
        if (prefs.favorites.none { f -> backdrop.titles.indices.any { backdrop.uriAt(it) == f } }) prefs.favoritesOnly = false
        openPicker(focus = index)
    }

    /** Ticks what is kept: the pinned item, "Nur Favoriten wechseln" or "Zufällig wechseln". */
    private fun markPicker() {
        val keepMode = when {
            backdrop.pinned -> ROW_ITEM
            prefs.favoritesOnly -> ROW_FAVORITES
            else -> ROW_ALL
        }
        for ((view, r) in pickerRows) {
            val title = (view as? LinearLayout)?.getChildAt(0) as? TextView ?: continue
            val keep = r.mode == keepMode && (r.mode != ROW_ITEM || r.index == backdrop.current)
            val base = title.text.toString().removePrefix("✓  ")
            title.text = if (keep) "✓  $base" else base
        }
    }

    private fun choose(row: PickerRow) {
        handler.removeCallbacks(preview)
        when (row.mode) {
            ROW_ITEM -> {
                prefs.favoritesOnly = false
                backdrop.select(row.index, pin = true)
                if (prefs.background == "extension") backdrop.uriAt(row.index)?.let { prefs.extUri = it; prefs.extType = "video" }
                if (prefs.background == "video") backdrop.uriAt(row.index)?.let { prefs.videoUri = it }
                if (prefs.background == "landscape") backdrop.uriAt(row.index)?.let { prefs.landscape = it.removePrefix("landscape:") }
                if (prefs.background == "reddit") backdrop.uriAt(row.index)?.let { prefs.redditPost = it.removePrefix("reddit:") }
                if (prefs.background == "image") backdrop.uriAt(row.index)?.let { prefs.imageUri = it }
            }
            else -> {
                prefs.favoritesOnly = row.mode == ROW_FAVORITES
                backdrop.unpin()
                if (prefs.background == "extension") prefs.extUri = null
                if (prefs.background == "video") prefs.videoUri = null
                if (prefs.background == "landscape") prefs.landscape = null
                if (prefs.background == "reddit") prefs.redditPost = null
                if (prefs.background == "image") prefs.imageUri = null
                // Switching through favourites: start with one of them
                if (row.mode == ROW_FAVORITES && !isFavorite(backdrop.current)) backdrop.next()
            }
        }
        // The running background already shows the choice, so don't rebuild it
        backdropKey = Backdrops.key(prefs)
        closePicker(keep = true)
    }

    private fun closePicker(keep: Boolean) {
        handler.removeCallbacks(preview)
        if (picker.visibility != View.VISIBLE) return
        if (!keep && pickerStart >= 0) {
            // Back: return to what was showing before the preview
            prefs.favoritesOnly = pickerWasFavorites
            backdrop.select(pickerStart, pin = pickerWasPinned)
            if (!pickerWasPinned) backdrop.unpin()
        }
        picker.visibility = View.GONE
        home.forEach { it.visibility = View.VISIBLE }
        updateBar()
        if (barAvailable()) showBar() else dock.getChildAt(0)?.requestFocus()
    }

    private fun showWeather(place: Place, w: WeatherNow?) {
        if (place.key != shownPlace) {
            // Another main place was chosen in the settings
            shownPlace = place.key
            haveWeather = false
            temp.text = "–"
            cond.text = tr("Wetter lädt …")
            extra.text = place.name
        }
        if (w == null) {
            if (!haveWeather) {
                cond.text = tr("Kein Wetter")
                extra.text = tr("{0} · keine Verbindung", place.name)
            }
            return
        }
        haveWeather = true
        statusTemp.text = "${w.temp}°"
        weatherLine = "${w.temp}° ${WeatherApi.describe(w.code)}"
        statusWeather.set(WeatherApi.skyFor(w.code), w.isDay)
        temp.text = "${w.temp}°"
        cond.text = WeatherApi.describe(w.code)
        extra.text = tr("{0} · H {1}° T {2}° · Stand {3}", place.name, w.max, w.min, timeFormat.format(Date(w.time)))
        backdrop.setWeather(WeatherApi.skyFor(w.code), w.isDay)
    }

    // ---- Status bar ----

    private fun statusItem(content: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = Ui.rowBackground(this@MainActivity, false).apply { setColor(0) }
        setOnFocusChangeListener { v, f ->
            v.background = Ui.rowBackground(this@MainActivity, f).apply { if (!f) setColor(0) }
        }
        content()
    }

    private fun setStatusHidden(hidden: Boolean) {
        prefs.statusHidden = hidden
        status.visibility = if (hidden) View.GONE else View.VISIBLE
        corner.visibility = if (hidden) View.VISIBLE else View.GONE
        panelButton.visibility = if (hidden) View.VISIBLE else View.GONE
        (if (hidden) corner else status.getChildAt(1)).requestFocus()
    }

    /** Detailed weather view, opened from the weather in the status bar or the weather card. */
    private fun weatherDetails() {
        if (prefs.places.isEmpty()) return choosePlace()
        start(Intent(this, WeatherActivity::class.java), tr("Wetter"))
    }

    private fun openNetworkSettings() {
        val tries = listOf(
            android.provider.Settings.ACTION_WIFI_SETTINGS,
            android.provider.Settings.ACTION_WIRELESS_SETTINGS,
            android.provider.Settings.ACTION_SETTINGS,
        )
        for (action in tries) {
            if (runCatching { startActivity(Intent(action)) }.isSuccess) return
        }
        toast(tr("Die Netzwerk-Einstellungen lassen sich nicht öffnen"))
    }


    /** One line per further place below the main weather, e.g. "Palma  24°  Klar". */
    private fun showOtherPlaces(places: List<Place>) {
        while (others.childCount > places.size) others.removeViewAt(others.childCount - 1)
        while (others.childCount < places.size) others.addView(Ui.text(this, 14f).apply { setPadding(0, dp(8), 0, 0) })
        others.setPadding(0, if (places.isEmpty()) 0 else dp(6), 0, 0)
        places.forEachIndexed { i, place ->
            val line = others.getChildAt(i) as TextView
            if (!line.text.startsWith(place.name)) line.text = place.name
            WeatherApi.load(this, place) { w ->
                if (i < others.childCount && w != null) line.text = "${place.name}   ${w.temp}°   ${WeatherApi.describe(w.code)}"
            }
        }
    }

    // ---- Dock ----

    private fun buildDock(focus: String? = null) {
        dock.removeAllViews()
        var ids = prefs.dock ?: Apps.defaultDock(apps).also { prefs.dock = it }
        // Once the Glas Browser app is installed, it joins the dock (also in place of the old built-in one)
        val browser = apps.firstOrNull { it.pkg == GlasBrowser.PKG }?.component
        if (browser != null && !prefs.browserInDock) {
            prefs.browserInDock = true
            val old = ids.indexOf(GlasBrowser.OLD_COMPONENT)
            if (browser !in ids) {
                ids = if (old >= 0) ids.toMutableList().also { it[old] = browser } else ids + browser
                prefs.dock = ids
            }
        }
        // The inputs tile joins the dock once, like an app; it can be moved or removed like one
        val hasInputs = TvInputs.list(this).isNotEmpty()
        if (hasInputs && !prefs.inputsTileAdded) {
            prefs.inputsTileAdded = true
            if (Dock.INPUTS !in ids) { ids = ids + Dock.INPUTS; prefs.dock = ids }
        }
        val byComponent = apps.associateBy { it.component }
        val shown = ids.filter { it in byComponent || (it == Dock.INPUTS && hasInputs) }
        for (id in shown) {
            val app = byComponent[id]
            if (app != null) dock.addView(appTile(app, inDock = true), dockSlot())
            else dock.addView(tile(tr("Eingänge"), Glyph(Glyph.Kind.INPUT), null, locked = false,
                onClick = { Actions.run(this, "inputs") }, onMenu = { moveMenu(id, tr("Eingänge"), emptyList()) }), dockSlot())
        }
        dock.addView(tile(tr("Alle Apps"), null, "⋯", locked = prefs.lockAllApps, onClick = { openDrawer() }, onMenu = { allAppsMenu() }), dockSlot())
        dock.addView(tile(tr("Einstellungen"), null, "⚙", locked = prefs.protectSettings, onClick = { openSettings() }, onMenu = null), dockSlot())
        val index = shown.indexOf(focus).coerceAtLeast(0)
        if (drawer.visibility != View.VISIBLE) dock.getChildAt(index)?.requestFocus()
    }

    private fun dockMenu(app: AppEntry) = moveMenu(app.component, prefs.names[app.component] ?: app.label, listOf(
        renameItem(app),
        lockItem(app) { buildDock(app.component) },
        timeItem(app) { buildDock(app.component) },
    ))

    /** Own name for an app, e.g. "Fernsehen" instead of "MagentaTV: TV & Streaming"; empty brings back the original. */
    private fun renameItem(app: AppEntry) = GlassMenu.Item(tr("Umbenennen …")) {
        TextPrompt.ask(this, tr("Neuer Name für {0}", app.label), app.label, prefs.names[app.component] ?: app.label) { text ->
            val name = text.trim()
            prefs.names = if (name.isEmpty() || name == app.label) prefs.names - app.component else prefs.names + (app.component to name)
            buildDock(app.component)
        }
    }

    /** Move left or right, or remove from the dock; for apps and the inputs tile alike. */
    private fun moveMenu(id: String, label: String, extra: List<GlassMenu.Item>) {
        val ids = prefs.dock.orEmpty().toMutableList()
        val i = ids.indexOf(id)
        val items = mutableListOf<GlassMenu.Item>()
        if (i > 0) items += GlassMenu.Item(tr("Nach links")) {
            ids.add(i - 1, ids.removeAt(i))
            prefs.dock = ids
            buildDock(id)
        }
        if (i in 0 until ids.size - 1) items += GlassMenu.Item(tr("Nach rechts")) {
            ids.add(i + 1, ids.removeAt(i))
            prefs.dock = ids
            buildDock(id)
        }
        items += GlassMenu.Item(tr("Aus dem Dock entfernen")) {
            prefs.dock = ids - id
            buildDock(ids.getOrNull(i + 1))
        }
        items += extra
        GlassMenu.show(this, label, items)
    }

    // ---- All apps ----

    private fun openDrawer() {
        if (prefs.lockAllApps) Pin.verify(this, prefs, tr("„Alle Apps“ ist gesperrt")) { showDrawer() } else showDrawer()
    }

    private fun showDrawer() {
        fillDrawer(null)
        home.forEach { it.visibility = View.INVISIBLE }
        bar.visibility = View.GONE
        drawer.visibility = View.VISIBLE
    }

    private fun fillDrawer(focus: String?) {
        grid.removeAllViews()
        // As many columns as fit on this screen: panel margins, panel padding, scroll padding
        val available = resources.displayMetrics.widthPixels - 2 * (dp(96) + dp(32) + dp(16))
        grid.columnCount = (available / dp(120)).coerceAtLeast(1)
        for (app in apps) {
            grid.addView(appTile(app, inDock = false), GridLayout.LayoutParams().apply {
                width = dp(104)
                height = dp(104)
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
        }
        grid.getChildAt(apps.indexOfFirst { it.component == focus }.coerceAtLeast(0))?.requestFocus()
    }

    private fun closeDrawer() {
        drawer.visibility = View.GONE
        home.forEach { it.visibility = View.VISIBLE }
        if (::backdrop.isInitialized) updateBar()
        dock.getChildAt(dock.childCount - 2)?.requestFocus()
    }

    private fun drawerMenu(app: AppEntry) {
        val ids = prefs.dock.orEmpty()
        val dockItem = if (app.component in ids) {
            GlassMenu.Item(tr("Aus dem Dock entfernen")) {
                prefs.dock = ids - app.component
                buildDock()
            }
        } else {
            GlassMenu.Item(tr("Zum Dock hinzufügen")) {
                prefs.dock = ids + app.component
                buildDock()
                toast(tr("{0} ist jetzt im Dock", app.label))
            }
        }
        GlassMenu.show(this, app.label, listOf(dockItem, lockItem(app) { fillDrawer(app.component) }, timeItem(app) { fillDrawer(app.component) }))
    }

    // ---- Parental lock ----

    private fun timeItem(app: AppEntry, after: () -> Unit) =
        GlassMenu.Item(tr("Zeitfenster: {0} …", TimeLimits.label(prefs.timeWindows[app.pkg]))) {
            TimeLimits.choose(this, prefs, app.label, app.pkg, after)
        }

    private fun lockItem(app: AppEntry, after: () -> Unit): GlassMenu.Item {
        val locked = app.pkg in prefs.locked
        return GlassMenu.Item(if (locked) tr("PIN-Sperre aufheben") else tr("Mit PIN sperren")) {
            when {
                locked -> Pin.verify(this, prefs, tr("PIN eingeben")) {
                    prefs.locked = prefs.locked - app.pkg
                    after()
                }
                prefs.pinHash == null -> Pin.create(this, prefs) {
                    prefs.locked = prefs.locked + app.pkg
                    after()
                }
                else -> Pin.verify(this, prefs, tr("PIN eingeben")) {
                    prefs.locked = prefs.locked + app.pkg
                    after()
                }
            }
        }
    }

    private fun allAppsMenu() {
        val locked = prefs.lockAllApps
        GlassMenu.show(this, tr("Alle Apps"), listOf(GlassMenu.Item(if (locked) tr("PIN-Sperre aufheben") else tr("Mit PIN sperren")) {
            val apply: () -> Unit = {
                prefs.lockAllApps = !locked
                buildDock()
                dock.getChildAt(dock.childCount - 2)?.requestFocus()
            }
            if (prefs.pinHash == null) Pin.create(this, prefs, apply) else Pin.verify(this, prefs, tr("PIN eingeben"), apply)
        }))
    }

    private fun launch(app: AppEntry) {
        val window = prefs.timeWindows[app.pkg]
        val go = {
            // The Glas Browser speaks the launcher's language
            start(if (app.pkg == GlasBrowser.PKG) Intent(app.launch).putExtra("lang", I18n.lang) else app.launch, app.label)
        }
        when {
            app.pkg in prefs.locked -> Pin.verify(this, prefs, tr("{0} ist gesperrt", app.label)) { go() }
            window != null && !TimeLimits.allowedNow(window) ->
                Pin.verify(this, prefs, tr("{0} ist nur {1} erlaubt", app.label, TimeLimits.label(window))) { go() }
            else -> go()
        }
    }

    // ---- Weather warnings ----

    private fun loadWarnings(place: Place) {
        warningsFetched = System.currentTimeMillis()
        Thread {
            val found = runCatching { WeatherApi.warnings(place) }.getOrNull() ?: return@Thread
            runOnUiThread {
                warnings = found
                updateWarning()
            }
        }.start()
    }

    private fun updateWarning() {
        if (warnings.isEmpty()) {
            warningPill.visibility = View.GONE
            return
        }
        warningText.text = "⚠  " + warnings.first().title + if (warnings.size > 1) "  +${warnings.size - 1}" else ""
        val covered = drawer.visibility == View.VISIBLE || picker.visibility == View.VISIBLE
        warningPill.visibility = if (covered) View.INVISIBLE else View.VISIBLE
    }

    private fun showWarnings() {
        val first = warnings.firstOrNull() ?: return
        val source = if (first.official) tr("Quelle: Deutscher Wetterdienst") else tr("Berechnet aus der Vorhersage von Open-Meteo")
        GlassMenu.show(this, tr("Wetterwarnung · {0}", prefs.mainPlace?.name ?: ""), warnings.map { w -> GlassMenu.Item("⚠  ${w.title}") {} },
            footer = (first.detail.take(400).ifBlank { "" } + "\n\n" + source).trim())
    }

    // ---- Updates ----

    private var updateChecked = 0L

    /** Once a day: if a newer version is on GitHub, say so. */
    private fun checkForUpdate() {
        val repo = prefs.updateRepo
        if (System.currentTimeMillis() - updateChecked < 24 * 60 * 60_000L) return
        updateChecked = System.currentTimeMillis()
        Thread {
            val update = runCatching { Updater.check(this, repo) }.getOrNull() ?: return@Thread
            runOnUiThread { toast(tr("Glas Launcher {0} ist verfügbar. Installieren unter Einstellungen → Updates.", update.version)) }
        }.start()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        if (prefs.protectSettings) Pin.verify(this, prefs, tr("Einstellungen sind gesperrt")) { start(intent, tr("Einstellungen")) }
        else start(intent, tr("Einstellungen"))
    }

    private fun start(intent: Intent, label: String) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(tr("{0} lässt sich nicht öffnen", label))
        }
    }

    // ---- Tiles ----

    private fun appTile(app: AppEntry, inDock: Boolean) = tile(
        label = prefs.names[app.component] ?: app.label,
        icon = app.icon,
        glyph = null,
        locked = app.pkg in prefs.locked,
        timed = app.pkg in prefs.timeWindows,
        onClick = { launch(app) },
        onMenu = { if (inDock) dockMenu(app) else drawerMenu(app) },
    )


    private fun tile(label: String, icon: Drawable?, glyph: String?, locked: Boolean, timed: Boolean = false, onClick: () -> Unit, onMenu: (() -> Unit)?): View {
        val t = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            background = Ui.tileBackground(this@MainActivity, false)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val image: View = if (icon != null) {
            ImageView(this).apply {
                setImageBitmap(Ui.roundedIcon(icon, dp(46)))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        } else {
            Ui.text(this, 28f).apply {
                text = glyph
                gravity = Gravity.CENTER
            }
        }
        content.addView(image, LinearLayout.LayoutParams(dp(46), dp(46)))
        content.addView(Ui.text(this, 12f).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        })
        t.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (locked || timed) t.addView(Ui.text(this, 12f).apply { text = if (locked) "🔒" else "⏰" }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(6), dp(8), 0)
        })

        t.setOnFocusChangeListener { v, focused ->
            v.background = Ui.tileBackground(this, focused)
            v.animate()
                .scaleX(if (focused) 1.12f else 1f)
                .scaleY(if (focused) 1.12f else 1f)
                .translationY(if (focused) -dp(6f) else 0f)
                .setDuration(180)
                .start()
        }
        t.setOnClickListener { onClick() }
        if (onMenu != null) {
            t.setOnLongClickListener { onMenu(); true }
            // Read by the Menu key
            t.tag = Runnable { onMenu() }
        }
        return t
    }

    private fun dockSlot() = LinearLayout.LayoutParams(dp(96), dp(96)).apply { setMargins(dp(6), 0, dp(6), 0) }

    private fun frame(gravity: Int) = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, gravity)

    private companion object {
        const val ROW_ALL = 0
        const val ROW_FAVORITES = 1
        const val ROW_ITEM = 2
    }
}
