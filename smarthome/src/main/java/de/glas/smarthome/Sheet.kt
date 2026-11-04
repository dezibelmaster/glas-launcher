package de.glas.smarthome

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** What the screens can do with a device. Shows the new state at once and then tells the FRITZ!Box. */
object Act {
    private fun forAll(d: Device, f: (Device) -> Device?) {
        val ids = d.members + d.id
        Home.devices.filter { it.id in ids }.forEach { x -> f(x)?.let { Home.local(it) } }
    }

    fun temp(d: Device, raw: Int, send: Boolean = true) {
        forAll(d) { x -> x.hkr?.let { x.copy(hkr = it.copy(tsoll = raw)) } }
        if (send) Home.send { it.setTemp(d, raw) }
    }

    fun power(d: Device, on: Boolean) {
        forAll(d) { x ->
            if (!x.canSwitch) null
            else x.copy(switchOn = x.switchOn?.let { on }, onOff = x.onOff?.let { on }, power = if (on) x.power else x.power?.let { 0.0 })
        }
        Home.send { it.setOn(d, on) }
    }

    fun level(d: Device, percent: Int) {
        forAll(d) { x -> x.copy(level = percent) }
        Home.send { it.setLevel(d, percent) }
    }

    fun blind(d: Device, target: String) = Home.send(2500) { it.blind(d, target) }

    fun boost(d: Device, minutes: Int) {
        val end = if (minutes > 0) System.currentTimeMillis() / 1000 + minutes * 60 else 0
        forAll(d) { x -> x.hkr?.let { x.copy(hkr = it.copy(boost = minutes > 0, boostEnd = end)) } }
        Home.send { it.boost(d, minutes) }
    }

    fun window(d: Device, minutes: Int) {
        val end = if (minutes > 0) System.currentTimeMillis() / 1000 + minutes * 60 else 0
        forAll(d) { x -> x.hkr?.let { x.copy(hkr = it.copy(windowOpen = minutes > 0, windowEnd = end)) } }
        Home.send { it.window(d, minutes) }
    }

    fun scene(t: Template) = Home.send(800) { it.apply(t) }
}

/** Details of one device, sliding up from the bottom. Follows the device while it is open. */
class Sheet(private val act: Activity, private val devKey: String) {
    private val dialog = Dialog(act)
    private val binders = mutableListOf<(Device) -> Unit>()
    private val panel = act.column()
    private var lastChange = 0L

    val isShowing get() = dialog.isShowing

    init {
        val d = Home.device(devKey)
        if (d != null) build(d)
    }

    fun show() {
        if (binders.isEmpty()) return
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()

    fun update() {
        val d = Home.device(devKey) ?: return
        binders.forEach { it(d) }
    }

    private fun bind(f: (Device) -> Unit) {
        binders += f
    }

    private val recentlyChanged get() = System.currentTimeMillis() - lastChange < 5000

    private fun build(d: Device) = with(act) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        panel.setPadding(dp(20), dp(10), dp(20), dp(24))
        panel.background = GradientDrawable().apply {
            val r = dp(30f)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(C.SHEET)
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(panel)
        }
        val base = dp(24)
        scroll.setOnApplyWindowInsetsListener { _, ins ->
            panel.setPadding(dp(20), dp(10), dp(20), base + ins.bars()[3])
            ins
        }

        // Drag handle; pulling the top part down closes the sheet
        val top = column()
        top.addView(View(this).apply { background = rounded(0x33FFFFFF, 3f) }, lp(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        top.addView(header(d), lp().margins(t = dp(16), b = dp(10)))
        dragToClose(top, scroll)
        panel.addView(top)

        when {
            d.hkr != null -> thermostat(d)
            d.kind == Kind.BLIND -> blind(d)
            d.kind == Kind.LIGHT -> light(d)
            d.canSwitch -> switch(d)
            else -> sensor(d)
        }
        if (d.isGroup) members(d)
        info(d)

        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setDimAmount(.6f)
            attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
        }
        update()
    }

    private fun dragToClose(handle: View, sheet: View) {
        var startY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { sheet.translationY = (e.rawY - startY).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (sheet.translationY > handle.dp(110)) dialog.dismiss()
                    else sheet.animate().translationY(0f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun header(d: Device) = act.row().apply {
        val bubble = FrameLayout(context)
        addView(bubble, lp(dp(46), dp(46)))
        val texts = context.column()
        val name = context.label(21f, C.TEXT, Type.medium).apply { text = d.name; maxLines = 2 }
        val sub = context.label(13f, C.MUTED)
        texts.addView(name)
        texts.addView(sub, lp().margins(t = dp(5)))
        addView(texts, lp(0, WRAP, 1f).margins(l = dp(14), r = dp(10)))
        addView(context.iconButton(Glyph.Kind.CLOSE, 38, C.MUTED) { dialog.dismiss() })
        bind { x ->
            bubble.removeAllViews()
            bubble.addView(context.bubble(kindGlyph(x), Cards.accent(x) ?: C.MUTED, 46))
            name.text = x.name
            sub.text = Cards.subtitle(x, long = true)
        }
    }

    // ---- Radiator -------------------------------------------------------------------------------

    private fun thermostat(d: Device) = with(act) {
        val dial = Dial(this)
        dial.onChange = { lastChange = System.currentTimeMillis() }
        dial.onCommit = { raw ->
            lastChange = System.currentTimeMillis()
            Home.device(devKey)?.let { Act.temp(it, raw) }
        }
        panel.addView(dial, lp(MATCH, WRAP).margins(t = dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Fine steps, debounced so several taps are sent as one command
        val send = Runnable { Home.device(devKey)?.let { Act.temp(it, dial.raw) } }
        val steps = row(Gravity.CENTER)
        fun stepper(g: Glyph.Kind, by: Int) = iconButton(g, 52, C.TEXT, C.GLASS_STRONG) {
            lastChange = System.currentTimeMillis()
            val raw = dial.step(by)
            Home.device(devKey)?.let { Act.temp(it, raw, send = false) }
            dial.removeCallbacks(send)
            dial.postDelayed(send, 700)
        }
        steps.addView(stepper(Glyph.Kind.MINUS, -1))
        steps.addView(space(0f), lp(dp(72), 1))
        steps.addView(stepper(Glyph.Kind.PLUS, 1))
        panel.addView(steps, lp().margins(t = dp(-30)))

        // Presets
        val presets = row()
        data class Preset(val g: Glyph.Kind, val title: String, val raw: (Hkr) -> Int)
        val list = listOf(
            Preset(Glyph.Kind.MOON, "Absenk") { it.absenk },
            Preset(Glyph.Kind.SUN, "Komfort") { it.komfort },
            Preset(Glyph.Kind.POWER, "Aus") { Hkr.OFF },
        )
        for ((i, p) in list.withIndex()) {
            val box = column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(12), dp(8), dp(12))
                pressable(18f)
            }
            val icon = ImageView(this)
            val t1 = label(13f, C.TEXT, Type.medium).apply { text = p.title }
            val t2 = label(12f, C.MUTED)
            box.addView(icon, lp(dp(22), dp(22)))
            box.addView(t1, lp(WRAP, WRAP).margins(t = dp(8)))
            box.addView(t2, lp(WRAP, WRAP).margins(t = dp(3)))
            box.setOnClickListener {
                val x = Home.device(devKey) ?: return@setOnClickListener
                val raw = p.raw(x.hkr ?: return@setOnClickListener)
                if (raw == 255) return@setOnClickListener
                lastChange = System.currentTimeMillis()
                dial.set(raw)
                Act.temp(x, raw)
            }
            presets.addView(box, lp(0, WRAP, 1f).margins(l = if (i > 0) dp(10) else 0))
            bind { x ->
                val h = x.hkr ?: return@bind
                val raw = p.raw(h)
                val on = raw == h.tsoll && raw != 255
                val col = if (raw == Hkr.OFF) C.MUTED else C.raw(raw)
                icon.setImageDrawable(Glyph(p.g, if (on) col else C.MUTED))
                t2.text = if (raw == Hkr.OFF) "Frostschutz" else Fmt.raw(raw)
                box.background = if (on) card(col, 18f) else rounded(C.GLASS, 18f, C.STROKE)
            }
        }
        panel.addView(presets, lp().margins(t = dp(18)))

        // Boost and open window
        panel.addView(toggleRow(Glyph.Kind.ROCKET, "Boost", C.ORANGE,
            sub = { h -> if (h.boost && h.boostEnd > 0) "Volle Leistung bis " + Fmt.time(h.boostEnd) else "30 Minuten volle Heizleistung" },
            state = { it.boost },
            change = { x, on -> Act.boost(x, if (on) 30 else 0) }), lp().margins(t = dp(18)))
        panel.addView(toggleRow(Glyph.Kind.WINDOW, "Fenster offen", C.CYAN,
            sub = { h -> if (h.windowOpen && h.windowEnd > 0) "Heizung pausiert bis " + Fmt.time(h.windowEnd) else "Heizung 15 Minuten pausieren" },
            state = { it.windowOpen },
            change = { x, on -> Act.window(x, if (on) 15 else 0) }), lp().margins(t = dp(10)))

        bind { x ->
            val h = x.hkr ?: return@bind
            dial.ist = h.tist ?: x.temp
            if (!recentlyChanged) dial.set(h.tsoll)
        }
    }

    private fun toggleRow(
        g: Glyph.Kind, title: String, color: Int, sub: (Hkr) -> String, state: (Hkr) -> Boolean, change: (Device, Boolean) -> Unit,
    ) = act.row().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = context.rounded(C.GLASS, 18f, C.STROKE)
        val icon = FrameLayout(context)
        addView(icon, lp(dp(38), dp(38)))
        val texts = context.column()
        val t2 = context.label(12.5f, C.MUTED)
        texts.addView(context.label(15f, C.TEXT, Type.medium).apply { text = title })
        texts.addView(t2, lp().margins(t = dp(4)))
        addView(texts, lp(0, WRAP, 1f).margins(l = dp(12), r = dp(8)))
        val toggle = Toggle(context, color)
        toggle.onToggle = { on ->
            lastChange = System.currentTimeMillis()
            Home.device(devKey)?.let { change(it, on) }
        }
        addView(toggle)
        setOnClickListener { toggle.performClick() }
        bind { x ->
            val h = x.hkr ?: return@bind
            val on = state(h)
            icon.removeAllViews()
            icon.addView(context.bubble(g, if (on) color else null, 38))
            t2.text = sub(h)
            toggle.set(on, true)
        }
    }

    // ---- Switch and light -----------------------------------------------------------------------

    private fun powerButton(color: Int) = with(act) {
        val box = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val btn = ImageView(this).apply {
            val pad = dp(40)
            setPadding(pad, pad, pad, pad)
            pressable(70f)
            elevation = dp(6f)
        }
        val state = label(15f, C.MUTED, Type.medium)
        box.addView(btn, lp(dp(140), dp(140)))
        box.addView(state, lp(WRAP, WRAP).margins(t = dp(16)))
        btn.setOnClickListener {
            val x = Home.device(devKey) ?: return@setOnClickListener
            if (x.switchLocked) return@setOnClickListener
            btn.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            Act.power(x, x.isOn != true)
        }
        bind { x ->
            val on = x.isOn == true
            btn.setImageDrawable(Glyph(Glyph.Kind.POWER, if (on) Color.WHITE else C.MUTED))
            btn.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (on) intArrayOf(withAlpha(color, 0xFF), withAlpha(color, 0xAA)) else intArrayOf(0x1FFFFFFF, 0x0FFFFFFF),
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), if (on) withAlpha(Color.WHITE, 0x40) else C.STROKE)
            }
            btn.outlineSpotShadowColorCompat(if (on) color else Color.BLACK)
            state.text = when {
                !x.present -> "Nicht erreichbar"
                x.switchLocked -> if (on) "Eingeschaltet · gesperrt" else "Ausgeschaltet · gesperrt"
                on -> "Eingeschaltet"
                else -> "Ausgeschaltet"
            }
            state.setTextColor(if (on) color else C.MUTED)
        }
        panel.addView(box, lp().margins(t = dp(14), b = dp(6)))
    }

    private fun ImageView.outlineSpotShadowColorCompat(color: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = color
            outlineAmbientShadowColor = color
        }
    }

    private fun switch(d: Device) {
        powerButton(C.GREEN)
        stats(d)
    }

    private fun light(d: Device) {
        powerButton(C.AMBER)
        if (d.level != null) slider("Helligkeit", C.AMBER, { it.level ?: 0 }) { x, v -> Act.level(x, v) }
    }

    private fun blind(d: Device) = with(act) {
        val buttons = row()
        data class B(val g: Glyph.Kind, val title: String, val target: String)
        for ((i, b) in listOf(B(Glyph.Kind.UP, "Öffnen", "open"), B(Glyph.Kind.STOP, "Stopp", "stop"), B(Glyph.Kind.DOWN, "Schließen", "close")).withIndex()) {
            val box = column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(16), dp(8), dp(16))
                background = rounded(C.GLASS, 20f, C.STROKE)
                pressable(20f)
                addView(ImageView(context).apply { setImageDrawable(Glyph(b.g, C.TEXT)) }, lp(dp(26), dp(26)))
                addView(context.label(13f, C.TEXT, Type.medium).apply { text = b.title }, lp(WRAP, WRAP).margins(t = dp(8)))
                setOnClickListener { Home.device(devKey)?.let { Act.blind(it, b.target) } }
            }
            buttons.addView(box, lp(0, WRAP, 1f).margins(l = if (i > 0) dp(10) else 0))
        }
        panel.addView(buttons, lp().margins(t = dp(10)))
        if (d.level != null) slider("Geschlossen", C.ACCENT, { it.level ?: 0 }) { x, v -> Act.level(x, v) }
    }

    private fun slider(title: String, color: Int, value: (Device) -> Int, commit: (Device, Int) -> Unit) = with(act) {
        val box = column().apply {
            setPadding(dp(16), dp(14), dp(16), dp(10))
            background = rounded(C.GLASS, 18f, C.STROKE)
        }
        val head = row()
        val v = label(15f, C.TEXT, Type.medium)
        head.addView(label(15f, C.TEXT, Type.medium).apply { text = title }, lp(0, WRAP, 1f))
        head.addView(v)
        box.addView(head)
        var touching = false
        val bar = SeekBar(this).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) v.text = "$p %" }
                override fun onStartTrackingTouch(s: SeekBar) { touching = true }
                override fun onStopTrackingTouch(s: SeekBar) {
                    touching = false
                    lastChange = System.currentTimeMillis()
                    Home.device(devKey)?.let { commit(it, s.progress) }
                }
            })
        }
        box.addView(bar, lp().margins(t = dp(10)))
        panel.addView(box, lp().margins(t = dp(18)))
        bind { x ->
            if (touching || recentlyChanged) return@bind
            val p = value(x)
            bar.progress = p
            v.text = "$p %"
        }
    }

    // ---- Sensors and details --------------------------------------------------------------------

    private fun sensor(d: Device) = stats(d, big = true)

    private fun stats(d: Device, big: Boolean = false) = with(act) {
        val grid = column()
        panel.addView(grid, lp().margins(t = dp(14)))
        bind { x ->
            grid.removeAllViews()
            val tiles = buildList {
                x.power?.let { add(Triple(Glyph.Kind.BOLT, "Leistung", Fmt.watts(it))) }
                x.energy?.let { add(Triple(Glyph.Kind.CLOCK, "Verbrauch gesamt", Fmt.kwh(it))) }
                x.voltage?.takeIf { it > 0 }?.let { add(Triple(Glyph.Kind.POWER, "Spannung", Fmt.volts(it))) }
                x.temp?.let { add(Triple(Glyph.Kind.THERMO, "Temperatur", Fmt.temp(it))) }
                x.humidity?.let { add(Triple(Glyph.Kind.DROP, "Luftfeuchte · " + humidityWord(it), "$it %")) }
                x.alert?.let { add(Triple(Glyph.Kind.BELL, "Melder", if (it) "Ausgelöst" else "Ruhe")) }
            }
            for (pair in tiles.chunked(2)) {
                val r = row()
                for ((i, t) in pair.withIndex()) {
                    val tile = column().apply {
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                        background = rounded(C.GLASS, 20f, C.STROKE)
                        addView(ImageView(context).apply { setImageDrawable(Glyph(t.first, C.MUTED)) }, lp(dp(20), dp(20)))
                        addView(context.label(if (big) 30f else 22f, C.TEXT, Type.light).apply { text = t.third }, lp().margins(t = dp(12)))
                        addView(context.label(12.5f, C.MUTED).apply { text = t.second }, lp().margins(t = dp(6)))
                    }
                    r.addView(tile, lp(0, WRAP, 1f).margins(l = if (i > 0) dp(10) else 0))
                }
                if (pair.size == 1) r.addView(space(), lp(0, 1, 1f).margins(l = dp(10)))
                grid.addView(r, lp().margins(t = if (grid.childCount > 0) dp(10) else 0))
            }
        }
    }

    private fun humidityWord(h: Int) = when {
        h < 40 -> "trocken"
        h > 60 -> "feucht"
        else -> "angenehm"
    }

    private fun members(d: Device) = with(act) {
        panel.addView(section("Geräte in der Gruppe"), lp().margins(t = dp(22)))
        val list = column().apply { background = rounded(C.GLASS, 18f, C.STROKE) }
        panel.addView(list, lp().margins(t = dp(10)))
        bind { x ->
            list.removeAllViews()
            val members = x.members.mapNotNull { m -> Home.devices.firstOrNull { it.id == m } }
            for ((i, m) in members.withIndex()) {
                val r = row().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
                r.addView(act.bubble(kindGlyph(m), Cards.accent(m), 32))
                r.addView(act.label(14.5f, C.TEXT).apply { text = m.name; maxLines = 1 }, lp(0, WRAP, 1f).margins(l = dp(12), r = dp(8)))
                r.addView(act.label(13f, C.MUTED).apply { text = Cards.value(m) })
                if (i > 0) list.addView(divider())
                list.addView(r)
            }
        }
    }

    private fun info(d: Device) = with(act) {
        val box = column().apply { background = rounded(C.GLASS, 18f, C.STROKE) }
        val title = section("Info")
        panel.addView(title, lp().margins(t = dp(22)))
        panel.addView(box, lp().margins(t = dp(10)))
        bind { x ->
            box.removeAllViews()
            val rows = mutableListOf<Pair<String, String>>()
            val warn = mutableSetOf<String>()
            x.hkr?.let { h ->
                if (h.nextTime > 0 && h.nextTemp != 255) rows += "Nächste Änderung" to "${Fmt.dayTime(h.nextTime)} · ${Fmt.raw(h.nextTemp)}"
                if (h.summer) rows += "Betrieb" to "Sommerpause"
                if (h.holiday) rows += "Betrieb" to "Urlaubsschaltung"
                if (h.locked) rows += "Bedienung" to "Gesperrt"
                if (h.error != 0) { rows += "Störung" to Fmt.hkrError(h.error); warn += "Störung" }
            }
            x.batteryLevel?.let { rows += "Batterie" to "$it %" }
            if (x.anyBatteryLow) warn += "Batterie"
            if (!x.present) { rows += "Verbindung" to "Nicht erreichbar"; warn += "Verbindung" }
            if (x.product.isNotEmpty() && !x.isGroup) rows += "Gerät" to x.product
            if (x.firmware.isNotEmpty()) rows += "Firmware" to x.firmware
            if (x.ain.isNotEmpty() && !x.ain.startsWith("demo")) rows += "AIN" to x.ain
            title.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
            box.visibility = title.visibility
            for ((i, r) in rows.withIndex()) {
                if (i > 0) box.addView(divider())
                val line = row(Gravity.TOP).apply { setPadding(dp(16), dp(13), dp(16), dp(13)) }
                line.addView(act.label(14f, C.MUTED).apply { text = r.first }, lp(WRAP, WRAP))
                line.addView(act.label(14f, if (r.first in warn) C.RED else C.TEXT).apply {
                    text = r.second
                    gravity = Gravity.END
                }, lp(0, WRAP, 1f).margins(l = dp(16)))
                box.addView(line)
            }
        }
    }

    private fun section(t: String) = act.label(13f, C.MUTED, Type.medium).apply {
        text = t.uppercase()
        letterSpacing = .08f
        setPadding(dp(4), 0, 0, 0)
    }

    private fun divider() = View(act).apply {
        setBackgroundColor(C.STROKE)
        layoutParams = lp(MATCH, 1).margins(l = dp(16), r = dp(16))
    }
}

/** Texts and colours shared by the cards on the main screen and the sheets. */
object Cards {
    fun accent(d: Device): Int? {
        if (!d.present) return null
        d.hkr?.let { h ->
            return when {
                h.windowOpen -> C.CYAN
                h.boost -> C.ORANGE
                h.isOff -> null
                else -> C.raw(h.tsoll)
            }
        }
        return when (d.kind) {
            Kind.LIGHT -> if (d.isOn == true) C.AMBER else null
            Kind.BLIND -> C.ACCENT
            Kind.SENSOR -> null
            Kind.ALARM -> if (d.alert == true) C.RED else null
            else -> if (d.isOn == true) C.GREEN else null
        }
    }

    fun subtitle(d: Device, long: Boolean = false): String {
        if (!d.present) return "Nicht erreichbar"
        val h = d.hkr
        val parts = mutableListOf<String>()
        if (d.isGroup) parts += "${d.members.size} " + if (d.members.size == 1) "Gerät" else "Geräte"
        when {
            h != null -> {
                (h.tist ?: d.temp)?.let { if (!d.isGroup || long) parts += "Gemessen " + Fmt.temp(it) }
                if (long && h.heating) parts += "heizt"
            }
            d.kind == Kind.SENSOR -> d.humidity?.let { parts += "Luftfeuchte $it %" }
            d.kind == Kind.BLIND -> d.level?.let { parts += if (it <= 0) "Geöffnet" else if (it >= 100) "Geschlossen" else "Zu $it %" }
            d.kind == Kind.LIGHT -> parts += if (d.isOn == true) (d.level?.let { "An · $it %" } ?: "An") else "Aus"
            d.canSwitch -> parts += when {
                d.isOn == true && d.power != null -> Fmt.watts(d.power)
                d.isOn == true -> "An"
                else -> "Aus"
            }
            d.alert != null -> parts += if (d.alert) "Ausgelöst" else "Ruhe"
        }
        if (long && !d.isGroup && d.product.isNotEmpty() && d.product != d.name) parts += d.product
        return parts.joinToString(" · ")
    }

    /** The big value at the bottom of a card. */
    fun value(d: Device): String {
        d.hkr?.let { return Fmt.raw(it.tsoll, unit = false) }
        return when (d.kind) {
            Kind.SENSOR -> Fmt.temp(d.temp, unit = false)
            Kind.BLIND -> d.level?.let { "$it %" } ?: "–"
            Kind.LIGHT -> if (d.isOn == true) d.level?.let { "$it %" } ?: "An" else "Aus"
            Kind.ALARM -> if (d.alert == true) "Alarm" else "Ruhe"
            else -> if (d.isOn == true) "An" else "Aus"
        }
    }

    /** Small tag in the top corner of a card for what needs attention. */
    fun badge(d: Device): Pair<String, Int>? {
        val h = d.hkr
        return when {
            !d.present -> "Offline" to C.MUTED
            h != null && h.error != 0 -> "Störung" to C.RED
            h != null && h.windowOpen -> "Fenster" to C.CYAN
            h != null && h.boost -> "Boost" to C.ORANGE
            d.anyBatteryLow -> "Batterie" to C.RED
            h != null && h.heating -> "Heizt" to C.raw(h.tsoll)
            h != null && h.summer -> "Sommer" to C.MUTED
            d.alert == true -> "Alarm" to C.RED
            else -> null
        }
    }
}
