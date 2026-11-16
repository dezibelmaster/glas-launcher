package de.glas.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
    /** Height chosen by hand (top of the digits as a fraction of the screen), or null to place it by itself. */
    var manualTop: Float? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /** While the clock is being moved, the landscape is only looked for again once it stands still. */
    var moving = false
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
    private var shownTop = Float.NaN
    private var shaderBright = false

    override fun onDraw(c: Canvas) {
        if (time.isEmpty() || width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        digits.textSize = h * .30f
        digits.getTextBounds("0", 0, 1, bounds)
        val digitH = bounds.height().toFloat()

        val depth = backdrop()?.depth
        val covers = depth?.covers() == true
        // A new picture moves the clock; it glides there instead of jumping
        val target = place(h, digitH)
        if (shownTop.isNaN() || manualTop != null) shownTop = target
        else if (kotlin.math.abs(target - shownTop) > .5f) { shownTop += (target - shownTop) * .08f; postInvalidateOnAnimation() }
        else shownTop = target
        val top = shownTop
        val baseline = top + digitH
        if (!moving) depth?.focus(if (manualTop != null) top / h..baseline / h else null)
        // On a bright sky (snow, fog) the glass turns grey so the clock stays readable
        val bright = (depth?.skyLight() ?: 0f) > .75f

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
        // The rim follows the merged outline: the font's strokes overlap inside each digit, and
        // drawing their own outlines would show small triangles where they cross
        edge.strokeWidth = h * .002f
        edge.color = if (bright) 0x59FFFFFF else 0x4DFFFFFF
        c.drawPath(outline(w / 2, baseline), edge)

        if (depth != null && covers) {
            // Landscape over the digits, only inside the clock so nothing else changes
            val half = digits.measureText(time) / 2
            clip.set(w / 2 - half - h * .02f, top - h * .03f, w / 2 + half + h * .02f, baseline + h * .03f)
            c.save()
            c.clipRect(clip)
            depth.drawForeground(c)
            c.restore()
            // The photo moves slowly, so the landscape in front has to follow
            postInvalidateOnAnimation()
        } else if (depth != null) {
            // A newly loaded photo may move the clock
            postInvalidateDelayed(1000)
        }
    }

    private var outlineKey = ""
    private val outlinePath = Path()

    private fun outline(x: Float, baseline: Float): Path {
        val key = "$time/${digits.textSize}/$x/$baseline"
        if (key != outlineKey) {
            outlineKey = key
            val raw = Path()
            digits.getTextPath(time, 0, time.length, x, baseline, raw)
            outlinePath.reset()
            if (!outlinePath.op(raw, Path.Op.UNION)) outlinePath.set(raw)
        }
        return outlinePath
    }

    /** Where the top of the digits is now, as a fraction of the height (for moving it by hand). */
    fun currentTop(): Float {
        val h = height.toFloat().takeIf { it > 0 } ?: return .19f
        digits.textSize = h * .30f
        digits.getTextBounds("0", 0, 1, bounds)
        val digitH = bounds.height().toFloat()
        return place(h, digitH) / h
    }

    /**
     * Where the top of the digits goes: as chosen by hand, otherwise following the picture. With a
     * mask the landscape covers about a quarter of the digits; when only the horizon is known (a sea
     * of clouds, the sea) the clock stands in the sky just above it. Always clear of the dock.
     */
    private fun place(h: Float, digitH: Float): Float {
        manualTop?.let { return (it * h).coerceIn(minTop(h), maxTop(h, digitH)) }
        val depth = backdrop()?.depth ?: return h * .19f
        val horizon = depth.horizonY()?.takeIf { it in h * .30f..h * .86f } ?: return h * .19f
        val top = if (depth.covers()) horizon + digitH * .24f - digitH else horizon - h * .04f - digitH
        return top.coerceIn(h * .17f, maxTop(h, digitH))
    }

    /** Range for the clock: below the greeting's room at the top, above the dock at the bottom. */
    private fun minTop(h: Float) = h * .14f
    private fun maxTop(h: Float, digitH: Float) = h * .64f - digitH

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..10 -> tr("Guten Morgen")
        in 11..17 -> tr("Guten Tag")
        in 18..21 -> tr("Guten Abend")
        else -> tr("Gute Nacht")
    }
}
