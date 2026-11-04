package de.glas.smarthome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Round thermostat dial: drag the knob along the arc. Positions are "Aus", 8 to 28 °C in half degrees, and "An".
 * [onChange] runs while dragging, [onCommit] once the finger lifts.
 */
class Dial(ctx: Context) : View(ctx) {
    /** Raw FRITZ!Box value, see [Hkr]. */
    var raw = 40
        private set
    var ist: Double? = null
        set(v) { field = v; invalidate() }
    var onChange: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null
    var dragging = false
        private set

    private val steps = 1 + (Hkr.MAX - Hkr.MIN + 1) + 1 // off, 8..28, on
    private val start = 135f
    private val sweep = 270f

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x17FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = 0x2EFFFFFF }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT; typeface = Type.light; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.MUTED; typeface = Type.regular; textAlign = Paint.Align.CENTER }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.MUTED; typeface = Type.light; textAlign = Paint.Align.LEFT }
    private val oval = RectF()
    private var shaderSize = 0

    fun set(rawValue: Int) {
        if (dragging) return
        raw = rawValue
        invalidate()
    }

    private fun index(r: Int) = when (r) {
        Hkr.OFF -> 0
        Hkr.ON -> steps - 1
        in Hkr.MIN..Hkr.MAX -> r - Hkr.MIN + 1
        else -> 0
    }

    private fun rawAt(i: Int) = when (i) {
        0 -> Hkr.OFF
        steps - 1 -> Hkr.ON
        else -> Hkr.MIN + i - 1
    }

    private fun angleOf(i: Int) = start + sweep * i / (steps - 1)

    override fun onMeasure(w: Int, h: Int) {
        val size = minOf(MeasureSpec.getSize(w), dp(300))
        setMeasuredDimension(size, (size * .92f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val stroke = dp(16f)
        val cx = width / 2f
        val r = width / 2f - dp(22f)
        val cy = r + dp(22f)
        oval.set(cx - r, cy - r, cx + r, cy + r)

        if (shaderSize != width) {
            shaderSize = width
            // Colour follows the temperature; the gradient starts where the arc starts
            // Colour follows the temperature. The gradient starts a little before the arc, so the round cap
            // at the start stays blue instead of picking up the red from the end of the circle.
            val lead = 12f
            val o = lead / 360f
            val colors = intArrayOf(C.temp(8.0), C.temp(8.0), C.temp(14.0), C.temp(18.0), C.temp(21.0), C.temp(24.0), C.RED, C.RED, C.temp(8.0))
            // Stop positions: step / 42 of a 270° arc, shifted by the lead
            val pos = floatArrayOf(0f, o + .018f, o + .232f, o + .375f, o + .482f, o + .589f, o + .732f, o + .82f, 1f)
            arc.shader = SweepGradient(cx, cy, colors, pos).apply {
                setLocalMatrix(Matrix().apply { setRotate(start - lead, cx, cy) })
            }
            glow.shader = arc.shader
        }
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        glow.strokeWidth = stroke * 2.2f
        glow.alpha = 0x30
        tick.strokeWidth = dp(1.5f)

        // Fine ticks outside the arc, longer every full 2 °C
        for (i in 1 until steps - 1 step 1) {
            if ((i - 1) % 2 != 0) continue
            val a = Math.toRadians(angleOf(i).toDouble())
            val long = (i - 1) % 8 == 0
            val r1 = r + stroke / 2 + dp(5f)
            val r2 = r1 + if (long) dp(7f) else dp(3.5f)
            tick.color = if (long) 0x47FFFFFF else 0x24FFFFFF
            c.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), tick)
        }

        c.drawArc(oval, start, sweep, false, track)
        val i = index(raw)
        val a = angleOf(i)
        if (i > 0) {
            c.drawArc(oval, start, a - start, false, glow)
            c.drawArc(oval, start, a - start, false, arc)
        }

        // Measured temperature as a small dot on the arc
        ist?.let { t ->
            val ii = ((t * 2).roundToInt() - Hkr.MIN + 1).coerceIn(1, steps - 2)
            val aa = Math.toRadians(angleOf(ii).toDouble())
            dot.color = 0xCCFFFFFF.toInt()
            c.drawCircle(cx + r * cos(aa).toFloat(), cy + r * sin(aa).toFloat(), dp(3f), dot)
        }

        val ar = Math.toRadians(a.toDouble())
        val kx = cx + r * cos(ar).toFloat()
        val ky = cy + r * sin(ar).toFloat()
        knob.setShadowLayer(dp(8f), 0f, dp(2f), 0x80000000.toInt())
        c.drawCircle(kx, ky, stroke * .78f, knob)
        knob.clearShadowLayer()
        dot.color = C.raw(raw)
        c.drawCircle(kx, ky, stroke * .34f, dot)

        // Centre: label, value, measured temperature
        small.textSize = dp(13f)
        c.drawText(if (raw == Hkr.OFF) "Frostschutz" else "Soll", cx, cy - r * .42f, small)
        val text = when (raw) {
            Hkr.OFF -> "Aus"
            Hkr.ON -> "An"
            else -> Fmt.temp(raw / 2.0, false).removeSuffix("°")
        }
        big.textSize = if (raw == Hkr.OFF || raw == Hkr.ON) dp(60f) else dp(68f)
        val base = cy + big.textSize * .34f
        c.drawText(text, cx, base, big)
        if (raw in Hkr.MIN..Hkr.MAX) {
            unit.textSize = dp(24f)
            c.drawText("°C", cx + big.measureText(text) / 2 + dp(3f), base - big.textSize * .42f, unit)
        }
        ist?.let {
            small.textSize = dp(14f)
            c.drawText("Gemessen " + Fmt.temp(it), cx, base + dp(34f), small)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val r = width / 2f - dp(22f)
        val cy = r + dp(22f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the ring reacts, so the middle stays free for scrolling the sheet
                val d = hypot(e.x - cx, e.y - cy)
                if (d < r - dp(48f) || d > r + dp(40f)) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                move(e.x - cx, e.y - cy)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) move(e.x - cx, e.y - cy)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onCommit?.invoke(raw)
            }
        }
        return true
    }

    private fun move(dx: Float, dy: Float) {
        var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (deg < 0) deg += 360f
        var rel = deg - start
        if (rel < 0) rel += 360f
        // The gap at the bottom snaps to whichever end is nearer
        if (rel > sweep) rel = if (rel - sweep < (360f - sweep) / 2) sweep else 0f
        val i = (rel / sweep * (steps - 1)).roundToInt().coerceIn(0, steps - 1)
        val v = rawAt(i)
        if (v != raw) {
            raw = v
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange?.invoke(v)
            invalidate()
        }
    }

    /** One step up or down, from the buttons below the dial. */
    fun step(by: Int): Int {
        val i = (index(raw) + by).coerceIn(0, steps - 1)
        raw = rawAt(i)
        invalidate()
        return raw
    }
}
