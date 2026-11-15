package de.glas.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Simple line symbols for the control centre, the inputs and the dock, drawn in code so they
 * look the same on every TV. Drawn on a 24×24 grid and scaled to the bounds.
 */
class Glyph(private val kind: Kind, private val color: Int = Color.WHITE) : Drawable() {
    enum class Kind { INPUT, HDMI, ANTENNA, PANEL, CLOUD, WIFI, BLUETOOTH, MEMORY, TIMER, POWER, SOUND, SEARCH, PHONE, GEAR, SPARKLE, KEYS }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = this@Glyph.color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@Glyph.color }
    private val path = Path()
    private val r = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.exactCenterX() - 12 * s, b.exactCenterY() - 12 * s)
        c.scale(s, s)
        p.strokeWidth = 1.8f
        when (kind) {
            Kind.INPUT -> {
                // Box with an arrow going in
                path.reset(); path.moveTo(9f, 5f); path.lineTo(18f, 5f); path.quadTo(20f, 5f, 20f, 7f); path.lineTo(20f, 17f)
                path.quadTo(20f, 19f, 18f, 19f); path.lineTo(9f, 19f); c.drawPath(path, p)
                c.drawLine(3f, 12f, 14f, 12f, p)
                path.reset(); path.moveTo(10.5f, 8.5f); path.lineTo(14f, 12f); path.lineTo(10.5f, 15.5f); c.drawPath(path, p)
            }
            Kind.HDMI -> {
                path.reset(); path.moveTo(3f, 8f); path.lineTo(21f, 8f); path.lineTo(21f, 12.5f); path.lineTo(18.5f, 16f)
                path.lineTo(5.5f, 16f); path.lineTo(3f, 12.5f); path.close(); c.drawPath(path, p)
                c.drawLine(7f, 12f, 17f, 12f, p)
            }
            Kind.ANTENNA -> {
                c.drawLine(12f, 11f, 12f, 21f, p); c.drawLine(9f, 21f, 15f, 21f, p)
                c.drawCircle(12f, 9.5f, 1.4f, fill)
                r.set(7f, 4.5f, 17f, 14.5f); c.drawArc(r, 200f, 140f, false, p)
                r.set(3.5f, 1f, 20.5f, 18f); c.drawArc(r, 205f, 130f, false, p)
            }
            Kind.PANEL -> {
                r.set(4f, 5f, 20f, 11f); c.drawRoundRect(r, 3f, 3f, p); c.drawCircle(16.5f, 8f, 1.6f, fill)
                r.set(4f, 13f, 20f, 19f); c.drawRoundRect(r, 3f, 3f, fill)
                fill.color = Color.BLACK; c.drawCircle(7.5f, 16f, 1.6f, fill); fill.color = color
            }
            Kind.CLOUD -> {
                path.reset(); path.moveTo(7f, 18f); path.lineTo(17.5f, 18f)
                path.cubicTo(21f, 18f, 21.5f, 12.5f, 17.5f, 12f); path.cubicTo(17f, 7f, 10f, 6f, 9f, 11f)
                path.cubicTo(4.5f, 10.5f, 3.5f, 18f, 7f, 18f); c.drawPath(path, p)
            }
            Kind.WIFI -> {
                c.drawCircle(12f, 18f, 1.5f, fill)
                r.set(8f, 11f, 16f, 19f); c.drawArc(r, 225f, 90f, false, p)
                r.set(4.5f, 7.5f, 19.5f, 22.5f); c.drawArc(r, 225f, 90f, false, p)
                r.set(1f, 4f, 23f, 26f); c.drawArc(r, 225f, 90f, false, p)
            }
            Kind.BLUETOOTH -> {
                path.reset(); path.moveTo(7f, 8f); path.lineTo(16f, 16f); path.lineTo(12f, 20f); path.lineTo(12f, 4f)
                path.lineTo(16f, 8f); path.lineTo(7f, 16f); c.drawPath(path, p)
            }
            Kind.MEMORY -> {
                r.set(6f, 6f, 18f, 18f); c.drawRoundRect(r, 2f, 2f, p)
                r.set(9.5f, 9.5f, 14.5f, 14.5f); c.drawRect(r, p)
                for (x in listOf(9f, 12f, 15f)) { c.drawLine(x, 3f, x, 6f, p); c.drawLine(x, 18f, x, 21f, p) }
            }
            Kind.TIMER -> {
                c.drawCircle(12f, 13f, 7.5f, p); c.drawLine(12f, 13f, 12f, 9f, p); c.drawLine(12f, 13f, 15f, 15f, p)
                c.drawLine(10f, 3f, 14f, 3f, p)
            }
            Kind.POWER -> {
                r.set(5f, 5.5f, 19f, 19.5f); c.drawArc(r, 300f, 300f, false, p); c.drawLine(12f, 3.5f, 12f, 11.5f, p)
            }
            Kind.SOUND -> {
                path.reset(); path.moveTo(4f, 10f); path.lineTo(8f, 10f); path.lineTo(12f, 6f); path.lineTo(12f, 18f)
                path.lineTo(8f, 14f); path.lineTo(4f, 14f); path.close(); c.drawPath(path, p)
                r.set(11f, 8f, 17f, 16f); c.drawArc(r, -50f, 100f, false, p)
                r.set(11f, 4.5f, 21f, 19.5f); c.drawArc(r, -50f, 100f, false, p)
            }
            Kind.SEARCH -> { c.drawCircle(10.5f, 10.5f, 6f, p); c.drawLine(15f, 15f, 20f, 20f, p) }
            Kind.PHONE -> {
                r.set(7f, 3f, 17f, 21f); c.drawRoundRect(r, 2.5f, 2.5f, p); c.drawCircle(12f, 9f, 2.2f, p)
                c.drawCircle(10f, 14f, .9f, fill); c.drawCircle(14f, 14f, .9f, fill); c.drawCircle(12f, 17f, .9f, fill)
            }
            Kind.GEAR -> {
                c.drawCircle(12f, 12f, 3f, p)
                c.drawCircle(12f, 12f, 7f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(12f + 7f * Math.cos(a).toFloat(), 12f + 7f * Math.sin(a).toFloat(),
                        12f + 9.5f * Math.cos(a).toFloat(), 12f + 9.5f * Math.sin(a).toFloat(), p)
                }
            }
            Kind.SPARKLE -> {
                path.reset(); path.moveTo(10f, 3f); path.quadTo(11f, 10f, 17f, 11f); path.quadTo(11f, 12f, 10f, 19f)
                path.quadTo(9f, 12f, 3f, 11f); path.quadTo(9f, 10f, 10f, 3f); c.drawPath(path, fill)
                path.reset(); path.moveTo(18f, 14f); path.quadTo(18.5f, 17.5f, 21.5f, 18f); path.quadTo(18.5f, 18.5f, 18f, 21.5f)
                path.quadTo(17.5f, 18.5f, 14.5f, 18f); path.quadTo(17.5f, 17.5f, 18f, 14f); c.drawPath(path, fill)
            }
            Kind.KEYS -> {
                r.set(3f, 7f, 21f, 17f); c.drawRoundRect(r, 2.5f, 2.5f, p)
                for (x in listOf(7f, 10.5f, 14f, 17f)) c.drawCircle(x, 10.5f, .9f, fill)
                c.drawLine(8f, 14f, 16f, 14f, p)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf; fill.colorFilter = cf }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
