package de.glas.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.util.Calendar

/**
 * Painted landscapes made for the large clock: each has a mask of its front hills, so the
 * clock stands behind them. They follow the time of day; rain and snow are drawn on top.
 */
object Landscapes {
    enum class Mood { MORNING, DAY, EVENING, NIGHT }
    class Scene(val id: String, val title: () -> String, val mood: Mood)

    val all = listOf(
        Scene("alpen_morgen", { tr("Alpenglühen") }, Mood.MORNING),
        Scene("bergsee", { tr("Bergsee") }, Mood.DAY),
        Scene("winter", { tr("Winterberge") }, Mood.DAY),
        Scene("nebelwald", { tr("Nebelwald") }, Mood.DAY),
        Scene("fjord", { tr("Fjord in der Dämmerung") }, Mood.EVENING),
        Scene("sonnenuntergang", { tr("Sonnenuntergang") }, Mood.EVENING),
        Scene("duenen", { tr("Dünen am Abend") }, Mood.EVENING),
        Scene("sternennacht", { tr("Sternennacht") }, Mood.NIGHT),
    )

    fun moodNow(): Mood = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..9 -> Mood.MORNING
        in 10..16 -> Mood.DAY
        in 17..20 -> Mood.EVENING
        else -> Mood.NIGHT
    }

    fun load(ctx: Context, scene: Scene): PhotoScene.Photo? {
        val bmp = runCatching { ctx.assets.open("landscapes/${scene.id}.jpg").use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
        val mask = runCatching { ctx.assets.open("landscapes/${scene.id}_mask.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        // A day landscape chosen by hand at night is darkened, like the weather photos
        val dim = scene.mood != Mood.NIGHT && moodNow() == Mood.NIGHT
        return PhotoScene.Photo(bmp, dim, mask)
    }
}

/** Background with the painted landscapes, browsable and switching with the time of day. */
class LandscapeBackdrop private constructor(context: Context, private val state: State) :
    SceneBackdrop(context, PhotoScene(context, withFx = true, key = { _, _ -> state.key() }) { _, _ -> Landscapes.load(context, state.scene) }) {

    constructor(context: Context) : this(context, State(Prefs(context)))

    private class State(val prefs: Prefs) {
        var index = Landscapes.all.indexOfFirst { it.id == prefs.landscape }.takeIf { it >= 0 } ?: pick(-1)
        var pinned = prefs.landscape != null
        val scene get() = Landscapes.all[index]
        fun key() = scene.id + "/" + Landscapes.moodNow()

        /** A landscape for this time of day (a favourite, if only those should change), not [after] if possible. */
        fun pick(after: Int): Int {
            val mood = Landscapes.moodNow()
            val favs = if (prefs.favoritesOnly) prefs.favorites else emptySet()
            val all = Landscapes.all.indices.toList()
            val fitting = all.filter { Landscapes.all[it].mood == mood }
            val pool = fitting.filter { "landscape:" + Landscapes.all[it].id in favs }.ifEmpty {
                all.filter { "landscape:" + Landscapes.all[it].id in favs }.ifEmpty { fitting }
            }
            return pool.filter { it != after }.randomOrNull() ?: pool.firstOrNull() ?: 0
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var sky = Sky.PARTLY
    private var day = true

    /** Every quarter of an hour another landscape for the time of day, unless one was chosen. */
    private val change = object : Runnable {
        override fun run() {
            if (!state.pinned) {
                val next = state.pick(state.index)
                if (next != state.index || state.key() != shownKey) show(next)
            }
            main.postDelayed(this, 15 * 60_000L)
        }
    }
    private var shownKey = ""

    init {
        main.postDelayed(change, 15 * 60_000L)
    }

    override fun setWeather(sky: Sky, day: Boolean) {
        this.sky = sky
        this.day = day
        shownKey = state.key()
        super.setWeather(sky, day)
    }

    private fun show(i: Int) {
        state.index = i
        shownKey = state.key()
        super.setWeather(sky, day)
        onChange?.invoke()
    }

    override val titles get() = Landscapes.all.map { it.title() }
    override val current get() = state.index
    override val pinned get() = state.pinned
    override fun uriAt(index: Int) = Landscapes.all.getOrNull(index)?.let { "landscape:" + it.id }

    override fun select(index: Int, pin: Boolean) {
        if (index !in Landscapes.all.indices) return
        state.pinned = pin
        if (index != state.index) show(index) else onChange?.invoke()
    }

    override fun unpin() {
        state.pinned = false
        onChange?.invoke()
    }

    override fun next() = show(step(state.index, Landscapes.all.size, forward = true))
    override fun previous() = show(step(state.index, Landscapes.all.size, forward = false))

    override fun release() {
        main.removeCallbacks(change)
        super.release()
    }
}
