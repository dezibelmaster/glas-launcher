package de.glas.smarthome

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

/** Temperature course of the last 24 hours, loaded when the sheet opens. */
fun Activity.historyCard(d: Device): View {
    val box = column().apply {
        setPadding(dp(14), dp(14), dp(14), dp(10))
        background = rounded(C.GLASS, 18f, C.STROKE)
    }
    val sub = label(12.5f, C.MUTED).apply { text = "Gemessene Temperatur · letzte 24 Stunden" }
    box.addView(sub, lp().margins(l = dp(4)))
    val chart = Chart(this).apply { color = d.hkr?.let { C.raw(it.tsoll).takeIf { _ -> !it.isOff } } ?: C.ORANGE }
    val state = label(13.5f, C.MUTED).apply {
        text = "Lade Verlauf …"
        gravity = Gravity.CENTER
        setPadding(0, dp(50), 0, dp(50))
    }
    box.addView(state, lp())
    box.addView(chart, lp().margins(t = dp(8)))
    chart.visibility = View.GONE
    Home.fetch({ it.stats(d) }) { s, err ->
        if (s != null && s.valid) {
            state.visibility = View.GONE
            chart.visibility = View.VISIBLE
            chart.stats = s
            val known = s.values.filterNotNull()
            sub.text = "Gemessene Temperatur · 24 Std. · " + Fmt.temp(known.min(), false) + " bis " + Fmt.temp(known.max(), false)
        } else {
            state.text = err ?: "Die FRITZ!Box hat noch keinen Verlauf für dieses Gerät."
        }
    }
    return box
}

/**
 * Settings of a radiator controller, like in the FRITZ!Box: temperature measurement, open-window detection,
 * operating lock, temperature range and adaptive heating. Needs the Smart Home REST API (FRITZ!OS 8.20 and newer).
 */
class HkrSettings(private val act: Activity, private val d: Device) {
    private val box = act.column()
    private var cfg: HkrConfig? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, Runnable>()

    fun view(): View {
        box.addView(act.label(13.5f, C.MUTED).apply {
            text = "Lade Einstellungen …"
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
            background = act.rounded(C.GLASS, 18f, C.STROKE)
        }, lp())
        load()
        return box
    }

    private fun load() {
        Home.fetch({ it.config(d) }) { c, err ->
            if (c != null) { cfg = c; render() } else showError(err ?: "Keine Einstellungen.")
        }
    }

    private fun showError(msg: String) = with(act) {
        box.removeAllViews()
        box.addView(label(13.5f, C.MUTED).apply {
            text = msg + "\n\nTippen zum Wiederholen"
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = rounded(C.GLASS, 18f, C.STROKE)
            setOnClickListener { box.removeAllViews(); view() }
        }, lp())
    }

    /** Sends [body] (fields of `thermostatInterface`) after [delay] ms; a newer change of the same [key] replaces it. */
    private fun send(key: String, body: JSONObject, delay: Long = 0) {
        pending.remove(key)?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pending.remove(key)
            Home.fetch({ it.setConfig(d, body) }) { _, err ->
                if (err != null) {
                    Toast.makeText(act, err, Toast.LENGTH_LONG).show()
                    load()
                }
            }
        }
        pending[key] = r
        handler.postDelayed(r, delay)
    }

    private fun render() {
        val c = cfg ?: return
        box.removeAllViews()
        measurement(c)
        window(c)
        locks(c)
        range(c)
        if (c.adaptive != null) adaptive(c)
    }

    // ---- Building blocks ----------------------------------------------------------------------

    private fun card(title: String, note: String? = null): LinearLayout = with(act) {
        val card = column().apply { background = rounded(C.GLASS, 18f, C.STROKE) }
        card.addView(label(15f, C.TEXT, Type.medium).apply {
            text = title
            setPadding(dp(16), dp(14), dp(16), 0)
        })
        if (note != null) card.addView(label(12.5f, C.MUTED).apply {
            text = note
            setLineSpacing(0f, 1.2f)
            setPadding(dp(16), dp(5), dp(16), 0)
        })
        box.addView(card, lp().margins(t = if (box.childCount > 0) dp(10) else 0))
        card
    }

    private fun LinearLayout.line(title: String, sub: String? = null): LinearLayout = with(act) {
        val r = row().apply { setPadding(dp(16), dp(12), dp(14), dp(12)) }
        val texts = column()
        texts.addView(label(14f, C.TEXT).apply { text = title })
        if (sub != null) texts.addView(label(12f, C.MUTED).apply { text = sub; setLineSpacing(0f, 1.15f) }, lp().margins(t = dp(3)))
        r.addView(texts, lp(0, WRAP, 1f).margins(r = dp(10)))
        this@line.addView(r)
        r
    }

    /** Value with − and + buttons. [step] returns the new value or null when it cannot go further. */
    private fun LinearLayout.stepper(title: String, sub: String?, initial: String, minus: () -> String?, plus: () -> String?): TextView = with(act) {
        val r = line(title, sub)
        val value = label(15f, C.TEXT, Type.medium).apply {
            text = initial
            gravity = Gravity.END
            minWidth = dp(64)
        }
        r.addView(value)
        r.addView(iconButton(Glyph.Kind.MINUS, 34) { minus()?.let { value.text = it } }, lp(dp(34), dp(34)).margins(l = dp(10)))
        r.addView(iconButton(Glyph.Kind.PLUS, 34) { plus()?.let { value.text = it } }, lp(dp(34), dp(34)).margins(l = dp(6)))
        value
    }

    private fun LinearLayout.toggle(title: String, sub: String?, on: Boolean, color: Int = C.ACCENT, change: (Boolean) -> Unit): Toggle {
        val r = line(title, sub)
        val t = Toggle(act, color)
        t.set(on)
        t.onToggle = change
        r.addView(t)
        r.setOnClickListener { t.performClick() }
        return t
    }

    private fun LinearLayout.divider() = addView(View(act).apply { setBackgroundColor(C.STROKE) }, lp(MATCH, 1).margins(l = act.dp(16), r = act.dp(16)))

    private fun t(c: Double) = String.format(Locale.GERMANY, "%.1f °C", c)

    // ---- Sections -----------------------------------------------------------------------------

    private fun measurement(c: HkrConfig) {
        val card = card("Temperaturmessung", "Der Regler misst am Heizkörper. Gib die tatsächliche Raumtemperatur an, damit er richtig heizt.")
        val room = d.hkr?.tist ?: d.temp
        if (c.offsetExternal) {
            card.line("Externer Temperatursensor", "Die Raumtemperatur kommt von einem anderen Gerät. Ändern in der FRITZ!Box-Oberfläche.")
            return
        }
        val offset0 = c.offset ?: 0.0
        if (room == null) {
            card.line("Keine Messwerte", null)
            return
        }
        // The box reports the corrected temperature; without the correction it is what the controller itself measures
        val atDevice = room - offset0
        var offset = offset0
        card.addView(View(act), lp(MATCH, act.dp(4)))
        val r = card.line("Am Heizkörperregler", null)
        r.addView(act.label(15f, C.MUTED).apply { text = t(atDevice) })
        card.divider()
        fun shown() = t(atDevice + offset) + if (offset != 0.0) String.format(Locale.GERMANY, "  (%+.1f)", offset) else ""
        fun change(by: Double): String? {
            val n = (offset + by).coerceIn(-5.0, 5.0)
            if (n == offset) return null
            offset = n
            send("offset", JSONObject().put("temperatureOffset", JSONObject().put("sensorMode", "internal").put("internalOffset", n)), 900)
            return shown()
        }
        card.stepper("Im Raum", null, shown(), { change(-.5) }, { change(.5) })
        card.addView(View(act), lp(MATCH, act.dp(4)))
    }

    private fun window(c: HkrConfig) {
        val card = card("Fenster-auf-Erkennung", "Fällt die Temperatur stark, schließt der Regler das Ventil für die eingestellte Zeit.")
        if (c.windowExternal) {
            card.line("Fenstersensor", "Ein Tür-/Fenstersensor meldet offene Fenster. Ändern in der FRITZ!Box-Oberfläche.")
            return
        }
        val steps = (1..10).toList() + (15..120 step 5).toList()
        var minutes = c.windowDuration ?: 10
        var sensitivity = c.windowSensitivity ?: "medium"
        fun body() = JSONObject().put("windowOpenMode", JSONObject()
            .put("sensorMode", "internal").put("internalDuration", minutes).put("internalSensitivity", sensitivity))
        fun move(by: Int): String? {
            val i = steps.indexOfFirst { it >= minutes }.let { if (it < 0) steps.size - 1 else it }
            val n = steps[(i + by).coerceIn(0, steps.size - 1)]
            if (n == minutes) return null
            minutes = n
            send("window", body(), 900)
            return "$n Min."
        }
        card.addView(View(act), lp(MATCH, act.dp(4)))
        card.stepper("Ventil schließen für", null, "$minutes Min.", { move(-1) }, { move(1) })
        card.divider()
        card.line("Empfindlichkeit", null)
        val seg = act.row().apply {
            background = act.rounded(C.GLASS, 16f, C.STROKE)
            setPadding(act.dp(3), act.dp(3), act.dp(3), act.dp(3))
        }
        val options = listOf("low" to "niedrig", "medium" to "mittel", "high" to "hoch")
        val views = mutableListOf<TextView>()
        fun paint() = views.forEachIndexed { i, tv ->
            val on = options[i].first == sensitivity
            tv.background = if (on) act.rounded(C.TEXT, 13f) else null
            tv.setTextColor(if (on) C.BG else C.TEXT)
        }
        for ((key, title) in options) {
            val tv = act.label(13.5f, C.TEXT, Type.medium).apply {
                text = title
                gravity = Gravity.CENTER
                setPadding(0, act.dp(9), 0, act.dp(9))
                setOnClickListener {
                    if (sensitivity == key) return@setOnClickListener
                    sensitivity = key
                    paint()
                    send("window", body())
                }
            }
            views += tv
            seg.addView(tv, lp(0, WRAP, 1f))
        }
        paint()
        card.addView(seg, lp().margins(l = act.dp(16), r = act.dp(16), b = act.dp(14)))
    }

    private fun locks(c: HkrConfig) {
        if (c.lockLocal == null && c.lockApi == null) return
        val card = card("Bediensperre")
        c.lockLocal?.let { on ->
            card.toggle("Tasten am Regler sperren", "Am Gerät lässt sich nichts mehr verstellen.", on, C.ORANGE) {
                send("lockLocal", JSONObject().put("lockedDeviceLocalEnabled", it))
            }
        }
        if (c.lockLocal != null && c.lockApi != null) card.divider()
        c.lockApi?.let { on ->
            card.toggle("App und Oberfläche sperren",
                "Temperatur per App, FRITZ!Fon und FRITZ!Box-Oberfläche sperren. Danach lässt sie sich auch hier nicht mehr ändern.", on, C.ORANGE) {
                send("lockApi", JSONObject().put("lockedDeviceApiEnabled", it))
            }
        }
        card.addView(View(act), lp(MATCH, act.dp(2)))
    }

    private fun range(c: HkrConfig) {
        if (c.rangeEnabled == null) return
        val card = card("Temperaturbegrenzung", "Die Temperatur lässt sich nur in diesem Bereich einstellen. Boost und Frostschutz gehen weiterhin.")
        var on = c.rangeEnabled
        var min = c.rangeMin ?: 16.0
        var max = c.rangeMax ?: 23.0
        fun body() = JSONObject().put("temperatureRangeLock", JSONObject()
            .put("temperatureLockEnabled", on)
            .put("minimum", JSONObject().put("mode", "temperature").put("celsius", min))
            .put("maximum", JSONObject().put("mode", "temperature").put("celsius", max)))
        val limits = act.column()
        card.toggle("Temperaturregelung begrenzen", null, on, C.GREEN) {
            on = it
            limits.visibility = if (it) View.VISIBLE else View.GONE
            send("range", body())
        }
        card.addView(limits)
        limits.divider()
        limits.stepper("Minimum", null, t(min), {
            if (min <= 8.0) null else { min -= .5; send("range", body(), 900); t(min) }
        }, {
            if (min + .5 >= max) null else { min += .5; send("range", body(), 900); t(min) }
        })
        limits.divider()
        limits.stepper("Maximum", null, t(max), {
            if (max - .5 <= min) null else { max -= .5; send("range", body(), 900); t(max) }
        }, {
            if (max >= 28.0) null else { max += .5; send("range", body(), 900); t(max) }
        })
        limits.visibility = if (on) View.VISIBLE else View.GONE
        card.addView(View(act), lp(MATCH, act.dp(2)))
    }

    private fun adaptive(c: HkrConfig) {
        val card = card("Heizverhalten")
        card.toggle("Adaptive Heizphase", "Beginnt früher zu heizen, damit die Komforttemperatur pünktlich erreicht ist.", c.adaptive == true, C.ORANGE) {
            send("adaptive", JSONObject().put("adaptiveHeatingModeEnabled", it))
        }
        card.addView(View(act), lp(MATCH, act.dp(2)))
    }
}
