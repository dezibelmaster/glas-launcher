package de.glas.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Control centre and input selection in the glass look of tvOS: a panel at the right edge
 * with round "pills". It is its own window, so it also opens over other apps (e.g. from a
 * remote key) and, from Android 12, blurs what lies behind it.
 */
class PanelActivity : Activity() {
    private lateinit var content: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var mode = MODE_PANEL
    private val blur get() = Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PANEL
        val dm = resources.displayMetrics
        window.setLayout(dp(560), dm.heightPixels - dp(56))
        window.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        window.attributes = window.attributes.apply { x = dp(32) }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(.35f)
        window.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(40f)
            setColor(if (blur) 0x4D0E1218 else 0xEB0E1218.toInt())
            setStroke(dp(1), 0x40FFFFFF)
        })
        if (blur && Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(dp(36))
        window.attributes = window.attributes.apply { x = dp(28) }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        main.postDelayed(refresh, 30_000)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (mode == MODE_PANEL) render(keepFocus = true)
            main.postDelayed(this, 30_000)
        }
    }

    private fun render(keepFocus: Boolean = false) {
        val focusedTag = if (keepFocus) currentFocus?.tag else null
        content.removeAllViews()
        when (mode) {
            MODE_INPUTS -> inputs()
            MODE_SLEEP -> sleep()
            else -> panel()
        }
        val target = focusedTag?.let { content.findViewWithTag<View>(it) } ?: firstFocusable(content)
        target?.requestFocus()
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    // ---- Control centre ----

    private fun panel() {
        heading(tr("Kontrollzentrum"))
        val connected = Network.kind(this) != null
        val mem = Memory.info(this)
        val used = (100 - mem.available * 100 / mem.total.coerceAtLeast(1)).toInt()
        val left = SleepTimer.minutesLeft(this)
        grid(
            pill("wifi", Glyph.Kind.WIFI, tr("WLAN"), if (connected) tr("Verbunden") else tr("Nicht verbunden")) {
                open(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
            },
            pill("bt", Glyph.Kind.BLUETOOTH, tr("Bluetooth"), tr("Einstellungen")) { open(Settings.ACTION_BLUETOOTH_SETTINGS) },
            pill("mem", Glyph.Kind.MEMORY, tr("Arbeitsspeicher"), tr("{0} % belegt", used), progress = used) { freeMemory() },
            pill("sound", Glyph.Kind.SOUND, tr("Ton"), tr("Ausgabe und Lautsprecher")) { open(Settings.ACTION_SOUND_SETTINGS) },
            pill("saver", Glyph.Kind.SPARKLE, tr("Bildschirmschoner"), tr("Jetzt starten")) {
                startActivity(Intent(this, ScreensaverActivity::class.java)); finish()
            },
            pill("off", Glyph.Kind.POWER, tr("Bildschirm aus"), if (left > 0) tr("in {0} Min.", left) else tr("Jetzt oder mit Timer"), accent = 0xFFFF5252.toInt()) {
                mode = MODE_SLEEP; render()
            },
        )
        val squares = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            square("inputs", Glyph.Kind.INPUT, tr("Eingänge")) { mode = MODE_INPUTS; render() },
            square("glas", Glyph.Kind.PANEL, tr("Glas Launcher")) { Actions.run(this, "settings"); finish() },
            square("tv", Glyph.Kind.GEAR, tr("Fernseher")) {
                // The TV settings stay behind the PIN when the settings are protected
                val prefs = Prefs(this)
                if (prefs.protectSettings) Pin.verify(this, prefs, tr("Einstellungen sind gesperrt")) { open(Settings.ACTION_SETTINGS) }
                else open(Settings.ACTION_SETTINGS)
            },
            square("search", Glyph.Kind.SEARCH, tr("Suche")) { search() },
            square("phone", Glyph.Kind.PHONE, tr("Handy")) { PhoneRemote.show(this) },
        ).forEachIndexed { i, v ->
            squares.addView(v, LinearLayout.LayoutParams(0, dp(66), 1f).apply { if (i > 0) marginStart = dp(10) })
        }
        content.addView(squares, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        weatherRow()
    }

    private fun weatherRow() {
        val place = Prefs(this).mainPlace ?: return
        val now = Prefs(this).cachedWeather(place)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            tag = "weather"
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = pillBackground(false)
            setOnFocusChangeListener { v, f -> v.background = pillBackground(f); recolor(v as LinearLayout, f) }
            setOnClickListener { Actions.run(this@PanelActivity, "weather"); finish() }
        }
        fun half(title: String, sub: String, code: Int?, day: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (code != null) addView(WeatherIcon(this@PanelActivity).apply { set(WeatherApi.skyFor(code), day) }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(LinearLayout(this@PanelActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(text(title, 14f, bold = true))
                addView(text(sub, 12f))
            })
        }
        row.addView(half(if (now != null) "${now.temp}° · ${place.name}" else place.name,
            now?.let { WeatherApi.describe(it.code) } ?: "–", now?.code, now?.isDay ?: true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val tomorrow = LinearLayout(this)
        row.addView(tomorrow, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        // Tomorrow comes from the full forecast, fetched in the background
        Thread {
            val f = runCatching { WeatherApi.forecast(this, place).forecast }.getOrNull()
            val day = f?.days?.getOrNull(1) ?: return@Thread
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                tomorrow.addView(half(tr("Morgen {0}° / {1}°", day.max, day.min), WeatherApi.describe(day.code), day.code, true))
                recolor(row, row.hasFocus() || row.isFocused)
            }
        }.start()
    }

    // ---- Screen off, now or later ----

    private fun sleep() {
        heading(tr("Bildschirm aus"))
        val left = SleepTimer.minutesLeft(this)
        val pills = mutableListOf(
            pill("now", Glyph.Kind.POWER, tr("Jetzt"), tr("Fernseher schlafen legen"), accent = 0xFFFF5252.toInt()) { screenOff() },
        )
        for (m in SleepTimer.choices.filter { it > 0 }) {
            pills += pill("t$m", Glyph.Kind.TIMER, tr("In {0} Minuten", m), if (left > 0 && m == SleepTimer.chosen(this)) tr("läuft, noch {0} Min.", left) else null) {
                SleepTimer.set(this, m)
                toast(tr("Der Fernseher schläft in {0} Minuten ein", m))
                finish()
            }
        }
        if (left > 0) pills += pill("toff", Glyph.Kind.TIMER, tr("Timer aus"), tr("noch {0} Min.", left)) {
            SleepTimer.set(this, 0)
            toast(tr("Schlummertimer aus"))
            mode = MODE_PANEL; render()
        }
        grid(*pills.toTypedArray())
        content.addView(square("back", Glyph.Kind.PANEL, tr("Zurück zum Kontrollzentrum")) { mode = MODE_PANEL; render() },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(2) })
    }

    // ---- Inputs ----

    private fun inputs() {
        heading(tr("Eingänge"))
        val list = TvInputs.list(this)
        if (list.isEmpty()) {
            content.addView(text(tr("Dieser Fernseher meldet keine Eingänge."), 16f))
        } else {
            grid(*list.map { input ->
                pill("in:" + input.id, TvInputs.kind(input), input.label, null) {
                    if (TvInputs.open(this, input)) finish() else toast(tr("{0} lässt sich nicht öffnen", input.label))
                }
            }.toTypedArray())
        }
        if (intent.getStringExtra(EXTRA_MODE) != MODE_INPUTS) {
            content.addView(square("back", Glyph.Kind.PANEL, tr("Zurück zum Kontrollzentrum")) { mode = MODE_PANEL; render() },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(2) })
        }
    }

    // ---- Actions ----

    private fun open(vararg actions: String) {
        for (a in actions) {
            try {
                startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                return
            } catch (e: ActivityNotFoundException) {
            } catch (e: SecurityException) {
            }
        }
        toast(tr("Diese Einstellung gibt es auf deinem Fernseher nicht"))
    }

    private fun search() {
        val tries = listOf(Intent(Intent.ACTION_ASSIST), Intent("android.search.action.GLOBAL_SEARCH"), Intent(Intent.ACTION_WEB_SEARCH))
        for (i in tries) {
            if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) { finish(); return }
        }
        toast(tr("Die Suche lässt sich nicht öffnen"))
    }

    private fun freeMemory() {
        toast(tr("Gebe Arbeitsspeicher frei …"))
        Thread {
            val gained = runCatching { Memory.free(this) }.getOrDefault(0L)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                toast(if (gained > 20_000_000) tr("{0} freigegeben", Memory.mb(gained)) else tr("Der Speicher war schon weitgehend frei"))
                render(keepFocus = true)
            }
        }.start()
    }

    private fun screenOff() {
        toast(tr("Einen Moment …"))
        val app = applicationContext
        Thread {
            val r = SleepTimer.sleepNow(app)
            runOnUiThread {
                when (r) {
                    GoogleTv.Result.Done -> finish()
                    GoogleTv.Result.NoDebugging -> toast(tr("Dafür muss das USB-Debugging in den Entwickleroptionen an sein"))
                    GoogleTv.Result.NotAllowed -> toast(tr("Bitte am Fernseher „USB-Debugging zulassen“ mit „Immer zulassen“ bestätigen"))
                    is GoogleTv.Result.Failed -> toast(tr("Das hat nicht geklappt ({0})", r.reason))
                }
            }
        }.start()
    }

    // ---- Building blocks ----

    private fun heading(t: String) {
        content.addView(text(t, 20f, bold = true).apply { setPadding(dp(6), 0, 0, dp(12)) })
    }

    private fun grid(vararg pills: View) {
        for (pair in pills.toList().chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, v ->
                row.addView(v, LinearLayout.LayoutParams(0, dp(60), 1f).apply { if (i > 0) marginStart = dp(10) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginStart = dp(10) })
            content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    private fun pill(
        tag: String, kind: Glyph.Kind, title: String, sub: String?, progress: Int? = null, accent: Int? = null, onClick: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        this.tag = tag
        setPadding(dp(18), 0, dp(14), 0)
        background = pillBackground(false)
        addView(ImageView(this@PanelActivity).apply {
            setImageDrawable(Glyph(kind, accent ?: Color.WHITE))
            this.tag = GlyphTag(kind, accent)
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(LinearLayout(this@PanelActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(text(title, 15f, bold = true))
            if (sub != null) addView(text(sub, 12f))
            if (progress != null) addView(View(this@PanelActivity).apply { background = bar(progress) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(4)).apply { topMargin = dp(6); marginEnd = dp(6) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        setOnFocusChangeListener { v, f ->
            v.background = pillBackground(f)
            recolor(v as LinearLayout, f)
            v.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(150).start()
        }
        setOnClickListener { onClick() }
    }

    private fun square(tag: String, kind: Glyph.Kind, label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        this.tag = tag
        background = pillBackground(false, radius = dp(20f))
        addView(ImageView(this@PanelActivity).apply { setImageDrawable(Glyph(kind)); this.tag = GlyphTag(kind, null) },
            LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(text(label, 11f).apply { setPadding(dp(4), dp(3), dp(4), 0); gravity = Gravity.CENTER })
        setOnFocusChangeListener { v, f ->
            v.background = pillBackground(f, radius = dp(20f))
            recolor(v as LinearLayout, f)
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(150).start()
        }
        setOnClickListener { onClick() }
    }

    private class GlyphTag(val kind: Glyph.Kind, val accent: Int?)

    /** Focused pills turn white with dark writing, as on tvOS. */
    private fun recolor(group: android.view.ViewGroup, focused: Boolean) {
        for (i in 0 until group.childCount) {
            when (val c = group.getChildAt(i)) {
                is TextView -> {
                    c.setTextColor(if (focused) 0xFF15181D.toInt() else Color.WHITE)
                    c.setShadowLayer(if (focused) 0f else dp(3f), 0f, if (focused) 0f else dp(1f), if (focused) 0 else 0x66000000)
                }
                is ImageView -> (c.tag as? GlyphTag)?.let { c.setImageDrawable(Glyph(it.kind, it.accent ?: if (focused) 0xFF15181D.toInt() else Color.WHITE)) }
                is android.view.ViewGroup -> recolor(c, focused)
            }
        }
    }

    private fun pillBackground(focused: Boolean, radius: Float = dp(30f)) = GradientDrawable().apply {
        cornerRadius = radius
        if (focused) {
            setColor(0xF5FFFFFF.toInt())
        } else {
            // Light frosted glass with a bright rim, a little lighter at the top
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(0x40FFFFFF, 0x1FFFFFFF)
            setStroke(dp(1), 0x59FFFFFF)
        }
    }

    private fun bar(percent: Int): LayerDrawable {
        val back = GradientDrawable().apply { cornerRadius = dp(2f); setColor(0x40FFFFFF) }
        val front = ClipDrawable(GradientDrawable().apply { cornerRadius = dp(2f); setColor(Color.WHITE) }, Gravity.START, ClipDrawable.HORIZONTAL)
        front.level = percent.coerceIn(0, 100) * 100
        return LayerDrawable(arrayOf(back, front))
    }

    private fun text(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setShadowLayer(dp(3f), 0f, dp(1f), 0x66000000)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_PANEL = "panel"
        const val MODE_INPUTS = "inputs"
        const val MODE_SLEEP = "sleep"

        fun intent(ctx: Context, mode: String) = Intent(ctx, PanelActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
