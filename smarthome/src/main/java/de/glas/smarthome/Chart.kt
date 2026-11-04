package de.glas.smarthome

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Temperature course as a soft area chart. Touching it shows the value at that time. */
class Chart(ctx: Context) : View(ctx) {
    var stats: Stats? = null
        set(v) { field = v; touch = -1; invalidate() }
    var color = C.ORANGE
        set(v) { field = v; invalidate() }

    private var touch = -1
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.FAINT; typeface = Type.regular }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E2638.toInt() }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT; typeface = Type.medium; textAlign = Paint.Align.CENTER }
    private val hour = SimpleDateFormat("HH:mm", Locale.GERMANY)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(190))

    private val left get() = dp(30f)
    private val top get() = dp(30f)
    private val bottomPad get() = dp(24f)

    override fun onDraw(c: Canvas) {
        val s = stats ?: return
        val values = s.values
        val known = values.filterNotNull()
        if (known.size < 2) return
        val w = width - left - dp(6f)
        val h = height - top - bottomPad
        // Whole degrees with a little room above and below
        val lo = floor(known.min() - .5)
        val hi = ceil(known.max() + .5).coerceAtLeast(lo + 2)
        fun x(i: Int) = left + w * i / (values.size - 1).coerceAtLeast(1)
        fun y(v: Double) = top + h * (1 - ((v - lo) / (hi - lo))).toFloat()

        text.textSize = dp(10.5f)
        grid.strokeWidth = dp(1f)
        val steps = 4
        for (k in 0..steps) {
            val v = lo + (hi - lo) * k / steps
            val yy = y(v)
            c.drawLine(left, yy, left + w, yy, grid)
            text.textAlign = Paint.Align.LEFT
            c.drawText(String.format(Locale.GERMANY, "%.0f°", v), 0f, yy + dp(3.5f), text)
        }
        // Time labels every six hours
        text.textAlign = Paint.Align.CENTER
        val span = (values.size - 1) * s.stepSeconds.toLong()
        val labels = if (span > 2 * 86400) 7 else 4
        for (k in 0..labels) {
            val i = ((values.size - 1) * k / labels.toFloat()).roundToInt()
            val t = s.timeOf(i)
            val label = if (span > 2 * 86400) SimpleDateFormat("EE", Locale.GERMANY).format(Date(t * 1000)) else hour.format(Date(t * 1000))
            c.drawText(label, x(i).coerceIn(left + dp(14f), left + w - dp(14f)), height - dp(4f), text)
        }

        // Line and area, broken where values are missing
        val path = Path()
        val fill = Path()
        var open = false
        var startX = 0f
        var lastX = 0f
        fun closeFill() {
            if (!open) return
            fill.lineTo(lastX, top + h)
            fill.lineTo(startX, top + h)
            fill.close()
            open = false
        }
        for ((i, v) in values.withIndex()) {
            if (v == null) { closeFill(); continue }
            val px = x(i); val py = y(v)
            if (!open) {
                path.moveTo(px, py); fill.moveTo(px, py); startX = px; open = true
            } else {
                path.lineTo(px, py); fill.lineTo(px, py)
            }
            lastX = px
        }
        closeFill()
        area.shader = LinearGradient(0f, top, 0f, top + h, withAlpha(color, 0x66), withAlpha(color, 0x00), Shader.TileMode.CLAMP)
        c.drawPath(fill, area)
        line.color = color
        line.strokeWidth = dp(2.2f)
        c.drawPath(path, line)

        // Value under the finger, or the newest one
        val i = if (touch >= 0) touch else values.indexOfLast { it != null }
        val v = values.getOrNull(i) ?: return
        val px = x(i); val py = y(v)
        if (touch >= 0) c.drawLine(px, top, px, top + h, grid.apply { color = 0x33FFFFFF })
        grid.color = 0x14FFFFFF
        dot.color = color
        c.drawCircle(px, py, dp(5f), dot)
        dot.color = C.TEXT
        c.drawCircle(px, py, dp(2.2f), dot)
        bubbleText.textSize = dp(12f)
        val label = Fmt.temp(v) + "  " + hour.format(Date(s.timeOf(i) * 1000))
        val bw = bubbleText.measureText(label) + dp(16f)
        val bx = (px - bw / 2).coerceIn(left, width - bw)
        c.drawRoundRect(bx, dp(2f), bx + bw, dp(24f), dp(10f), dp(10f), bubble)
        c.drawText(label, bx + bw / 2, dp(17.5f), bubbleText)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = stats ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val w = width - left - dp(6f)
                val i = (((e.x - left) / w) * (s.values.size - 1)).roundToInt().coerceIn(0, s.values.size - 1)
                // Snap to the nearest measured value
                touch = (0 until s.values.size).filter { s.values[it] != null }.minByOrNull { kotlin.math.abs(it - i) } ?: -1
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touch = -1
                invalidate()
            }
        }
        return true
    }
}
