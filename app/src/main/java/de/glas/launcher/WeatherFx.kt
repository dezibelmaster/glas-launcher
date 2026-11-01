package de.glas.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.sin
import kotlin.random.Random

/** Rain, snow, lightning, fog and stars, drawn over any weather background. */
class WeatherFx(private val withStars: Boolean, private val withFog: Boolean) {
    private class Particle(var x: Float, var y: Float, val v: Float, val r: Float, val phase: Float)

    private var sky = Sky.PARTLY
    private var day = true
    private var w = 0f
    private var h = 0f
    private var k = 1f
    private var particles = emptyList<Particle>()
    private var stars = emptyList<Particle>()
    private var lines = FloatArray(0)
    private var flash = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun configure(sky: Sky, day: Boolean, w: Float, h: Float) {
        this.sky = sky
        this.day = day
        this.w = w
        this.h = h
        if (w == 0f) return
        k = w / 1920f
        val count = when (sky) {
            Sky.RAIN -> 260
            Sky.STORM -> 380
            Sky.SNOW -> 180
            else -> 0
        }
        particles = List(count) {
            if (sky == Sky.SNOW) Particle(rnd() * w, rnd() * h, (.8f + rnd() * 1.6f) * k, (2 + rnd() * 5) * k, rnd() * 6)
            else Particle(rnd() * w, rnd() * h, (12 + rnd() * 12) * k, 0f, 0f)
        }
        lines = FloatArray(count * 4)
        val clearNight = !day && (sky == Sky.CLEAR || sky == Sky.PARTLY)
        stars = if (withStars && clearNight) List(160) {
            Particle(rnd() * w, rnd() * h * .7f, 0f, rnd() * 2.8f * k + .6f, rnd() * 6)
        } else emptyList()
    }

    fun step(now: Long, dt: Float) {
        val t = now / 1000.0
        if (sky == Sky.SNOW) {
            for (p in particles) {
                p.y += p.v * dt
                p.x += sin(t / 1.2 + p.phase).toFloat() * .4f * k * dt
                if (p.y > h) { p.y = -5f; p.x = rnd() * w }
            }
        } else {
            for (p in particles) {
                p.y += p.v * dt
                p.x -= p.v * .15f * dt
                if (p.y > h) { p.y = -20f; p.x = rnd() * w * 1.1f }
            }
        }
        if (sky == Sky.STORM) {
            if (rnd() < .004f * dt) flash = 1f
            if (flash > 0f) flash = (flash - .06f * dt).coerceAtLeast(0f)
        }
    }

    /** Stars, drawn behind clouds. */
    fun drawBack(c: Canvas, now: Long) {
        val t = now / 1000.0
        paint.style = Paint.Style.FILL
        for (s in stars) {
            val a = (.4 + .4 * sin(t / .9 + s.phase)) * 255
            paint.color = Color.argb(a.toInt(), 255, 255, 255)
            c.drawCircle(s.x, s.y, s.r, paint)
        }
    }

    /** Precipitation, fog and lightning, drawn in front. */
    fun drawFront(c: Canvas, now: Long) {
        when (sky) {
            Sky.RAIN, Sky.STORM -> {
                for ((i, p) in particles.withIndex()) {
                    lines[i * 4] = p.x
                    lines[i * 4 + 1] = p.y
                    lines[i * 4 + 2] = p.x + p.v * .3f
                    lines[i * 4 + 3] = p.y - p.v * 2.2f
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.6f * k
                paint.color = Color.argb(115, 220, 235, 255)
                c.drawLines(lines, paint)
                paint.style = Paint.Style.FILL
            }
            Sky.SNOW -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(230, 255, 255, 255)
                for (p in particles) c.drawCircle(p.x, p.y, p.r, paint)
            }
            Sky.FOG -> if (withFog) {
                val t = now / 1000.0
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(50, 255, 255, 255)
                for (i in 0..3) {
                    val y = h * (.35f + .15f * i)
                    val x = sin(t / 8 + i).toFloat() * w * .1f
                    c.drawOval(x - w * .2f, y - h * .06f, x + w * 1.2f, y + h * .06f, paint)
                }
            }
            else -> {}
        }
        if (flash > 0f) c.drawColor(Color.argb((flash * 140).toInt(), 255, 255, 255))
    }

    private fun rnd() = Random.nextFloat()
}
