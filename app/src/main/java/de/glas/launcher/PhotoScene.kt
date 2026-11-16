package de.glas.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
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
    /** [mask] marks the landscape in front of the sky (white), for the clock that disappears behind it. */
    class Photo(val bitmap: Bitmap, val dim: Boolean, val mask: Bitmap? = null, val autoMask: Boolean = false)

    /** [front] is the photo with only the landscape kept; [horizon] where it begins, as a fraction of the height. */
    internal class Layer(val photo: Photo, val small: Bitmap, val start: Long, val front: Bitmap?, val horizon: Float?, val skyLight: Float?)

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
            val ready = photo?.let { prepare(it) }
            main.post {
                if (mine != token) return@post
                loaded = true
                previous = current
                current = ready
            }
        }.start()
    }

    /** Everything needed to show [photo]: the landscape cut out for the clock, where it begins, how bright the sky is. */
    internal fun prepare(photo: Photo, start: Long = SystemClock.uptimeMillis()): Layer {
        // Photos without a prepared mask get one found automatically: where the clock was put by
        // hand, or else when the skyline is clear
        val band = focus?.let { band(photo.bitmap, it) }
        val found = if (photo.mask == null && photo.autoMask) {
            band?.let { SkyDetector.analyse(photo.bitmap, it).takeIf { r -> r.mask != null } } ?: SkyDetector.analyse(photo.bitmap)
        } else null
        val mask = photo.mask ?: found?.mask
        val front = mask?.let { runCatching { front(photo.bitmap, it) }.getOrNull() }
        // Without a usable mask the horizon still tells where the sky is, so the clock can stand there
        val horizon = mask?.let { horizon(it) } ?: found?.horizon
        val light = horizon?.let { skyLight(photo.bitmap, it) }
        return Layer(photo, shrink(photo.bitmap), start, front, horizon, light)
    }

    /** The clock's digits on screen (fractions of the height) when it was placed by hand, or null. */
    @Volatile private var focus: ClosedFloatingPointRange<Float>? = null

    /** Looks again for the landscape where the clock now stands, so it can disappear behind it there. */
    fun focus(digits: ClosedFloatingPointRange<Float>?) {
        if (digits == focus) return
        focus = digits
        val cur = current ?: return
        if (cur.photo.mask != null || !cur.photo.autoMask) return
        val mine = token
        Thread {
            val again = prepare(cur.photo, cur.start)
            main.post { if (mine == token && current === cur) current = again }
        }.start()
    }

    /** The screen range [digits] as rows of the photo (the slow zoom is ignored), with some room below. */
    private fun band(bmp: Bitmap, digits: ClosedFloatingPointRange<Float>): ClosedFloatingPointRange<Float>? {
        if (w == 0f || h == 0f) return null
        val scale = max(w / bmp.width, h / bmp.height) * 1.08f
        fun row(f: Float) = ((f * h - h / 2) / scale + bmp.height / 2f) / bmp.height
        return (row(digits.start) - .02f).coerceIn(0f, 1f)..(row(digits.endInclusive) + .08f).coerceIn(0f, 1f)
    }

    /** Halves the photo step by step down to 1/16, which keeps the glass blur smooth. */
    private fun shrink(src: Bitmap): Bitmap {
        var b = src
        while (b.width > src.width / 16 && b.width > 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        return b
    }

    /** The photo with the sky cut away, using the mask's brightness as transparency. */
    private fun front(photo: Bitmap, mask: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(photo, 0f, 0f, null)
        val keep = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f))
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        c.drawBitmap(mask, null, Rect(0, 0, photo.width, photo.height), keep)
        return out
    }

    /** Where the landscape begins in the middle half of the picture (a little above the median). */
    private fun horizon(mask: Bitmap): Float? {
        val tops = ArrayList<Int>()
        var x = mask.width / 4
        while (x < mask.width * 3 / 4) {
            for (y in 0 until mask.height) {
                if (Color.red(mask.getPixel(x, y)) > 128) { tops += y; break }
            }
            x += 4
        }
        if (tops.isEmpty()) return null
        tops.sort()
        return tops[tops.size / 4] / mask.height.toFloat()
    }

    /** How bright the sky is just above the horizon in the middle, 0 (black) to 1 (white). */
    private fun skyLight(photo: Bitmap, horizon: Float): Float {
        var sum = 0f
        var n = 0
        val y0 = (photo.height * (horizon - .25f)).toInt().coerceAtLeast(0)
        val y1 = (photo.height * (horizon - .02f)).toInt().coerceAtLeast(y0 + 1)
        var y = y0
        while (y < y1) {
            var x = photo.width * 3 / 10
            while (x < photo.width * 7 / 10) {
                val p = photo.getPixel(x, y)
                sum += (.2126f * Color.red(p) + .7152f * Color.green(p) + .0722f * Color.blue(p)) / 255f
                n++
                x += 16
            }
            y += 16
        }
        return if (n == 0) .5f else sum / n
    }

    /** Brightness of the sky behind the clock for the current photo, or null if unknown. */
    fun skyLight(): Float? = current?.let { cur -> cur.skyLight?.let { if (cur.photo.dim) it * .41f else it } }

    /** Whether the landscape of the current photo can be drawn in front of the clock. */
    fun hasFront() = current?.front != null

    /** Height on screen where the landscape of the current photo begins, or null if unknown. */
    fun horizonY(): Float? {
        val cur = current ?: return null
        val hz = cur.horizon ?: return null
        if (w == 0f) return null
        val full = cur.photo.bitmap
        val scale = max(w / full.width, h / full.height) * 1.08f
        return h / 2 + (hz * full.height - full.height / 2f) * scale
    }

    /** Draws only the landscape in front of the sky, exactly where the full photo lies. */
    fun drawForeground(c: Canvas, now: Long) {
        val cur = current ?: return
        if (cur.front == null) return
        val fade = ((now - cur.start) / 1500f).coerceIn(0f, 1f)
        previous?.takeIf { it.front != null && fade < 1f }?.let { drawFront(c, it, now, 255) }
        drawFront(c, cur, now, (fade * 255).toInt())
    }

    private val frontPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // The same darkening as the night overlay over day photos, as a colour filter
    private val nightFilter = LightingColorFilter(0x696969, 0x020308)

    private fun drawFront(c: Canvas, layer: Layer, now: Long, alpha: Int) {
        val front = layer.front ?: return
        place(layer.photo.bitmap, front, now)
        frontPaint.alpha = alpha
        frontPaint.colorFilter = if (layer.photo.dim) nightFilter else null
        c.drawBitmap(front, matrix, frontPaint)
    }

    /** Slow pan and zoom of [bmp], a version of [full] at any size. */
    private fun place(full: Bitmap, bmp: Bitmap, now: Long) {
        val t = now / 1000.0
        val zoom = 1.08f + .04f * sin(t / 37).toFloat()
        val scale = max(w / full.width, h / full.height) * zoom * (full.width.toFloat() / bmp.width)
        val dx = sin(t / 53).toFloat() * w * .025f
        val dy = cos(t / 61).toFloat() * h * .02f
        matrix.reset()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(scale, scale)
        matrix.postTranslate(w / 2 + dx, h / 2 + dy)
    }

    override fun step(now: Long, dt: Float) {
        if (current == null) fallback.step(now, dt)
        if (withFx) fx.step(now, dt)
    }

    override fun draw(c: Canvas, now: Long, lowRes: Boolean) {
        val cur = current
        if (cur == null) {
            // Keep the screen dark while the first photo loads, then fall back to the drawn sky
            if (loaded) fallback.draw(c, now, lowRes)
            else Snapshot.bitmap?.let { c.drawBitmap(it, null, android.graphics.RectF(0f, 0f, w, h), paint) } ?: c.drawColor(Color.BLACK)
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
        place(full, bmp, now)
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
            // Own photos have no mask; the built-in ones may come with one
            fromUserFolder(ctx, name)?.let { return PhotoScene.Photo(it, dim = !day && name.endsWith("_day"), autoMask = true) }
            val bmp = fromAssets(ctx, name) ?: continue
            val mask = runCatching { ctx.assets.open("weather/mask/$name.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
            return PhotoScene.Photo(bmp, dim = !day && name.endsWith("_day"), mask = mask)
        }
        return null
    }

    fun fromUri(ctx: Context, uri: String?): PhotoScene.Photo? {
        if (uri == null) return null
        return decode(ctx) { ctx.contentResolver.openInputStream(Uri.parse(uri)) }?.let { PhotoScene.Photo(it, false, autoMask = true) }
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
    internal fun decode(ctx: Context, open: () -> InputStream?): Bitmap? = try {
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
