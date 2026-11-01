package de.glas.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.random.Random

/** Drawn sky: colour gradient, sun or moon, drifting clouds and weather effects. */
class ModernScene : Scene {
    private class Cloud(var x: Float, val y: Float, val r: Float, val v: Float)

    private var sky = Sky.PARTLY
    private var day = true
    private var w = 0f
    private var h = 0f
    private var clouds = emptyList<Cloud>()
    private val fx = WeatherFx(withStars = true, withFog = true)
    private val bgPaint = Paint()
    private val glowPaint = Paint()
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudPath = Path()
    private val full = RectF()

    override fun resize(w: Float, h: Float) {
        this.w = w
        this.h = h
        full.set(0f, 0f, w, h)
        rebuild()
    }

    override fun setWeather(sky: Sky, day: Boolean) {
        if (sky == this.sky && day == this.day && clouds.isNotEmpty()) return
        this.sky = sky
        this.day = day
        rebuild()
    }

    private fun rebuild() {
        if (w == 0f) return
        val k = w / 1920f
        val (top, bottom) = gradient()
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)

        val bright = sky == Sky.CLEAR || sky == Sky.PARTLY
        glowPaint.shader = when {
            bright && day -> RadialGradient(
                w * .78f, h * .28f, w * .35f,
                intArrayOf(0xF2FFF4D6.toInt(), 0x99FFE6AA.toInt(), 0x00FFE6AA),
                floatArrayOf(0f, .12f, 1f), Shader.TileMode.CLAMP,
            )
            bright -> RadialGradient(
                w * .8f, h * .25f, w * .18f,
                intArrayOf(0xE6E6ECFF.toInt(), 0x59C8D4FF, 0x00C8D4FF),
                floatArrayOf(0f, .1f, 1f), Shader.TileMode.CLAMP,
            )
            else -> null
        }

        val count = when (sky) {
            Sky.CLEAR, Sky.FOG -> 0
            Sky.PARTLY -> 6
            Sky.OVERCAST -> 14
            Sky.RAIN -> 10
            Sky.SNOW -> 8
            Sky.STORM -> 12
        }
        clouds = List(count) {
            Cloud(Random.nextFloat() * w, Random.nextFloat() * h * .45f, (120 + Random.nextFloat() * 240) * k, (.1f + Random.nextFloat() * .3f) * k)
        }
        val alpha = when {
            !day -> .22f
            sky == Sky.RAIN || sky == Sky.STORM || sky == Sky.OVERCAST -> .5f
            else -> .75f
        }
        cloudPaint.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        fx.configure(sky, day, w, h)
    }

    private fun gradient(): Pair<Int, Int> {
        val hex = if (day) when (sky) {
            Sky.CLEAR -> "#3A8DE0" to "#9FD3FF"
            Sky.PARTLY -> "#5F7C99" to "#B9C9D8"
            Sky.OVERCAST -> "#56677A" to "#A3B1BF"
            Sky.FOG -> "#8C969F" to "#C9CED3"
            Sky.RAIN -> "#2F3D4C" to "#6D7F90"
            Sky.SNOW -> "#7D8EA3" to "#D9E2EC"
            Sky.STORM -> "#161C26" to "#3B4656"
        } else when (sky) {
            Sky.CLEAR -> "#060A1A" to "#1C2747"
            Sky.PARTLY -> "#0B1224" to "#26324F"
            Sky.OVERCAST -> "#141A24" to "#2D3644"
            Sky.FOG -> "#1A1F26" to "#3A414A"
            Sky.RAIN -> "#0D131B" to "#25303C"
            Sky.SNOW -> "#1E2633" to "#4A5566"
            Sky.STORM -> "#07090D" to "#1B212B"
        }
        return Color.parseColor(hex.first) to Color.parseColor(hex.second)
    }

    override fun step(now: Long, dt: Float) {
        for (c in clouds) {
            c.x += c.v * dt
            if (c.x - c.r * 2 > w) c.x = -c.r * 2
        }
        fx.step(now, dt)
    }

    override fun draw(c: Canvas, now: Long, lowRes: Boolean) {
        c.drawRect(full, bgPaint)
        if (glowPaint.shader != null) c.drawRect(full, glowPaint)
        fx.drawBack(c, now)
        for (cl in clouds) {
            // One path per cloud, so overlapping puffs don't stack their alpha
            cloudPath.reset()
            for ((dx, dy, s) in PUFFS) cloudPath.addCircle(cl.x + dx * cl.r, cl.y + dy * cl.r, cl.r * s, Path.Direction.CW)
            c.drawPath(cloudPath, cloudPaint)
        }
        fx.drawFront(c, now)
    }

    private companion object {
        val PUFFS = listOf(
            Triple(0f, 0f, 1f), Triple(.9f, .15f, .75f), Triple(-.9f, .2f, .7f),
            Triple(.4f, -.35f, .7f), Triple(-.35f, -.3f, .6f),
        )
    }
}
