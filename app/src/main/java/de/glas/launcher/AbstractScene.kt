package de.glas.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/** Soft colour fields drifting slowly; the palette follows the weather. */
class AbstractScene : Scene {
    private var sky = Sky.PARTLY
    private var day = true
    private var w = 0f
    private var h = 0f
    private var base = Color.BLACK
    private val paints = List(5) { Paint(Paint.ANTI_ALIAS_FLAG) }

    // Per blob: speed and phase on x and y, in radians per second
    private val fx = doubleArrayOf(.031, .023, .041, .019, .027)
    private val fy = doubleArrayOf(.025, .037, .021, .033, .029)
    private val px = doubleArrayOf(0.0, 1.7, 3.4, 5.1, 2.2)
    private val py = doubleArrayOf(.8, 2.9, 4.4, 1.3, 5.7)

    override fun resize(w: Float, h: Float) {
        this.w = w
        this.h = h
        rebuild()
    }

    override fun setWeather(sky: Sky, day: Boolean) {
        this.sky = sky
        this.day = day
        rebuild()
    }

    private fun radius(i: Int) = w * (.34f + .08f * (i % 3))

    private fun rebuild() {
        if (w == 0f) return
        val colors = palette().map { Color.parseColor(it) }.map { if (day) it else dim(it) }
        base = if (day) 0xFF0F141C.toInt() else 0xFF05070B.toInt()
        paints.forEachIndexed { i, p ->
            val c = colors[i % colors.size]
            p.shader = RadialGradient(
                0f, 0f, radius(i),
                intArrayOf(withAlpha(c, 0xC8), withAlpha(c, 0x50), withAlpha(c, 0)),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP,
            )
        }
    }

    private fun palette() = when (sky) {
        Sky.CLEAR -> listOf("#FFB347", "#FF6F61", "#4FC3F7", "#1E88E5", "#FFD54F")
        Sky.PARTLY -> listOf("#90A4AE", "#4FC3F7", "#B39DDB", "#FFE082", "#64B5F6")
        Sky.OVERCAST -> listOf("#78909C", "#546E7A", "#B0BEC5", "#455A64", "#90A4AE")
        Sky.FOG -> listOf("#CFD8DC", "#90A4AE", "#ECEFF1", "#78909C", "#B0BEC5")
        Sky.RAIN -> listOf("#26C6DA", "#3949AB", "#5C6BC0", "#00838F", "#4DD0E1")
        Sky.SNOW -> listOf("#E3F2FD", "#90CAF9", "#B39DDB", "#FFFFFF", "#81D4FA")
        Sky.STORM -> listOf("#7E57C2", "#311B92", "#00B8D4", "#1A237E", "#B388FF")
    }

    private fun dim(c: Int) = Color.rgb(Color.red(c) / 2, Color.green(c) / 2, Color.blue(c) / 2)
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    override fun step(now: Long, dt: Float) {}

    override fun draw(c: Canvas, now: Long, lowRes: Boolean) {
        val t = now / 1000.0
        c.drawColor(base)
        for (i in paints.indices) {
            val x = w * (.5f + .45f * sin(t * fx[i] + px[i]).toFloat())
            val y = h * (.5f + .42f * cos(t * fy[i] + py[i]).toFloat())
            c.save()
            c.translate(x, y)
            c.drawCircle(0f, 0f, radius(i), paints[i])
            c.restore()
        }
    }
}
