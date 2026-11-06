package de.glas.smarthome

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var greeting: TextView
    private lateinit var status: TextView
    private lateinit var refresh: ImageView
    private lateinit var summary: LinearLayout
    private lateinit var alerts: LinearLayout
    private lateinit var scenesTitle: TextView
    private lateinit var scenes: LinearLayout
    private lateinit var scenesScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var modes: LinearLayout
    private lateinit var gridNote: TextView
    private var view = "temp"
    private lateinit var grid: LinearLayout
    private lateinit var welcome: View
    private var spin: ObjectAnimator? = null
    private var sheet: Sheet? = null
    private var filter = "all"
    private val cards = HashMap<String, Pair<Device, View>>()
    private val pending = HashMap<String, Runnable>()
    private var columns = 2
    private val listener: () -> Unit = { render() }

    private val ticker = object : Runnable {
        override fun run() {
            Home.refresh()
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        filter = store.filter
        view = store.view
        edgeToEdge()
        build()
        Home.onError = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    override fun onResume() {
        super.onResume()
        Home.configure(store)
        Home.listeners += listener
        cards.clear()
        render()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        Home.listeners -= listener
        handler.removeCallbacks(ticker)
        // Taps still waiting for the debounce go out now
        for (r in pending.values.toList()) { handler.removeCallbacks(r); r.run() }
        pending.clear()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cards.clear()
        render()
    }

    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }

    // ---- Layout -------------------------------------------------------------------------------

    private fun build() {
        val root = FrameLayout(this).apply { background = Backdrop() }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        content = column()
        scroll.addView(content)
        root.addView(scroll)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.bars()
            content.setPadding(dp(18) + b[0], b[1] + dp(14), dp(18) + b[2], b[3] + dp(28))
            ins
        }
        setContentView(root)

        // Header: greeting, title, refresh and settings
        val head = row(Gravity.TOP)
        val titles = column()
        greeting = label(14f, C.MUTED, Type.medium)
        titles.addView(greeting)
        titles.addView(label(34f, C.TEXT, Type.medium).apply { text = "Zuhause"; letterSpacing = -.01f }, lp().margins(t = dp(6)))
        head.addView(titles, lp(0, WRAP, 1f))
        refresh = iconButton(Glyph.Kind.REFRESH, 44) { Home.refresh() }
        head.addView(refresh, lp(dp(44), dp(44)).margins(t = dp(6)))
        head.addView(iconButton(Glyph.Kind.GEAR, 44) { startActivity(Intent(this, SetupActivity::class.java)) },
            lp(dp(44), dp(44)).margins(l = dp(10), t = dp(6)))
        content.addView(head)

        status = label(12.5f, C.FAINT).apply { setOnClickListener { Home.refresh() } }
        content.addView(status, lp().margins(t = dp(10)))

        summary = row()
        content.addView(summary, lp().margins(t = dp(20)))

        alerts = column()
        content.addView(alerts, lp().margins(t = dp(4)))

        scenesTitle = section("Szenen")
        content.addView(scenesTitle, lp().margins(t = dp(22)))
        scenes = row()
        scenesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(scenes)
        }
        content.addView(scenesScroll, lp().margins(t = dp(10)))

        // Switch between temperatures and battery levels on the cards
        modes = row()
        content.addView(modes, lp(WRAP, WRAP).margins(t = dp(22)))

        chips = row()
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chips)
        }
        content.addView(chipsScroll, lp().margins(t = dp(12)))

        gridNote = label(12.5f, C.MUTED).apply { setPadding(dp(4), 0, 0, 0) }
        content.addView(gridNote, lp().margins(t = dp(14)))

        grid = column()
        content.addView(grid, lp().margins(t = dp(14)))

        welcome = welcomeCard()
        content.addView(welcome, lp().margins(t = dp(28)))
    }

    private fun section(t: String) = label(13f, C.MUTED, Type.medium).apply {
        text = t.uppercase()
        letterSpacing = .08f
        setPadding(dp(4), 0, 0, 0)
    }

    private fun welcomeCard() = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(30), dp(24), dp(26))
        background = card(C.ACCENT, 30f)
        addView(bubble(Glyph.Kind.HOME, C.ACCENT, 64))
        addView(label(22f, C.TEXT, Type.medium).apply { text = "Willkommen"; gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(t = dp(18)))
        addView(label(14.5f, C.MUTED).apply {
            text = "Steuere Heizkörperregler, Steckdosen, Lampen und Rollläden deiner FRITZ!Box. Melde dich mit einem FRITZ!Box-Benutzer an, der Smart Home steuern darf."
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        }, lp().margins(t = dp(10)))
        addView(primaryButton("FRITZ!Box verbinden") { startActivity(Intent(context, SetupActivity::class.java)) }, lp().margins(t = dp(24)))
        addView(label(14.5f, C.TEXT, Type.medium).apply {
            text = "Erst mal ausprobieren (Demo)"
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener {
                store.demo = true
                Home.configure(store)
                Home.refresh()
            }
        }, lp().margins(t = dp(8)))
    }

    // ---- Rendering ----------------------------------------------------------------------------

    private fun render() {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val hello = when (hour) {
            in 5..10 -> "Guten Morgen"
            in 11..17 -> "Guten Tag"
            in 18..22 -> "Guten Abend"
            else -> "Gute Nacht"
        }
        greeting.text = hello + " · " + SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(now.time)

        val connected = Home.backend != null
        welcome.visibility = if (connected) View.GONE else View.VISIBLE
        refresh.visibility = if (connected) View.VISIBLE else View.GONE
        listOf(summary, alerts, scenesTitle, scenesScroll, modes, chipsScroll, gridNote, grid).forEach { it.visibility = if (connected) View.VISIBLE else View.GONE }

        spinner(Home.loading)
        status.setTextColor(if (Home.error != null) C.RED else if (Home.remote && !store.demo) C.ACCENT else C.FAINT)
        status.text = when {
            !connected -> "Nicht verbunden"
            Home.error != null -> Home.error + "  ·  Tippen zum Wiederholen"
            Home.updated == 0L -> if (store.demo) "Demo wird geladen …" else "Verbinde mit ${store.host} …"
            else -> (if (store.demo) "Demo-Modus · " else "") + "Aktualisiert " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(Home.updated)) +
                when {
                    store.demo || store.remote.isBlank() -> ""
                    Home.remote -> "  ·  Unterwegs über MyFRITZ!"
                    else -> "  ·  Zuhause"
                }
        }
        if (!connected) return

        val all = Home.devices.filter { it.kind != Kind.OTHER }
        renderSummary(all)
        renderAlerts(all)
        renderScenes()
        renderGrid(all)
        sheet?.takeIf { it.isShowing }?.update()
    }

    private fun spinner(on: Boolean) {
        if (on && spin == null) {
            spin = ObjectAnimator.ofFloat(refresh, View.ROTATION, 0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else if (!on && spin != null) {
            spin?.cancel()
            spin = null
            refresh.animate().rotation(0f).setDuration(200).start()
        }
    }

    private fun renderSummary(all: List<Device>) {
        summary.removeAllViews()
        if (all.isEmpty()) { summary.visibility = View.GONE; return }
        val singles = all.filter { !it.isGroup && it.present }
        val temps = singles.mapNotNull { it.hkr?.tist ?: it.temp?.takeIf { _ -> it.kind == Kind.SENSOR || it.hkr != null } }
        val radiators = singles.filter { it.hkr != null }
        val heating = radiators.count { it.hkr!!.heating }
        val power = singles.mapNotNull { if (it.kind == Kind.SWITCH) it.power else null }
        val humidity = singles.mapNotNull { it.humidity }

        val tiles = mutableListOf<Triple<Glyph.Kind, String, String>>()
        if (temps.isNotEmpty()) tiles += Triple(Glyph.Kind.THERMO, Fmt.temp(temps.average(), false), "Ø innen")
        if (radiators.isNotEmpty()) tiles += Triple(Glyph.Kind.FLAME, "$heating/${radiators.size}", "heizen")
        if (power.isNotEmpty()) tiles += Triple(Glyph.Kind.BOLT, Fmt.watts(power.sum()), "Verbrauch")
        else if (humidity.isNotEmpty()) tiles += Triple(Glyph.Kind.DROP, "${humidity.average().toInt()} %", "Luftfeuchte")
        if (tiles.size < 3) tiles += Triple(Glyph.Kind.SPARK, "${singles.size}", "Geräte")

        summary.visibility = View.VISIBLE
        val colors = mapOf(Glyph.Kind.THERMO to C.temp(temps.average().takeIf { !it.isNaN() } ?: 20.0), Glyph.Kind.FLAME to C.ORANGE,
            Glyph.Kind.BOLT to C.GREEN, Glyph.Kind.DROP to C.CYAN, Glyph.Kind.SPARK to C.ACCENT)
        for ((i, t) in tiles.take(3).withIndex()) {
            val tile = column().apply {
                setPadding(dp(14), dp(14), dp(12), dp(14))
                background = card(null, 22f)
                addView(ImageView(context).apply { setImageDrawable(Glyph(t.first, colors[t.first] ?: C.TEXT)) }, lp(dp(20), dp(20)))
                addView(context.label(21f, C.TEXT, Type.medium).apply { text = t.second; maxLines = 1 }, lp().margins(t = dp(12)))
                addView(context.label(12f, C.MUTED).apply { text = t.third; maxLines = 1 }, lp().margins(t = dp(5)))
            }
            summary.addView(tile, lp(0, WRAP, 1f).margins(l = if (i > 0) dp(10) else 0))
        }
    }

    private fun renderAlerts(all: List<Device>) {
        alerts.removeAllViews()
        val singles = all.filter { !it.isGroup }
        fun names(l: List<Device>) = l.joinToString(", ") { it.name }
        val items = mutableListOf<Triple<Glyph.Kind, Int, Pair<String, Device>>>()
        singles.filter { !it.present }.takeIf { it.isNotEmpty() }?.let {
            items += Triple(Glyph.Kind.ALERT, C.MUTED, "Nicht erreichbar: ${names(it)}" to it.first())
        }
        singles.filter { it.present && it.hkr?.error?.let { e -> e != 0 } == true }.forEach {
            items += Triple(Glyph.Kind.ALERT, C.RED, "${it.name}: ${Fmt.hkrError(it.hkr!!.error)}" to it)
        }
        singles.filter { it.present && it.anyBatteryLow }.takeIf { it.isNotEmpty() }?.let {
            items += Triple(Glyph.Kind.BATTERY, C.RED, "Batterie schwach: ${names(it)}" to it.first())
        }
        singles.filter { it.hkr?.windowOpen == true }.takeIf { it.isNotEmpty() }?.let {
            items += Triple(Glyph.Kind.WINDOW, C.CYAN, "Fenster offen: ${names(it)}" to it.first())
        }
        for (a in items) {
            val r = row().apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(withAlpha(a.second, 0x1F), 18f, withAlpha(a.second, 0x33))
                addView(ImageView(context).apply { setImageDrawable(Glyph(a.first, a.second)) }, lp(dp(20), dp(20)))
                addView(context.label(13.5f, C.TEXT).apply {
                    text = a.third.first
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, lp(0, WRAP, 1f).margins(l = dp(12)))
                addView(ImageView(context).apply { setImageDrawable(Glyph(Glyph.Kind.CHEVRON, C.MUTED)) }, lp(dp(16), dp(16)))
                pressable(18f)
                setOnClickListener { open(a.third.second) }
            }
            alerts.addView(r, lp().margins(t = dp(10)))
        }
    }

    private fun renderScenes() {
        scenes.removeAllViews()
        val list = Home.templates
        val show = if (list.isEmpty()) View.GONE else View.VISIBLE
        scenesTitle.visibility = show
        scenesScroll.visibility = show
        for ((i, t) in list.withIndex()) {
            val chip = row().apply {
                setPadding(dp(14), dp(11), dp(18), dp(11))
                background = card(null, 20f)
                addView(ImageView(context).apply { setImageDrawable(Glyph(Glyph.Kind.SPARK, C.ACCENT)) }, lp(dp(18), dp(18)))
                addView(context.label(14f, C.TEXT, Type.medium).apply { text = t.name; maxLines = 1 }, lp(WRAP, WRAP).margins(l = dp(9)))
                pressable(20f)
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    Act.scene(t)
                    Toast.makeText(context, "„${t.name}“ ausgeführt", Toast.LENGTH_SHORT).show()
                }
            }
            scenes.addView(chip, lp(WRAP, WRAP).margins(l = if (i > 0) dp(10) else 0))
        }
    }

    private data class Filter(val key: String, val title: String, val test: (Device) -> Boolean)

    private val filters = listOf(
        Filter("all", "Alle") { true },
        Filter("rooms", "Räume") { it.isGroup },
        Filter("heat", "Heizung") { it.kind == Kind.THERMOSTAT },
        Filter("switch", "Steckdosen") { it.kind == Kind.SWITCH },
        Filter("light", "Licht") { it.kind == Kind.LIGHT },
        Filter("blind", "Rollläden") { it.kind == Kind.BLIND },
        Filter("sensor", "Sensoren") { it.kind == Kind.SENSOR || it.kind == Kind.ALARM },
    )

    private fun hasBattery(d: Device) = !d.isGroup && (d.batteryLevel != null || d.anyBatteryLow)

    private fun renderModes(all: List<Device>) {
        modes.removeAllViews()
        // Hidden while nothing reports a battery; the chosen view is kept for when the devices have loaded
        if (all.none(::hasBattery)) {
            modes.visibility = View.GONE
            return
        }
        modes.visibility = View.VISIBLE
        modes.background = rounded(C.GLASS, 22f, C.STROKE)
        modes.setPadding(dp(4), dp(4), dp(4), dp(4))
        for ((key, g, title) in listOf(Triple("temp", Glyph.Kind.THERMO, "Temperatur"), Triple("battery", Glyph.Kind.BATTERY, "Batterie"))) {
            val on = key == view
            val seg = row().apply {
                setPadding(dp(14), dp(9), dp(16), dp(9))
                background = if (on) rounded(C.TEXT, 18f) else null
                addView(ImageView(context).apply { setImageDrawable(Glyph(g, if (on) C.BG else C.MUTED)) }, lp(dp(18), dp(18)))
                addView(context.label(14f, if (on) C.BG else C.TEXT, Type.medium).apply { text = title }, lp(WRAP, WRAP).margins(l = dp(7)))
                pressable(18f)
                setOnClickListener {
                    if (view == key) return@setOnClickListener
                    view = key
                    store.view = key
                    render()
                }
            }
            modes.addView(seg)
        }
    }

    private fun renderGrid(all: List<Device>) {
        renderModes(all)
        if (view == "battery" && modes.visibility == View.VISIBLE) {
            chipsScroll.visibility = View.GONE
            // Weakest first, so the batteries to change are at the top
            val shown = all.filter(::hasBattery).sortedBy { if (it.anyBatteryLow) -1 else it.batteryLevel ?: 101 }
            val low = shown.count { it.anyBatteryLow || (it.batteryLevel ?: 100) <= 20 }
            gridNote.visibility = View.VISIBLE
            gridNote.text = "${shown.size} Geräte mit Batterie · " + if (low == 0) "alle in Ordnung" else "$low schwach"
            gridNote.setTextColor(if (low == 0) C.MUTED else C.RED)
            fill(shown.map { d ->
                val c = cards["b" + d.id]
                val v = if (c != null && c.first == d) c.second else batteryCard(d).also { cards["b" + d.id] = d to it }
                (v.parent as? ViewGroup)?.removeView(v)
                v
            })
            return
        }
        gridNote.visibility = View.GONE

        // Chips only for kinds that exist here
        val available = filters.filter { f -> f.key == "all" || all.any(f.test) }
        if (available.none { it.key == filter }) filter = "all"
        chips.removeAllViews()
        chipsScroll.visibility = if (available.size > 2) View.VISIBLE else View.GONE
        for ((i, f) in available.withIndex()) {
            val on = f.key == filter
            val count = all.count(f.test)
            val chip = label(14f, if (on) C.BG else C.TEXT, Type.medium).apply {
                text = if (f.key == "all") f.title else "${f.title}  $count"
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = if (on) rounded(C.TEXT, 20f) else rounded(C.GLASS, 20f, C.STROKE)
                pressable(20f)
                setOnClickListener {
                    filter = f.key
                    store.filter = f.key
                    render()
                }
            }
            chips.addView(chip, lp(WRAP, WRAP).margins(l = if (i > 0) dp(8) else 0))
        }

        val shown = all.filter(filters.first { it.key == filter }.test)
            .sortedBy { if (it.isGroup) 0 else 1 } // stable: rooms first, then the order of the FRITZ!Box

        val views = when {
            shown.isNotEmpty() -> shown.map { d ->
                val c = cards[d.id]
                val v = if (c != null && c.first == d) c.second else card(d).also { cards[d.id] = d to it }
                (v.parent as? ViewGroup)?.removeView(v)
                v
            }
            Home.updated == 0L && Home.error == null -> List(4) { skeleton() }
            else -> emptyList()
        }
        fill(views)
    }

    private fun fill(views: List<View>) {
        columns = (resources.configuration.screenWidthDp / 190).coerceIn(2, 5)
        grid.removeAllViews()
        if (views.isEmpty()) {
            grid.addView(label(14.5f, C.MUTED).apply {
                text = if (Home.error != null) "Keine Daten von der FRITZ!Box." else "Keine Smart-Home-Geräte an der FRITZ!Box angemeldet."
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(40), dp(16), dp(40))
            })
            return
        }
        for (chunk in views.chunked(columns)) {
            val r = row(Gravity.TOP)
            for ((i, v) in chunk.withIndex()) r.addView(v, lp(0, dp(176), 1f).margins(l = if (i > 0) dp(12) else 0))
            repeat(columns - chunk.size) { r.addView(space(), lp(0, 1, 1f).margins(l = dp(12))) }
            grid.addView(r, lp().margins(b = dp(12)))
        }
    }

    private fun skeleton() = View(this).apply {
        background = card(null)
        alpha = .5f
        ObjectAnimator.ofFloat(this, View.ALPHA, .25f, .6f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    // ---- Cards --------------------------------------------------------------------------------

    /** Card for the battery view: charge level big, with a battery that fills accordingly. */
    private fun batteryCard(d: Device): View {
        val level = d.batteryLevel
        val col = C.battery(level, d.anyBatteryLow)
        val c = column().apply {
            setPadding(dp(16), dp(15), dp(14), dp(14))
            background = card(if (col == C.GREEN) null else col)
            alpha = if (d.present) 1f else .55f
            pressable(26f)
            setOnClickListener { open(d) }
        }
        val top = row()
        top.addView(bubble(kindGlyph(d), if (col == C.GREEN) null else col))
        top.addView(space())
        when {
            !d.present -> top.addView(pill("Offline", C.MUTED))
            col == C.RED -> top.addView(pill("Wechseln", C.RED))
        }
        c.addView(top)
        c.addView(label(15.5f, C.TEXT, Type.medium).apply {
            text = d.name
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        }, lp().margins(t = dp(14)))
        c.addView(label(12.5f, C.MUTED).apply {
            text = d.product.ifEmpty { "Batterie" }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp().margins(t = dp(5)))
        c.addView(space(), lp(MATCH, 0, 1f))
        val bottom = row()
        bottom.addView(label(27f, if (level == null) C.RED else C.TEXT, Type.light).apply {
            text = level?.let { "$it %" } ?: "Schwach"
            maxLines = 1
        }, lp(0, WRAP, 1f))
        bottom.addView(ImageView(this).apply { setImageDrawable(Glyph(Glyph.Kind.BATTERY, col, level ?: 10)) }, lp(dp(34), dp(34)))
        c.addView(bottom)
        return c
    }

    private fun card(d: Device): View {
        val accent = Cards.accent(d)
        val c = column().apply {
            setPadding(dp(16), dp(15), dp(14), dp(14))
            background = card(accent)
            alpha = if (d.present) 1f else .55f
            pressable(26f)
            setOnClickListener { open(d) }
        }
        val top = row()
        top.addView(bubble(kindGlyph(d), accent))
        top.addView(space())
        Cards.badge(d)?.let { (t, col) -> top.addView(pill(t, col)) }
        c.addView(top)

        c.addView(label(15.5f, C.TEXT, Type.medium).apply {
            text = d.name
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        }, lp().margins(t = dp(14)))
        c.addView(label(12.5f, C.MUTED).apply {
            text = Cards.subtitle(d)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp().margins(t = dp(5)))
        c.addView(space(), lp(MATCH, 0, 1f))

        val bottom = row()
        val value = label(27f, C.TEXT, Type.light).apply {
            text = Cards.value(d)
            maxLines = 1
        }
        bottom.addView(value, lp(0, WRAP, 1f))
        when {
            d.hkr != null && d.present -> {
                val small = 32
                bottom.addView(iconButton(Glyph.Kind.MINUS, small) { step(d, -1) })
                bottom.addView(iconButton(Glyph.Kind.PLUS, small) { step(d, 1) }, lp(dp(small), dp(small)).margins(l = dp(6)))
                if (d.hkr.isOff) value.setTextColor(C.MUTED)
            }
            d.kind == Kind.BLIND && d.present -> {
                bottom.addView(iconButton(Glyph.Kind.UP, 32) { Act.blind(d, "open") })
                bottom.addView(iconButton(Glyph.Kind.DOWN, 32) { Act.blind(d, "close") }, lp(dp(32), dp(32)).margins(l = dp(6)))
            }
            d.canSwitch && d.present -> {
                val t = Toggle(this, if (d.kind == Kind.LIGHT) C.AMBER else C.GREEN)
                t.set(d.isOn == true)
                t.isEnabled = !d.switchLocked
                t.onToggle = { on -> Home.device(d.id)?.let { Act.power(it, on) } }
                bottom.addView(t)
                if (d.isOn != true) value.setTextColor(C.MUTED)
            }
            d.kind == Kind.SENSOR && d.humidity != null -> {
                bottom.addView(ImageView(this).apply { setImageDrawable(Glyph(Glyph.Kind.DROP, C.CYAN)) }, lp(dp(16), dp(16)))
                bottom.addView(label(14f, C.TEXT).apply { text = "${d.humidity} %" }, lp(WRAP, WRAP).margins(l = dp(4)))
            }
        }
        c.addView(bottom)
        return c
    }

    /** Steps like the dial: Aus, 8 … 28 °C, An. Several quick taps go out as one command. */
    private fun step(d: Device, by: Int) {
        val cur = Home.device(d.id) ?: return
        val h = cur.hkr ?: return
        val order = listOf(Hkr.OFF) + (Hkr.MIN..Hkr.MAX).toList() + Hkr.ON
        val i = order.indexOf(h.tsoll).let { if (it < 0) order.indexOf(h.komfort).coerceAtLeast(1) else it }
        val raw = order[(i + by).coerceIn(0, order.size - 1)]
        if (raw == h.tsoll) return
        Act.temp(cur, raw, send = false)
        pending.remove(d.id)?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pending.remove(d.id)
            Home.device(d.id)?.let { Act.temp(it, raw) }
        }
        pending[d.id] = r
        handler.postDelayed(r, 700)
    }

    private fun open(d: Device) {
        sheet?.dismiss()
        sheet = Sheet(this, d.id).also { it.show() }
    }
}

/** Big filled button used on the welcome card and the setup screen. */
fun Activity.primaryButton(text: String, onClick: () -> Unit) = label(16f, C.BG, Type.medium).apply {
    this.text = text
    gravity = Gravity.CENTER
    setPadding(dp(20), dp(16), dp(20), dp(16))
    background = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFFFFFFFF.toInt(), 0xFFDCE3FF.toInt()),
    ).apply { cornerRadius = dp(18f) }
    pressable(18f)
    setOnClickListener { onClick() }
}
