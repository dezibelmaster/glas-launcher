package de.glas.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.InputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A photo with a slow pan and zoom, cross-fading when it changes. For the weather
 * style the photo follows the weather and rain, snow or lightning is drawn on top.
 * Without a photo it shows the drawn sky instead.
 */
class PhotoScene(
    context: Context,
    private val withFx: Boolean,
    private val key: (Sky, Boolean) -> String,
    private val loader: (Sky, Boolean) -> Photo?,
) : Scene {
    class Photo(val bitmap: Bitmap, val dim: Boolean)

    private class Layer(val photo: Photo, val small: Bitmap, val start: Long)

    private val main = Handler(Looper.getMainLooper())
    private val fallback = ModernScene()
    private val fx = WeatherFx(withStars = false, withFog = false)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val matrix = Matrix()
    private var current: Layer? = null
    private var previous: Layer? = null
    private var loadedKey: String? = null
    private var loaded = false
    private var token = 0
    private var w = 0f
    private var h = 0f
    private var sky = Sky.PARTLY
    private var day = true

    override fun resize(w: Float, h: Float) {
        this.w = w
        this.h = h
        fallback.resize(w, h)
        fx.configure(sky, day, w, h)
    }

    override fun setWeather(sky: Sky, day: Boolean) {
        this.sky = sky
        this.day = day
        fallback.setWeather(sky, day)
        if (withFx) fx.configure(sky, day, w, h)
        val k = key(sky, day)
        if (k == loadedKey) return
        loadedKey = k
        val mine = ++token
        Thread {
            val photo = loader(sky, day)
            main.post {
                if (mine != token) return@post
                loaded = true
                previous = current
                current = photo?.let { Layer(it, shrink(it.bitmap), SystemClock.uptimeMillis()) }
            }
        }.start()
    }

    /** Halves the photo step by step down to 1/16, which keeps the glass blur smooth. */
    private fun shrink(src: Bitmap): Bitmap {
        var b = src
        while (b.width > src.width / 16 && b.width > 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        return b
    }

    override fun step(now: Long, dt: Float) {
        if (current == null) fallback.step(now, dt)
        if (withFx) fx.step(now, dt)
    }

    override fun draw(c: Canvas, now: Long, lowRes: Boolean) {
        val cur = current
        if (cur == null) {
            // Keep the screen dark while the first photo loads, then fall back to the drawn sky
            if (loaded) fallback.draw(c, now, lowRes) else c.drawColor(Color.BLACK)
            return
        }
        val fade = ((now - cur.start) / 1500f).coerceIn(0f, 1f)
        if (fade >= 1f) previous = null
        previous?.let { drawLayer(c, it, now, lowRes, 255) }
        drawLayer(c, cur, now, lowRes, (fade * 255).toInt())
        if (withFx) fx.drawFront(c, now)
    }

    private fun drawLayer(c: Canvas, layer: Layer, now: Long, lowRes: Boolean, alpha: Int) {
        val full = layer.photo.bitmap
        val bmp = if (lowRes) layer.small else full
        val t = now / 1000.0
        val zoom = 1.08f + .04f * sin(t / 37).toFloat()
        val scale = max(w / full.width, h / full.height) * zoom * (full.width.toFloat() / bmp.width)
        val dx = sin(t / 53).toFloat() * w * .025f
        val dy = cos(t / 61).toFloat() * h * .02f
        matrix.reset()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(scale, scale)
        matrix.postTranslate(w / 2 + dx, h / 2 + dy)
        paint.alpha = alpha
        c.drawBitmap(bmp, matrix, paint)
        if (layer.photo.dim) {
            // Day photo used at night
            dimPaint.color = Color.argb(alpha * 150 / 255, 4, 8, 20)
            c.drawRect(0f, 0f, w, h, dimPaint)
        }
    }
}

object PhotoLoader {
    /** Folder on the TV where own weather photos can replace the built-in ones. */
    val userFolder: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GlasLauncher")

    /** Photo for the weather, e.g. rain_day.jpg; at night a missing night photo falls back to the dimmed day photo. */
    fun weather(ctx: Context, sky: Sky, day: Boolean): PhotoScene.Photo? {
        val base = sky.name.lowercase()
        val names = if (day) listOf("${base}_day") else listOf("${base}_night", "${base}_day")
        for (name in names) {
            val bmp = fromUserFolder(ctx, name) ?: fromAssets(ctx, name) ?: continue
            return PhotoScene.Photo(bmp, dim = !day && name.endsWith("_day"))
        }
        return null
    }

    fun fromUri(ctx: Context, uri: String?): PhotoScene.Photo? {
        if (uri == null) return null
        return decode(ctx) { ctx.contentResolver.openInputStream(Uri.parse(uri)) }?.let { PhotoScene.Photo(it, false) }
    }

    private fun fromUserFolder(ctx: Context, name: String): Bitmap? {
        for (ext in listOf("jpg", "jpeg", "png", "webp")) {
            val f = File(userFolder, "$name.$ext")
            if (runCatching { f.canRead() }.getOrDefault(false)) return decode(ctx) { f.inputStream() }
        }
        return null
    }

    private fun fromAssets(ctx: Context, name: String): Bitmap? {
        val path = "weather/$name.jpg"
        if (runCatching { ctx.assets.open(path).close() }.isFailure) return null
        return decode(ctx) { ctx.assets.open(path) }
    }

    /** Decodes at roughly screen size so large photos don't run out of memory. */
    private fun decode(ctx: Context, open: () -> InputStream?): Bitmap? = try {
        val dm = ctx.resources.displayMetrics
        val maxW = max(dm.widthPixels, dm.heightPixels)
        val maxH = minOf(dm.widthPixels, dm.heightPixels)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
