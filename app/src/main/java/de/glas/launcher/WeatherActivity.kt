package de.glas.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detailed weather on frosted-glass cards over the current background: now, the next
 * 12 hours, 7 days, wind, humidity, UV, air quality, pollen and sun times.
 * Opened from the weather in the status bar or the weather card.
 */
class WeatherActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var backdrop: Backdrop
    private lateinit var glassLayer: GlassLayer
    private lateinit var content: LinearLayout
    private lateinit var places: LinearLayout
    private lateinit var stand: TextView
    private lateinit var place: Place
    private val glass = mutableListOf<GlassLayer.Glass>()
    private val clock = SimpleDateFormat("HH:mm", Locale.GERMANY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // Opened only once a place has been chosen; leave quietly otherwise
        place = prefs.mainPlace ?: run { finish(); return }
        val root = FrameLayout(this)
        backdrop = Backdrops.create(this, prefs)
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        glassLayer = GlassLayer(this).also { it.backdrop = backdrop }
        root.addView(glassLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Slightly darker overall, so the cards read well on any background
        root.addView(View(this).apply { setBackgroundColor(0x33000000) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(22), dp(40), dp(18))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        places = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(places, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        stand = Ui.readable(Ui.text(this, 13f, color = Ui.MUTED))
        header.addView(stand)
        page.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        buildPlaces()
        load()
    }

    override fun onResume() {
        super.onResume()
        backdrop.resume()
    }

    override fun onPause() {
        super.onPause()
        backdrop.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        backdrop.release()
    }

    /** One button per weather place, to switch between them. */
    private fun buildPlaces() {
        places.removeAllViews()
        for (p in prefs.places) {
            val chip = Ui.row(this, p.name) {
                place = p
                buildPlaces()
                load()
            }.apply { setPadding(dp(16), dp(6), dp(16), dp(6)) }
            // Size the chip to its text instead of stretching the title
            val title = chip.getChildAt(0) as TextView
            title.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            if (p.key == place.key) title.text = "● ${p.name}"
            places.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        places.getChildAt(prefs.places.indexOfFirst { it.key == place.key }.coerceAtLeast(0))?.requestFocus()
    }

    private fun load() {
        showMessage(tr("Wetter für {0} wird geladen …", place.name))
        val p = place
        Thread {
            val result = runCatching { WeatherApi.forecast(this, p) }
            runOnUiThread {
                if (p.key != place.key || isDestroyed) return@runOnUiThread
                val loaded = result.getOrNull()
                if (loaded == null) {
                    val e = result.exceptionOrNull()
                    showMessage(tr("Keine Verbindung zum Wetterdienst") + "\n" + (e?.message ?: e?.javaClass?.simpleName ?: ""))
                    return@runOnUiThread
                }
                show(loaded.forecast)
                if (loaded.offline) toast(tr("Keine Verbindung zum Wetterdienst ({0}). Angezeigt wird der letzte Stand.", loaded.error ?: "?"))
            }
        }.start()
    }

    private fun showMessage(text: String) {
        clearCards()
        content.addView(Ui.readable(Ui.text(this, 20f)).apply {
            this.text = text
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun clearCards() {
        content.removeAllViews()
        glassLayer.glass.removeAll(glass)
        glass.clear()
    }

    // ---- Layout ----

    private fun show(f: WeatherApi.Forecast) {
        clearCards()
        stand.text = tr("{0} · Stand {1}", place.name, clock.format(Date(f.now.time))) + " · " + f.source
        if (place.key == prefs.mainPlace?.key) backdrop.setWeather(WeatherApi.skyFor(f.now.code), f.now.isDay)

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(currentCard(f), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(12) })
        top.addView(hourlyCard(f), LinearLayout.LayoutParams(0, MATCH_PARENT, 2f))
        content.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, 0, 175f))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(dailyCard(f), LinearLayout.LayoutParams(0, MATCH_PARENT, 1.1f).apply { marginEnd = dp(12) })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val today = f.days.firstOrNull()
        val small = listOf(
            windCard(f),
            infoCard(tr("💧  Feuchtigkeit"), "${f.humidity} %", tr("Luftdruck {0} hPa", f.pressure)),
            today?.uv.let { uv ->
                if (uv == null) infoCard(tr("☀  UV-Index"), "–", tr("keine Daten"))
                else infoCard(tr("☀  UV-Index"), uvLabel(uv), tr("Höchstwert heute {0}", "%.0f".format(uv)))
            },
            infoCard(tr("🍃  Luftqualität"), aqiLabel(f.aqi), f.aqi?.let { tr("Europäischer Index {0}", it) } ?: tr("keine Daten")),
            pollenCard(f),
            infoCard(tr("🌅  Sonne"), "${today?.sunrise ?: "–"}  ·  ${today?.sunset ?: "–"}", tr("Aufgang · Untergang")),
        )
        for (rowItems in small.chunked(3)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { i, v ->
                row.addView(v, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (i < 2) marginEnd = dp(12) })
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { if (grid.childCount == 0) bottomMargin = dp(12) })
        }
        bottom.addView(grid, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.9f))
        content.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, 0, 255f).apply { topMargin = dp(12) })
    }

    /** Card with frosted glass behind it. */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = Ui.outline(this@WeatherActivity, dp(20f))
        val g = GlassLayer.Glass(this, dp(20f), Ui.glassTint(prefs.glass, -0x10))
        glass += g
        glassLayer.glass += g
    }

    private fun label(text: String, size: Float = 13f, color: Int = Ui.MUTED) = Ui.text(this, size, color = color).apply { this.text = text }

    private fun currentCard(f: WeatherApi.Forecast) = card().apply {
        gravity = Gravity.CENTER_VERTICAL
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(WeatherIcon(context).apply { set(WeatherApi.skyFor(f.now.code), f.now.isDay) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        row.addView(Ui.text(context, 54f, Ui.thin).apply {
            text = "${f.now.temp}°"
            setPadding(dp(12), 0, 0, 0)
        })
        addView(row)
        addView(label(WeatherApi.describe(f.now.code), 20f, Color.WHITE))
        addView(label(tr("↑ {0}°  ↓ {1}°  ·  Gefühlt wie {2}°", f.now.max, f.now.min, f.feelsLike), 14f).apply { setPadding(0, dp(4), 0, 0) })
    }

    private fun hourlyCard(f: WeatherApi.Forecast) = card().apply {
        addView(label(tr("Nächste 12 Stunden")))
        val cols = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (h in f.hours) {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            col.addView(label(h.time.take(5), 12f))
            col.addView(WeatherIcon(context).apply { set(WeatherApi.skyFor(h.code), h.isDay) }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { topMargin = dp(4) })
            col.addView(label("${h.temp}°", 15f, Color.WHITE).apply { setPadding(0, dp(4), 0, 0) })
            cols.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        addView(cols, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(TempChart(context, f.hours.map { it.temp }), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val rain = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (h in f.hours) rain.addView(label("💧${h.rain}%", 11f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(rain)
    }

    private fun dailyCard(f: WeatherApi.Forecast) = card().apply {
        addView(label(tr("7 Tage")))
        val weekday = SimpleDateFormat("EE", I18n.locale)
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        f.days.forEachIndexed { i, d ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = if (i == 0) tr("Heute") else runCatching { weekday.format(iso.parse(d.date)!!) }.getOrDefault(d.date)
            row.addView(label(name, 15f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.2f))
            row.addView(label("💧${d.rain}%", 12f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(WeatherIcon(context).apply { set(WeatherApi.skyFor(d.code), true) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            row.addView(label("${d.max}°  ${d.min}°", 15f, Color.WHITE).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f))
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    private fun infoCard(title: String, value: String, detail: String) = card().apply {
        addView(label(title))
        addView(label(value, 22f, Color.WHITE).apply { setPadding(0, dp(6), 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(label(detail, 12f))
    }

    private fun windCard(f: WeatherApi.Forecast) = card().apply {
        addView(label(tr("〰  Wind")))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The arrow points where the wind blows to
        row.addView(label("↑", 26f, Color.WHITE).apply { rotation = (f.windDir + 180f) % 360f })
        row.addView(label(tr("{0} km/h", f.windKmh), 22f, Color.WHITE).apply { setPadding(dp(10), 0, 0, 0) })
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addView(label(tr("aus {0} · {1}", compass(f.windDir), windLabel(f.windKmh)), 12f))
    }

    private fun pollenCard(f: WeatherApi.Forecast) = card().apply {
        addView(label(tr("🌾  Pollen")))
        val known = f.pollen.filterValues { it != null }
        if (known.isEmpty()) {
            addView(label(tr("keine Daten"), 18f, Color.WHITE).apply { setPadding(0, dp(6), 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(label(tr("außerhalb der Saison oder Region"), 12f))
            return@apply
        }
        for ((name, v) in known) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label(name, 13f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(label(pollenLabel(v!!), 13f, Color.WHITE))
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    // ---- Wording ----

    private fun compass(deg: Int) = listOf(tr("Nord"), tr("Nordost"), tr("Ost"), tr("Südost"), tr("Süd"), tr("Südwest"), tr("West"), tr("Nordwest"))[((deg + 22) % 360) / 45]

    private fun windLabel(kmh: Int) = when {
        kmh < 2 -> tr("windstill")
        kmh < 12 -> tr("leichte Brise")
        kmh < 29 -> tr("mäßiger Wind")
        kmh < 50 -> tr("frischer bis starker Wind")
        kmh < 75 -> tr("stürmisch")
        else -> tr("Sturm")
    }

    private fun uvLabel(uv: Double) = when {
        uv < 3 -> tr("Niedrig")
        uv < 6 -> tr("Mäßig")
        uv < 8 -> tr("Hoch")
        uv < 11 -> tr("Sehr hoch")
        else -> tr("Extrem")
    }

    private fun aqiLabel(aqi: Int?) = when {
        aqi == null -> "–"
        aqi <= 20 -> tr("Sehr gut")
        aqi <= 40 -> tr("Gut")
        aqi <= 60 -> tr("Mäßig")
        aqi <= 80 -> tr("Schlecht")
        aqi <= 100 -> tr("Sehr schlecht")
        else -> tr("Extrem schlecht")
    }

    /** Grains per m³ in rough levels; good enough for a glance. */
    private fun pollenLabel(v: Double) = when {
        v < 1 -> tr("keine")
        v < 15 -> tr("gering")
        v < 50 -> tr("mittel")
        else -> tr("hoch")
    }
}

/** Small line chart of the hourly temperatures, aligned with the hour columns above it. */
private class TempChart(context: Context, private val temps: List<Int>) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.5f)
        color = 0xFFFFD54F.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        if (temps.size < 2) return
        val lo = temps.min()
        val hi = temps.max()
        val span = (hi - lo).coerceAtLeast(1)
        val step = width.toFloat() / temps.size
        val pad = context.dp(6f)
        fun y(t: Int) = pad + (height - 2 * pad) * (1f - (t - lo).toFloat() / span)
        path.reset()
        temps.forEachIndexed { i, t ->
            val x = step * (i + .5f)
            if (i == 0) path.moveTo(x, y(t)) else path.lineTo(x, y(t))
        }
        c.drawPath(path, line)
        temps.forEachIndexed { i, t -> c.drawCircle(step * (i + .5f), y(t), context.dp(3f), dot) }
    }
}
