package de.glas.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small weather symbol for the status bar: sun or moon, clouds, rain, snow, lightning or fog. */
class WeatherIcon(context: Context) : View(context) {
    private var sky: Sky? = null
    private var day = true
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    fun set(sky: Sky, day: Boolean) {
        this.sky = sky
        this.day = day
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = sky ?: return
        val u = min(width, height).toFloat()
        c.save()
        c.translate((width - u) / 2, (height - u) / 2)
        stroke.strokeWidth = u * .07f
        when (s) {
            Sky.CLEAR -> if (day) sun(c, u, .5f, .5f, .2f) else moon(c, u, .5f, .5f, .3f)
            Sky.PARTLY -> {
                if (day) sun(c, u, .36f, .36f, .15f) else moon(c, u, .38f, .36f, .2f)
                cloud(c, u, .58f, .66f, .74f, Color.WHITE)
            }
            Sky.OVERCAST -> {
                cloud(c, u, .4f, .46f, .6f, 0x99FFFFFF.toInt())
                cloud(c, u, .58f, .66f, .74f, Color.WHITE)
            }
            Sky.FOG -> {
                stroke.color = Color.WHITE
                for ((i, y) in listOf(.34f, .52f, .7f).withIndex()) {
                    val inset = if (i == 1) .14f else .22f
                    c.drawLine(u * inset, u * y, u * (1 - inset), u * y, stroke)
                }
            }
            Sky.RAIN -> {
                cloud(c, u, .5f, .44f, .78f, Color.WHITE)
                stroke.color = 0xFF9FD3FF.toInt()
                for (x in listOf(.34f, .52f, .7f)) c.drawLine(u * x, u * .7f, u * (x - .07f), u * .88f, stroke)
            }
            Sky.SNOW -> {
                cloud(c, u, .5f, .44f, .78f, Color.WHITE)
                fill.color = Color.WHITE
                for ((x, y) in listOf(.32f to .76f, .52f to .86f, .72f to .76f)) c.drawCircle(u * x, u * y, u * .055f, fill)
            }
            Sky.STORM -> {
                cloud(c, u, .5f, .4f, .78f, 0xFFCFD6DE.toInt())
                fill.color = 0xFFFFD54F.toInt()
                path.reset()
                path.moveTo(u * .56f, u * .5f)
                path.lineTo(u * .38f, u * .74f)
                path.lineTo(u * .5f, u * .74f)
                path.lineTo(u * .42f, u * .94f)
                path.lineTo(u * .66f, u * .66f)
                path.lineTo(u * .53f, u * .66f)
                path.close()
                c.drawPath(path, fill)
            }
        }
        c.restore()
    }

    private fun sun(c: Canvas, u: Float, cx: Float, cy: Float, r: Float) {
        fill.color = 0xFFFFD27A.toInt()
        stroke.color = 0xFFFFD27A.toInt()
        c.drawCircle(u * cx, u * cy, u * r, fill)
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val r1 = r * 1.45f
            val r2 = r * 1.95f
            c.drawLine(
                u * (cx + r1 * cos(a).toFloat()), u * (cy + r1 * sin(a).toFloat()),
                u * (cx + r2 * cos(a).toFloat()), u * (cy + r2 * sin(a).toFloat()), stroke,
            )
        }
    }

    private fun moon(c: Canvas, u: Float, cx: Float, cy: Float, r: Float) {
        fill.color = 0xFFE6ECFF.toInt()
        val full = Path().apply { addCircle(u * cx, u * cy, u * r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(u * (cx + r * .55f), u * (cy - r * .35f), u * r * .85f, Path.Direction.CW) }
        full.op(bite, Path.Op.DIFFERENCE)
        c.drawPath(full, fill)
    }

    /** Cloud centred on [cx], [cy] with width [w], all as fractions of the icon. */
    private fun cloud(c: Canvas, u: Float, cx: Float, cy: Float, w: Float, color: Int) {
        fill.color = color
        val h = w * .55f
        val left = cx - w / 2
        val bottom = cy + h / 2
        path.reset()
        path.addRoundRect(RectF(u * left, u * (bottom - h * .5f), u * (left + w), u * bottom), u * h * .25f, u * h * .25f, Path.Direction.CW)
        path.addCircle(u * (left + w * .32f), u * (bottom - h * .5f), u * h * .36f, Path.Direction.CW)
        path.addCircle(u * (left + w * .62f), u * (bottom - h * .58f), u * h * .46f, Path.Direction.CW)
        c.drawPath(path, fill)
    }
}

/** Network symbol for the status bar: Wi-Fi with 0–4 bars, a cable for Ethernet, or crossed out when offline. */
class NetworkIcon(context: Context) : View(context) {
    enum class Kind { WIFI, ETHERNET, OFFLINE }

    private var kind = Kind.OFFLINE
    private var level = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()

    fun set(kind: Kind, level: Int) {
        if (kind == this.kind && level == this.level) return
        this.kind = kind
        this.level = level.coerceIn(0, 4)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val u = min(width, height).toFloat()
        c.save()
        c.translate((width - u) / 2, (height - u) / 2)
        paint.strokeWidth = u * .09f
        when (kind) {
            Kind.ETHERNET -> {
                fill.color = Color.WHITE
                paint.color = Color.WHITE
                // Three boxes joined like a small network
                c.drawRoundRect(RectF(u * .38f, u * .14f, u * .62f, u * .36f), u * .04f, u * .04f, fill)
                c.drawRoundRect(RectF(u * .1f, u * .64f, u * .34f, u * .86f), u * .04f, u * .04f, fill)
                c.drawRoundRect(RectF(u * .66f, u * .64f, u * .9f, u * .86f), u * .04f, u * .04f, fill)
                c.drawLine(u * .5f, u * .36f, u * .5f, u * .5f, paint)
                c.drawLine(u * .22f, u * .5f, u * .78f, u * .5f, paint)
                c.drawLine(u * .22f, u * .5f, u * .22f, u * .64f, paint)
                c.drawLine(u * .78f, u * .5f, u * .78f, u * .64f, paint)
            }
            else -> {
                // Dot plus three arcs; bars above the signal level are dimmed
                val on = Color.WHITE
                val off = 0x55FFFFFF
                val bars = if (kind == Kind.OFFLINE) 0 else level
                fill.color = if (bars >= 1) on else off
                c.drawCircle(u * .5f, u * .8f, u * .07f, fill)
                for (i in 1..3) {
                    val r = u * (.17f + .17f * i)
                    arc.set(u * .5f - r, u * .82f - r, u * .5f + r, u * .82f + r)
                    paint.color = if (bars >= i + 1) on else off
                    c.drawArc(arc, 225f, 90f, false, paint)
                }
                if (kind == Kind.OFFLINE) {
                    paint.color = Color.WHITE
                    c.drawLine(u * .16f, u * .16f, u * .84f, u * .84f, paint)
                }
            }
        }
        c.restore()
    }
}
