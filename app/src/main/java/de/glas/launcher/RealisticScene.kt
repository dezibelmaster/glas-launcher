package de.glas.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Drawn sky with a realistic look: cloud layers made from fractal noise drifting at two
 * speeds, sun or moon, rain streaks and snowflakes at several depths, branching lightning,
 * fog banks and haze on the horizon. Cloud textures are generated in the background
 * whenever the weather changes.
 */
class RealisticScene : Scene {
    private class Layer(val bitmap: Bitmap, val speed: Float, val top: Float, val height: Float, val alpha: Int)
    private class Drop(var x: Float, var y: Float, val z: Float, val phase: Float)

    private val main = Handler(Looper.getMainLooper())
    private var sky = Sky.PARTLY
    private var day = true
    private var w = 0f
    private var h = 0f
    private var k = 1f
    private var layers = emptyList<Layer>()
    private var fog: Layer? = null
    private var token = 0
    private var drops = emptyList<Drop>()
    private var stars = emptyList<Drop>()
    private var flash = 0f
    private var bolt: Path? = null
    private var boltUntil = 0L
    private val skyPaint = Paint()
    private val glowPaint = Paint()
    private val hazePaint = Paint()
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val full = RectF()
    private val src = Rect()
    private val dst = RectF()
    private val flake: Bitmap = softDot(32)
    private var rain = Array(3) { FloatArray(0) }

    override fun resize(w: Float, h: Float) {
        this.w = w
        this.h = h
        k = w / 1920f
        full.set(0f, 0f, w, h)
        rebuild()
    }

    override fun setWeather(sky: Sky, day: Boolean) {
        if (sky == this.sky && day == this.day && layers.isNotEmpty()) return
        this.sky = sky
        this.day = day
        rebuild()
    }

    private fun rebuild() {
        if (w == 0f) return
        skyPaint.shader = LinearGradient(0f, 0f, 0f, h, skyColors(), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        val bright = sky == Sky.CLEAR || sky == Sky.PARTLY
        glowPaint.shader = when {
            bright && day -> RadialGradient(w * .78f, h * .2f, w * .5f,
                intArrayOf(0x99FFF3D6.toInt(), 0x33FFE2A8, 0x00FFE2A8), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
            bright -> RadialGradient(w * .8f, h * .2f, w * .25f,
                intArrayOf(0x55C9D6FF, 0x18C9D6FF, 0x00C9D6FF), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
            else -> null
        }
        stars = if (bright && !day) List(220) { Drop(rnd() * w, rnd() * h * .6f, rnd(), rnd() * 6) } else emptyList()
        val count = when (sky) {
            Sky.RAIN -> 520
            Sky.STORM -> 760
            Sky.SNOW -> 300
            else -> 0
        }
        drops = List(count) { Drop(rnd() * w, rnd() * h, .25f + rnd() * .75f, rnd() * 6) }
        rain = Array(3) { FloatArray(count * 4) }
        val horizon = skyColors()[2]
        hazePaint.shader = LinearGradient(0f, h * .6f, 0f, h,
            horizon and 0x00FFFFFF, (horizon and 0x00FFFFFF) or 0x66000000, Shader.TileMode.CLAMP)
        // Cloud textures take a moment: build them off the main thread, then swap them in
        val mine = ++token
        val s = sky
        val d = day
        Thread {
            val built = buildLayers(s, d)
            val fogLayer = if (s == Sky.FOG) Layer(cloudTexture(768, 224, 3, .4f, 0xFFFFFFFF.toInt(), 0xFFDCE1E6.toInt(), 91, fadeTop = true), 6f, .45f, .5f, 150) else null
            main.post {
                if (mine != token) return@post
                layers = built
                fog = fogLayer
            }
        }.start()
    }

    private fun skyColors(): IntArray {
        val hex = if (day) when (sky) {
            Sky.CLEAR -> listOf("#2E6CC8", "#6FA6E6", "#D2E6F8")
            Sky.PARTLY -> listOf("#3A73C4", "#7EAEE3", "#D6E6F4")
            Sky.OVERCAST -> listOf("#7F8B98", "#A8B2BD", "#CDD3D9")
            Sky.FOG -> listOf("#AEB6BE", "#C9CFD4", "#DFE3E6")
            Sky.RAIN -> listOf("#4A5562", "#6F7B87", "#9AA4AE")
            Sky.SNOW -> listOf("#9DA9B6", "#C3CCD5", "#E4E9EE")
            Sky.STORM -> listOf("#20262F", "#353D48", "#59626E")
        } else when (sky) {
            Sky.CLEAR, Sky.PARTLY -> listOf("#03060E", "#0B1428", "#1E2B47")
            Sky.SNOW -> listOf("#141A24", "#232C3A", "#3C4758")
            else -> listOf("#0B0E14", "#151A22", "#262D37")
        }
        return hex.map { Color.parseColor(it) }.toIntArray()
    }

    /** Two cloud layers; coverage and colour follow the weather. Grey skies get a closed deck down from the top. */
    private fun buildLayers(s: Sky, d: Boolean): List<Layer> {
        val threshold = when (s) {
            Sky.CLEAR -> .72f
            Sky.PARTLY -> .55f
            Sky.FOG -> .5f
            Sky.SNOW -> .4f
            Sky.OVERCAST -> .36f
            Sky.RAIN -> .33f
            Sky.STORM -> .31f
        }
        val (light, dark) = when {
            !d -> 0xFF3C4658.toInt() to 0xFF161B24.toInt()
            s == Sky.STORM -> 0xFF8C939E.toInt() to 0xFF22262E.toInt()
            s == Sky.RAIN -> 0xFFB4BBC4.toInt() to 0xFF4A515B.toInt()
            s == Sky.OVERCAST || s == Sky.SNOW -> 0xFFE8EBEF.toInt() to 0xFF78818D.toInt()
            else -> 0xFFFFFFFF.toInt() to 0xFFA0AAB8.toInt()
        }
        val deck = s == Sky.OVERCAST || s == Sky.RAIN || s == Sky.STORM || s == Sky.SNOW
        return if (deck) listOf(
            Layer(cloudTexture(768, 256, 3, threshold + .05f, light, dark, 17, fadeTop = false), 10f, -.05f, .55f, 200),
            Layer(cloudTexture(768, 288, 2, threshold, light, dark, 53, fadeTop = false), 22f, 0f, .8f, 255),
        ) else listOf(
            Layer(cloudTexture(768, 256, 3, threshold + .05f, light, dark, 17, fadeTop = true), 10f, .02f, .5f, 200),
            Layer(cloudTexture(768, 256, 2, threshold, light, dark, 53, fadeTop = true), 22f, .1f, .5f, 255),
        )
    }

    /**
     * Cloud texture from fractal value noise that wraps left to right, so it can scroll
     * endlessly. Features are stretched sideways like real clouds; edges facing up are lit
     * and thick parts get darker underneath.
     */
    private fun cloudTexture(tw: Int, th: Int, cells: Int, threshold: Float, light: Int, dark: Int, seed: Int, fadeTop: Boolean): Bitmap {
        val px = IntArray(tw * th)
        val lr = Color.red(light); val lg = Color.green(light); val lb = Color.blue(light)
        val dr = Color.red(dark); val dg = Color.green(dark); val db = Color.blue(dark)
        val stretch = 1.7f
        for (y in 0 until th) {
            val v = y.toFloat() / th
            // Fade out towards the bottom of the band, and the top too unless it is a closed deck
            val profile = (if (fadeTop) smooth(0f, .3f, v) else 1f) * (1 - smooth(if (fadeTop) .6f else .7f, 1f, v))
            val sv = v * th / tw * stretch
            val svAbove = sv - 6f / tw * stretch
            for (x in 0 until tw) {
                val u = x.toFloat() / tw
                val d = fbm(u, sv, cells, seed)
                val a = smooth(threshold, threshold + .13f, d) * profile
                if (a <= .003f) continue
                val above = fbm(u, svAbove, cells, seed)
                val depth = smooth(threshold, threshold + .4f, d)
                val lit = (1f - depth * .85f + (d - above) * 5f).coerceIn(0f, 1f)
                val r = (dr + (lr - dr) * lit).toInt()
                val g = (dg + (lg - dg) * lit).toInt()
                val b = (db + (lb - db) * lit).toInt()
                px[y * tw + x] = ((a * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888)
    }

    private fun fbm(u: Float, v: Float, cells: Int, seed: Int): Float {
        var sum = 0f
        var amp = .5f
        var period = cells
        for (o in 0 until 5) {
            sum += amp * valueNoise(u * period, v * period, period, seed + o * 131)
            amp *= .5f
            period *= 2
        }
        return sum / .96875f
    }

    /** Smooth value noise on an integer lattice that repeats every [period] cells horizontally. */
    private fun valueNoise(x: Float, y: Float, period: Int, seed: Int): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        fun at(ix: Int, iy: Int) = hash(Math.floorMod(ix, period), iy, seed)
        val a = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * sx
        val b = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * sx
        return a + (b - a) * sy
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var n = x * 374761393 + y * 668265263 + seed * 2147483647
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0x7FFFFFFF) / 2147483647f
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    override fun step(now: Long, dt: Float) {
        val t = now / 1000.0
        if (sky == Sky.SNOW) {
            for (d in drops) {
                d.y += (.6f + 1.8f * d.z) * k * dt
                d.x += sin(t / 1.4 + d.phase).toFloat() * .5f * d.z * k * dt
                if (d.y > h) { d.y = -10f; d.x = rnd() * w }
            }
        } else {
            for (d in drops) {
                val v = (18f + 22f * d.z) * k
                d.y += v * dt
                d.x -= v * .12f * dt
                if (d.y > h) { d.y = -40f * k; d.x = rnd() * w * 1.15f }
            }
        }
        if (sky == Sky.STORM) {
            if (rnd() < .0035f * dt) {
                flash = 1f
                bolt = makeBolt()
                boltUntil = now + 260
            }
            if (flash > 0f) flash = (flash - .05f * dt).coerceAtLeast(0f)
        }
    }

    /** Jagged lightning path from the cloud base to the horizon, with a few side branches. */
    private fun makeBolt(): Path {
        val p = Path()
        var x = w * (.2f + rnd() * .6f)
        var y = h * .22f
        p.moveTo(x, y)
        while (y < h * .8f) {
            x += (rnd() - .5f) * 60 * k
            y += (20 + rnd() * 40) * k
            p.lineTo(x, y)
            if (rnd() < .12f) {
                var bx = x
                var by = y
                val dir = if (rnd() < .5f) -1 else 1
                for (i in 0 until 4) {
                    bx += dir * (15 + rnd() * 30) * k
                    by += (15 + rnd() * 25) * k
                    p.lineTo(bx, by)
                }
                p.moveTo(x, y)
            }
        }
        return p
    }

    override fun draw(c: Canvas, now: Long, lowRes: Boolean) {
        val t = now / 1000.0
        c.drawRect(full, skyPaint)
        if (glowPaint.shader != null) c.drawRect(full, glowPaint)

        // Stars and moon or sun sit behind the clouds
        paint.style = Paint.Style.FILL
        for (s in stars) {
            val a = (.35 + .45 * sin(t * (1 + s.z) + s.phase)) * 255
            paint.color = Color.argb(a.toInt().coerceIn(0, 255), 255, 255, 255)
            c.drawCircle(s.x, s.y, (.6f + 1.6f * s.z) * k, paint)
        }
        if (sky == Sky.CLEAR || sky == Sky.PARTLY) {
            if (day) {
                val r = w * .032f
                discPaint.shader = RadialGradient(w * .78f, h * .2f, r * 1.6f,
                    intArrayOf(0xFFFFFEF6.toInt(), 0xFFFFF4D2.toInt(), 0x00FFF0C0), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(w * .78f, h * .2f, r * 1.6f, discPaint)
            } else {
                val r = w * .028f
                discPaint.shader = null
                discPaint.color = 0xFFE9EDF7.toInt()
                c.drawCircle(w * .8f, h * .2f, r, discPaint)
                discPaint.color = 0x22404A66
                c.drawCircle(w * .8f - r * .3f, h * .2f - r * .2f, r * .28f, discPaint)
                c.drawCircle(w * .8f + r * .35f, h * .2f + r * .25f, r * .2f, discPaint)
                c.drawCircle(w * .8f + r * .05f, h * .2f + r * .45f, r * .14f, discPaint)
            }
        }

        // Clouds: each layer scrolls at its own speed and repeats seamlessly
        for (layer in layers) drawLayer(c, layer, t)

        c.drawRect(full, hazePaint)
        fog?.let { drawLayer(c, it, t) }

        when (sky) {
            Sky.RAIN, Sky.STORM -> drawRain(c)
            Sky.SNOW -> for (d in drops) {
                val s = (3f + 10f * d.z) * k
                bmpPaint.alpha = (110 + 145 * d.z).toInt()
                dst.set(d.x - s, d.y - s, d.x + s, d.y + s)
                c.drawBitmap(flake, null, dst, bmpPaint)
            }
            else -> {}
        }
        bmpPaint.alpha = 255

        val b = bolt
        if (b != null && now < boltUntil) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0x55B9C8FF
            paint.strokeWidth = 12 * k
            c.drawPath(b, paint)
            paint.color = 0xFFF4F7FF.toInt()
            paint.strokeWidth = 2.5f * k
            c.drawPath(b, paint)
            paint.style = Paint.Style.FILL
        }
        if (flash > 0f) c.drawColor(Color.argb((flash * 120).toInt(), 225, 232, 255))
    }

    private fun drawLayer(c: Canvas, layer: Layer, t: Double) {
        val bmp = layer.bitmap
        val lw = w * 1.2f
        val lh = h * layer.height
        val top = h * layer.top
        val offset = ((t * layer.speed * k) % lw).toFloat()
        bmpPaint.alpha = layer.alpha
        src.set(0, 0, bmp.width, bmp.height)
        var x = -offset
        while (x < w) {
            dst.set(x, top, x + lw, top + lh)
            c.drawBitmap(bmp, src, dst, bmpPaint)
            x += lw
        }
        bmpPaint.alpha = 255
    }

    /** Rain in three depth bands: far drops thin and faint, near ones longer and brighter. */
    private fun drawRain(c: Canvas) {
        val counts = IntArray(3)
        for (d in drops) {
            val band = min(2, (d.z * 3).toInt())
            val len = (28f + 70f * d.z) * k
            val arr = rain[band]
            val i = counts[band]
            arr[i] = d.x
            arr[i + 1] = d.y
            arr[i + 2] = d.x + len * .12f
            arr[i + 3] = d.y - len
            counts[band] = i + 4
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (band in 0..2) {
            paint.strokeWidth = (.9f + band * .6f) * k
            paint.color = Color.argb(45 + band * 35, 210, 225, 240)
            c.drawLines(rain[band], 0, counts[band], paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun softDot(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(size / 2f, size / 2f, size / 2f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        Canvas(bmp).drawCircle(size / 2f, size / 2f, size / 2f, p)
        return bmp
    }

    private fun rnd() = Random.nextFloat()
}
