package de.glas.launcher

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Large clock and weather over the chosen background; drifts every minute against burn-in. */
class ScreensaverView(context: Context) : FrameLayout(context) {
    private val backdrop = Backdrops.create(context, Prefs(context))
    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val clock = Ui.text(context, 128f, Ui.thin)
    private val date = Ui.text(context, 22f, color = Ui.MUTED)
    private val weather = Ui.text(context, 20f)
    private val main = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.GERMANY)
    private val dateFormat = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY)
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = timeFormat.format(now)
            date.text = dateFormat.format(now)
            if (ticks % 6 == 5) drift()
            if (ticks % 90 == 0) refreshWeather()
            ticks++
            main.postDelayed(this, 10_000)
        }
    }

    init {
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(View(context).apply { setBackgroundColor(0x66000000) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Each line spans the box, so centre the text inside it
        for (line in listOf(clock, date, weather)) line.gravity = Gravity.CENTER_HORIZONTAL
        box.addView(clock)
        box.addView(date)
        box.addView(weather.apply { setPadding(0, context.dp(18), 0, 0) })
        addView(box, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun drift() {
        if (width == 0) return
        box.animate()
            .translationX((Random.nextFloat() * 2 - 1) * width * .2f)
            .translationY((Random.nextFloat() * 2 - 1) * height * .18f)
            .setDuration(4000)
            .start()
    }

    private fun refreshWeather() {
        val place = Prefs(context).mainPlace
        WeatherApi.load(context, place) { w ->
            if (w == null) return@load
            weather.text = "${w.temp}° · ${WeatherApi.describe(w.code)} · ${place.name}"
            backdrop.setWeather(WeatherApi.skyFor(w.code), w.isDay)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        main.post(tick)
        backdrop.resume()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacksAndMessages(null)
        backdrop.release()
    }
}

/** System screensaver, also shown while other apps are open. */
class ClockDream : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(ScreensaverView(this))
        freeMemoryIfWanted(this)
    }
}

/** Frees memory of apps resting in the background when the screensaver starts, if switched on. */
fun freeMemoryIfWanted(ctx: android.content.Context) {
    if (!Prefs(ctx).autoFreeMemory) return
    val app = ctx.applicationContext
    Thread { runCatching { Memory.free(app) } }.start()
}

/** The launcher's own screensaver after a few idle minutes; any key closes it. */
class ScreensaverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ScreensaverView(this))
        freeMemoryIfWanted(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) finish()
        return true
    }
}
