package de.glas.smart

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import kotlin.math.roundToInt

/** Controls for one device, built from the description (specification) the device reports. */
class DeviceActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private lateinit var headBox: LinearLayout
    private var live: LiveView? = null
    private lateinit var device: Device
    private var spec: Spec? = null
    private var shownStatus: Map<String, Any?> = emptyMap()
    private val poll = object : Runnable {
        override fun run() {
            refreshStatus()
            later(10_000, this)
        }
    }

    // Data points that are shown by dedicated controls or can't be set sensibly by hand
    private val skip = setOf(
        "work_mode", "bright_value", "bright_value_v2", "temp_value", "temp_value_v2", "colour_data", "colour_data_v2",
        "scene_data", "scene_data_v2", "music_data", "control_data", "flash_scene_1", "flash_scene_2", "flash_scene_3",
        "flash_scene_4", "rhythm_mode", "sleep_mode", "wakeup_mode", "power_memory", "cycle_time", "random_time",
        "switch_inching", "dreamlight_scene_mode", "dreamlightmic_music_data", "lightpixel_number_set",
        // Cameras: actions that erase or restart, and raw data that only the camera app understands
        "sd_format", "sd_format_state", "sd_umount", "sd_storge", "device_restart", "factory_reset", "ptz_control",
        "ptz_stop", "movement_detect_pic", "doorbell_pic", "alarm_message", "initiative_message", "doorbell_active",
        "record_switch_sd", "ipc_object_outline", "motion_area", "flight_pull_wire", "flight_warn_switch",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Repo.load(store)
        val d = Repo.find(intent.getStringExtra("id") ?: "")
        if (d == null) {
            finish()
            return
        }
        device = d
        spec = Repo.spec(store, d.id)

        val scroll = ScrollView(this)
        val outer = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(32)) }
        headBox = vertical()
        root = vertical()
        outer.addView(headBox, matchWrap())
        // The live picture stays put while the controls below are redrawn
        if (d.isCamera) {
            val lv = LiveView(this, d.id)
            live = lv
            outer.addView(lv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).margins(b = dp(10)))
            // HD/SD (over WebRTC only) and, for talking, Smart Life
            val row = horizontal()
            val qualityBox = horizontal().apply { visibility = View.GONE }
            fun drawQuality() {
                qualityBox.removeAllViews()
                qualityBox.addView(pill("HD", if (lv.hd) AMBER else null) { lv.setQuality(true); drawQuality() }, wrap().margins(r = dp(8)))
                qualityBox.addView(pill("SD", if (!lv.hd) AMBER else null) { lv.setQuality(false); drawQuality() }, wrap().margins(r = dp(8)))
            }
            drawQuality()
            lv.onQuality = { available -> qualityBox.visibility = if (available) View.VISIBLE else View.GONE }
            row.addView(qualityBox)
            row.addView(pill("In Smart Life öffnen", null) { openSmartLife() })
            outer.addView(row, matchWrap().margins(b = dp(6)))
            val status = text(12f, MUTED).apply { visibility = View.GONE; setTextIsSelectable(true) }
            lv.onStatus = { msg ->
                status.text = msg
                status.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
            }
            outer.addView(status, matchWrap().margins(l = dp(4), b = dp(10)))
            lv.post { lv.layoutParams = lv.layoutParams.apply { height = lv.width * 9 / 16 } }
            lv.clipToOutline = true
            lv.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(22f))
            }
        }
        outer.addView(root, matchWrap())
        scroll.addView(outer)
        fitBars(scroll)
        setContentView(scroll)
        render()

        if (spec == null) {
            async({ store.api().specs(d.id) }) { r ->
                r.onSuccess {
                    Repo.putSpec(store, d.id, it)
                    spec = it
                    render()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::device.isInitialized) later(10_000, poll)
        live?.start()
    }

    override fun onPause() {
        super.onPause()
        cancel(poll)
        live?.stop()
    }

    private fun refreshStatus() {
        val d = device
        async({ store.api().status(d.id) }) { r ->
            r.onSuccess { fresh ->
                if ((Repo.pendingUntil[d.id] ?: 0) > System.currentTimeMillis()) return@onSuccess
                d.online = true
                d.status.putAll(fresh)
                if (d.status != shownStatus) render()
            }
        }
    }

    private fun send(vararg commands: Pair<String, Any>) {
        Repo.send(this, device, commands.toList()) { render() }
    }

    // ---- Layout ----

    private fun render() {
        val d = device
        val s = spec
        shownStatus = HashMap(d.status)
        root.removeAllViews()
        headBox.removeAllViews()

        // Header with back arrow
        val head = horizontal()
        head.addView(text(28f).apply {
            text = "‹"
            setPadding(dp(4), 0, dp(16), dp(4))
            setOnClickListener { finish() }
        })
        val titles = vertical()
        titles.addView(text(24f, face = light).apply { text = d.name })
        titles.addView(text(14f, if (d.online) MUTED else RED).apply {
            text = listOf(d.product.takeIf { it.isNotBlank() && it != d.name }, if (d.online) "Online" else "Offline")
                .filterNotNull().joinToString(" · ")
        })
        head.addView(titles, weight())
        headBox.addView(head, matchWrap().margins(t = dp(8), b = dp(12)))

        val enabled = d.online
        val accent = if (d.kind == Kind.LIGHT) AMBER else GREEN

        // Power
        val switches = d.switchCodes()
        if (switches.size == 1) {
            root.addView(bigPower(d, switches[0], accent, enabled), matchWrap().margins(b = dp(12)))
        } else if (switches.size > 1) {
            val list = vertical()
            list.addView(horizontal().apply {
                addView(pill("Alle an", null) { send(*d.powerCommands(true).toTypedArray()) }, wrap().margins(r = dp(8)))
                addView(pill("Alle aus", null) { send(*d.powerCommands(false).toTypedArray()) })
            }, matchWrap().margins(b = dp(6)))
            for (code in switches) {
                list.addView(switchRow(Labels.name(code), d.bool(code) == true, enabled) { send(code to it) })
            }
            root.addView(panel("Schalter", list), matchWrap().margins(b = dp(12)))
        }

        if (d.kind == Kind.LIGHT) lightControls(d, s, enabled)

        // Readings such as power, temperature, door contact
        val readings = readingCodes(d, s)
        if (readings.isNotEmpty()) {
            val grid = vertical()
            for (i in readings.indices step 2) {
                val row = horizontal()
                row.addView(tile(readings[i], d, s), weight().margins(r = dp(5)))
                if (i + 1 < readings.size) row.addView(tile(readings[i + 1], d, s), weight().margins(l = dp(5)))
                else row.addView(View(this), weight().margins(l = dp(5)))
                grid.addView(row, matchWrap().margins(b = dp(10)))
            }
            root.addView(text(14f, MUTED, medium).apply { text = "Messwerte" }, matchWrap().margins(t = dp(4), b = dp(8), l = dp(4)))
            root.addView(grid, matchWrap())
        }

        // Every other setting the device offers
        if (s != null) {
            val others = s.functions.values.filter { it.code !in skip && it.code !in switches }
            val box = vertical()
            for (fn in others) genericControl(d, fn, enabled)?.let { box.addView(it, matchWrap().margins(b = dp(10))) }
            if (box.childCount > 0) root.addView(panel("Einstellungen", box), matchWrap().margins(t = dp(2), b = dp(12)))
        } else {
            root.addView(text(14f, MUTED).apply { text = "Lade Funktionen des Geräts …" }, matchWrap().margins(t = dp(8)))
        }

        root.addView(text(12f, 0x66F3F6FA).apply {
            text = "Geräte-ID ${d.id} · Kategorie ${d.category}"
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
        }, matchWrap().margins(t = dp(16)))
    }

    private fun bigPower(d: Device, code: String, accent: Int, enabled: Boolean): View {
        val on = d.bool(code) == true
        return horizontal().apply {
            background = if (on) glass(22f, (accent and 0x00FFFFFF) or 0x1F000000, (accent and 0x00FFFFFF) or 0x55000000) else glass()
            setPadding(dp(18), dp(18), dp(18), dp(18))
            val texts = vertical()
            texts.addView(text(22f, face = medium).apply { text = if (on) "An" else "Aus" })
            texts.addView(text(14f, MUTED).apply { text = d.summary(spec) })
            addView(texts, weight())
            addView(PowerButton(context, accent).apply {
                this.on = on
                isEnabled = enabled
                setOnClickListener { send(code to !on) }
            }, LinearLayout.LayoutParams(dp(60), dp(60)))
        }
    }

    private fun first(d: Device, vararg codes: String) = codes.firstOrNull { it in d.status || spec?.fn(it) != null }

    private fun lightControls(d: Device, s: Spec?, enabled: Boolean) {
        val mode = d.status["work_mode"] as? String
        val brightCode = first(d, "bright_value_v2", "bright_value")
        val tempCode = first(d, "temp_value_v2", "temp_value")
        val colourCode = first(d, "colour_data_v2", "colour_data")
        val colourFn = colourCode?.let { s?.fn(it) }
        val colour = colourCode?.let { Colour.parse(d.status[it], colourFn) } ?: Colour(0, 1.0, 1.0)
        val box = vertical()

        // Brightness: in colour mode it is the colour's value, otherwise its own data point
        if (mode == "colour" && colourCode != null) {
            box.addView(sliderRow("Helligkeit", 1, 100, (colour.v * 100).roundToInt(), AMBER, enabled, { "$it %" }) {
                send(colourCode to colour.copy(v = it / 100.0).toJson(colourFn))
            }, matchWrap())
        } else if (brightCode != null) {
            val fn = s?.fn(brightCode)
            val min = fn?.min ?: if (brightCode == "bright_value") 25 else 10
            val max = fn?.max ?: if (brightCode == "bright_value") 255 else 1000
            val v = d.int(brightCode) ?: max
            box.addView(sliderRow("Helligkeit", min, max, v, AMBER, enabled, { "${(it * 100f / max).roundToInt().coerceAtLeast(1)} %" }) {
                send(brightCode to it)
            }, matchWrap())
        }

        if (tempCode != null) {
            val fn = s?.fn(tempCode)
            val min = fn?.min ?: 0
            val max = fn?.max ?: if (tempCode == "temp_value") 255 else 1000
            val v = d.int(tempCode) ?: (max / 2)
            box.addView(sliderRow("Weißton", min, max, v, 0xFFDDEBFF.toInt(), enabled, {
                val f = (it - min).toFloat() / (max - min).coerceAtLeast(1)
                when {
                    f < .33f -> "Warm"
                    f < .66f -> "Neutral"
                    else -> "Kalt"
                }
            }) {
                if (mode != "white" && "work_mode" in d.status) send("work_mode" to "white", tempCode to it) else send(tempCode to it)
            }, matchWrap().margins(t = dp(6)))
        }

        if (colourCode != null) {
            box.addView(text(17f).apply { text = "Farbe" }, matchWrap().margins(t = dp(10), b = dp(6)))
            fun setColour(c: Colour) {
                if ("work_mode" in d.status) send("work_mode" to "colour", colourCode to c.toJson(colourFn))
                else send(colourCode to c.toJson(colourFn))
            }
            box.addView(HueBar(this, colour.h) { setColour(colour.copy(h = it, s = colour.s.coerceAtLeast(.3))) }.apply {
                isEnabled = enabled
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            box.addView(sliderRow("Sättigung", 0, 100, (colour.s * 100).roundToInt(), Color.HSVToColor(floatArrayOf(colour.h.toFloat(), 1f, 1f)),
                enabled, { "$it %" }) { setColour(colour.copy(s = it / 100.0)) }, matchWrap().margins(t = dp(6)))

            // Quick colours, including warm and cold white
            val swatches = horizontal()
            val presets = ArrayList<Pair<Int, () -> Unit>>()
            if (tempCode != null) {
                val fn = s?.fn(tempCode)
                val tMin = fn?.min ?: 0
                val tMax = fn?.max ?: 1000
                presets += 0xFFFFC46B.toInt() to { send("work_mode" to "white", tempCode to tMin) }
                presets += 0xFFEFF4FF.toInt() to { send("work_mode" to "white", tempCode to tMax) }
            }
            for (h in listOf(0, 28, 55, 120, 185, 230, 275, 320)) {
                presets += Color.HSVToColor(floatArrayOf(h.toFloat(), 1f, 1f)) to { setColour(Colour(h, 1.0, colour.v.coerceAtLeast(.5))) }
            }
            presets.forEachIndexed { i, (c, action) ->
                swatches.addView(swatch(c) { if (enabled) action() }, LinearLayout.LayoutParams(0, dp(30), 1f).margins(r = if (i < presets.size - 1) dp(6) else 0))
            }
            box.addView(swatches, matchWrap().margins(t = dp(10)))
        }

        val modeFn = s?.fn("work_mode")
        if (modeFn != null && modeFn.range.size > 1) {
            box.addView(text(17f).apply { text = "Modus" }, matchWrap().margins(t = dp(16), b = dp(8)))
            box.addView(chips(modeFn.range.map { it to Labels.enumLabel("work_mode", it) }, mode, AMBER) { if (enabled) send("work_mode" to it) }, matchWrap())
        }

        if (box.childCount > 0) root.addView(panel(null, box), matchWrap().margins(b = dp(12)))
    }

    private fun readingCodes(d: Device, s: Spec?): List<String> {
        val switches = d.switchCodes().toSet()
        val bad = setOf("Json", "Raw", "String")
        return d.status.keys.filter { code ->
            code !in switches && code !in skip && (s == null || !s.canSet(code)) &&
                (s?.status(code)?.type ?: "") !in bad && d.status[code] !is JSONObject &&
                !(d.status[code] is String && (d.status[code] as String).startsWith("{"))
        }.sortedBy { Labels.sensorOrder.indexOf(it).let { i -> if (i < 0) 999 else i } }
    }

    private fun tile(code: String, d: Device, s: Spec?) = vertical().apply {
        background = glass(18f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(text(13f, MUTED).apply { text = Labels.name(code) })
        val value = Labels.value(code, d.status[code], s?.status(code))
        addView(text(20f, if (value.endsWith("!")) RED else TEXT, medium).apply { text = value }, matchWrap().margins(t = dp(2)))
    }

    private fun genericControl(d: Device, fn: Fn, enabled: Boolean): View? {
        val code = fn.code
        val value = d.status[code]
        return when (fn.type) {
            "Boolean" -> switchRow(Labels.name(code), value == true, enabled) { send(code to it) }
            "Enum" -> {
                if (fn.range.isEmpty()) return null
                vertical().apply {
                    addView(text(17f).apply { text = Labels.name(code) }, matchWrap().margins(b = dp(8)))
                    addView(chips(fn.range.map { it to Labels.enumLabel(code, it) }, value as? String, GREEN) { if (enabled) send(code to it) })
                }
            }
            "Integer" -> {
                if (code.startsWith("countdown")) {
                    val options = listOf(0, 900, 1800, 3600, 7200, 14400).filter { it <= fn.max }
                    vertical().apply {
                        val left = (value as? Number)?.toInt() ?: 0
                        addView(text(17f).apply {
                            text = Labels.name(code) + if (left > 0) " · noch ${Labels.duration(left)}" else ""
                        }, matchWrap().margins(b = dp(8)))
                        addView(chips(options.map { it.toString() to Labels.duration(it) }, if (left == 0) "0" else null, GREEN) {
                            if (enabled) send(code to it.toInt())
                        })
                    }
                } else {
                    val min = fn.min
                    val max = fn.max
                    if (max <= min) return null
                    val v = (value as? Number)?.toInt() ?: min
                    sliderRow(Labels.name(code), min, max, v, GREEN, enabled, { Labels.value(code, it, fn) }) {
                        val snapped = (min + ((it - min).toDouble() / fn.step).roundToInt() * fn.step).coerceIn(min, max)
                        send(code to snapped)
                    }
                }
            }
            else -> null
        }
    }
}
