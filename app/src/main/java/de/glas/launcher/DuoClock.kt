package de.glas.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.util.Calendar

/**
 * Large clock in the middle of the screen, in the style of a phone's lock screen: round glassy
 * digits with a greeting and the date above. On the built-in weather photos the landscape
 * covers the lower part of the digits, as if the clock stood behind the mountains.
 */
class DuoClock(context: Context, private val backdrop: () -> Backdrop?) : View(context) {
    var time = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var line = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    private val font = runCatching { Typeface.createFromAsset(context.assets, "fonts/clock.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textAlign = Paint.Align.CENTER }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bounds = Rect()
    private val clip = RectF()
    private var shaderTop = Float.NaN
    private var shaderBright = false

    override fun onDraw(c: Canvas) {
        if (time.isEmpty() || width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        digits.textSize = h * .30f
        digits.getTextBounds("0", 0, 1, bounds)
        val digitH = bounds.height().toFloat()

        // Where the digits go: by default in the upper half; on a photo with a mask so that the
        // landscape covers about a third of them, as long as they stay clear of the dock
        val depth = backdrop()?.depth
        val horizon = depth?.horizonY()
        var top = h * .19f
        if (horizon != null && horizon in h * .30f..h * .70f) {
            top = (horizon + digitH * .24f - digitH).coerceIn(h * .17f, h * .64f - digitH)
        }
        val baseline = top + digitH
        // On a bright sky (snow, fog) the glass turns grey so the clock stays readable
        val bright = (depth?.skyLight() ?: 0f) > .62f

        // Greeting and date above the clock
        small.textSize = h * .028f
        small.color = if (bright) 0xE6343A42.toInt() else 0xF2FFFFFF.toInt()
        small.setShadowLayer(h * .006f, 0f, h * .002f, if (bright) 0x40FFFFFF else 0x66000000)
        c.drawText(greeting(), w / 2, top - h * .085f, small)
        small.textSize = h * .036f
        c.drawText(line, w / 2, top - h * .035f, small)

        // Digits: light at the top, more transparent towards the bottom, with a soft shadow and rim
        if (shaderTop != top || shaderBright != bright) {
            shaderTop = top
            shaderBright = bright
            digits.shader = if (bright) LinearGradient(0f, top, 0f, baseline, 0xF2646B74.toInt(), 0xCC80878F.toInt(), Shader.TileMode.CLAMP)
            else LinearGradient(0f, top, 0f, baseline, 0xFFFFFFFF.toInt(), 0xB8DDE8F5.toInt(), Shader.TileMode.CLAMP)
        }
        digits.setShadowLayer(h * .012f, 0f, h * .004f, 0x40000000)
        c.drawText(time, w / 2, baseline, digits)
        edge.textSize = digits.textSize
        edge.strokeWidth = h * .002f
        edge.color = if (bright) 0x59FFFFFF else 0x4DFFFFFF
        c.drawText(time, w / 2, baseline, edge)

        if (depth != null && horizon != null) {
            // Landscape over the digits, only inside the clock so nothing else changes
            val half = digits.measureText(time) / 2
            clip.set(w / 2 - half - h * .02f, top - h * .03f, w / 2 + half + h * .02f, baseline + h * .03f)
            c.save()
            c.clipRect(clip)
            depth.drawForeground(c)
            c.restore()
            // The photo moves slowly, so the landscape in front has to follow
            postInvalidateOnAnimation()
        }
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..10 -> tr("Guten Morgen")
        in 11..17 -> tr("Guten Tag")
        in 18..21 -> tr("Guten Abend")
        else -> tr("Gute Nacht")
    }
}
