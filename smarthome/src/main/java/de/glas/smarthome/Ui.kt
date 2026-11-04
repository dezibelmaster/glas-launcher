package de.glas.smarthome

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun View.dp(v: Int) = context.dp(v)
fun View.dp(v: Float) = context.dp(v)

fun withAlpha(color: Int, alpha: Int) = (alpha.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

object C {
    const val BG = 0xFF070A12.toInt()
    const val SHEET = 0xFF111725.toInt()
    const val TEXT = 0xFFF5F7FB.toInt()
    const val MUTED = 0xFF8D97AA.toInt()
    const val FAINT = 0xFF5B6476.toInt()
    const val GLASS = 0x12FFFFFF
    const val GLASS_STRONG = 0x1FFFFFFF
    const val STROKE = 0x17FFFFFF
    const val ACCENT = 0xFF7C8CFF.toInt()
    const val BLUE = 0xFF60A5FA.toInt()
    const val CYAN = 0xFF22D3EE.toInt()
    const val GREEN = 0xFF34D399.toInt()
    const val AMBER = 0xFFFBBF24.toInt()
    const val ORANGE = 0xFFFB923C.toInt()
    const val RED = 0xFFF43F5E.toInt()

    private val stops = doubleArrayOf(8.0, 14.0, 18.0, 21.0, 24.0, 28.0)
    private val colors = intArrayOf(0xFF3B82F6.toInt(), CYAN, AMBER, ORANGE, 0xFFF97316.toInt(), RED)
    private val eval = ArgbEvaluator()

    /** Cold blue up to warm red, like the scale on a radiator. */
    fun temp(c: Double): Int {
        if (c <= stops.first()) return colors.first()
        for (i in 1 until stops.size) if (c <= stops[i]) {
            val f = ((c - stops[i - 1]) / (stops[i] - stops[i - 1])).toFloat()
            return eval.evaluate(f, colors[i - 1], colors[i]) as Int
        }
        return colors.last()
    }

    fun raw(raw: Int) = when (raw) {
        Hkr.OFF -> MUTED
        Hkr.ON -> RED
        else -> temp(raw / 2.0)
    }
}

object Type {
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
}

fun Context.label(size: Float, color: Int = C.TEXT, face: Typeface = Type.regular) = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    typeface = face
    includeFontPadding = false
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }

fun Context.row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
}

fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.space(weight: Float = 1f) = View(this).apply { layoutParams = lp(0, 0, weight) }

fun Context.rounded(color: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
    cornerRadius = dp(radius)
    setColor(color)
    if (stroke != 0) setStroke(dp(1), stroke)
}

/** Glass card; an [accent] tints it from the top-left corner while the device is active. */
fun Context.card(accent: Int? = null, radius: Float = 26f) = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    if (accent != null) intArrayOf(withAlpha(accent, 0x3A), withAlpha(accent, 0x10), 0x0DFFFFFF) else intArrayOf(0x14FFFFFF, 0x0BFFFFFF),
).apply {
    cornerRadius = dp(radius)
    setStroke(dp(1), if (accent != null) withAlpha(accent, 0x40) else C.STROKE)
}

fun Context.ripple(radius: Float) = RippleDrawable(
    ColorStateList.valueOf(0x26FFFFFF), null, rounded(Color.WHITE, radius),
)

fun View.pressable(radius: Float) {
    foreground = context.ripple(radius)
    isClickable = true
    // A short scale-down makes taps feel physical
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        false
    }
}

/** Round glass button with a glyph. */
fun Context.iconButton(g: Glyph.Kind, size: Int = 40, tint: Int = C.TEXT, bg: Int = C.GLASS_STRONG, onClick: () -> Unit) =
    ImageView(this).apply {
        setImageDrawable(Glyph(g, tint))
        val pad = dp(size) / 4
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        layoutParams = lp(dp(size), dp(size))
        pressable(size / 2f)
        setOnClickListener { onClick() }
    }

/** A coloured circle holding a glyph, for the corner of a card. */
fun Context.bubble(g: Glyph.Kind, color: Int?, size: Int = 38) = FrameLayout(this).apply {
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (color != null) withAlpha(color, 0x33) else 0x14FFFFFF)
    }
    val img = ImageView(context).apply { setImageDrawable(Glyph(g, color ?: C.MUTED)) }
    val pad = dp(size) / 4
    addView(img, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(pad, pad, pad, pad) })
    layoutParams = lp(dp(size), dp(size))
}

fun Context.pill(text: String, color: Int, filled: Boolean = false) = label(11f, if (filled) C.BG else color, Type.medium).apply {
    this.text = text
    setPadding(dp(9), dp(4), dp(9), dp(4))
    background = rounded(if (filled) color else withAlpha(color, 0x2A), 12f)
}

/** Space the system bars take, on every Android version. */
fun WindowInsets.bars(): IntArray = if (Build.VERSION.SDK_INT >= 30) {
    val i = getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    intArrayOf(i.left, i.top, i.right, i.bottom)
} else {
    @Suppress("DEPRECATION")
    intArrayOf(systemWindowInsetLeft, systemWindowInsetTop, systemWindowInsetRight, systemWindowInsetBottom)
}

/** Deep night-blue background with two soft coloured glows. */
class Backdrop : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xFF0C1222.toInt(), C.BG, Shader.TileMode.CLAMP)
        c.drawRect(b, p)
        p.shader = RadialGradient(w * .1f, h * .05f, w * .9f, 0x3D4F6BFF, 0x004F6BFF, Shader.TileMode.CLAMP)
        c.drawRect(b, p)
        p.shader = RadialGradient(w * 1.05f, h * .32f, w * .75f, 0x26A855F7, 0x00A855F7, Shader.TileMode.CLAMP)
        c.drawRect(b, p)
        p.shader = RadialGradient(w * .2f, h * 1.0f, w * .8f, 0x1A22D3EE, 0x0022D3EE, Shader.TileMode.CLAMP)
        c.drawRect(b, p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** On/off switch drawn like the iOS and Material 3 ones, animated. */
class Toggle(ctx: Context, var color: Int = C.GREEN) : View(ctx) {
    var checked = false
        private set
    var onToggle: ((Boolean) -> Unit)? = null
    private var pos = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eval = ArgbEvaluator()

    init {
        isClickable = true
        setOnClickListener {
            set(!checked, true)
            onToggle?.invoke(checked)
        }
    }

    fun set(on: Boolean, animate: Boolean = false) {
        if (on == checked && (pos == if (on) 1f else 0f)) return
        checked = on
        val to = if (on) 1f else 0f
        if (animate) ValueAnimator.ofFloat(pos, to).apply {
            duration = 180
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
        }.start() else { pos = to; invalidate() }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(50), dp(30))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = eval.evaluate(pos, 0x33FFFFFF, color) as Int
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        val r = h / 2 - dp(3f)
        p.color = Color.WHITE
        p.setShadowLayer(dp(3f), 0f, dp(1f), 0x55000000)
        c.drawCircle(h / 2 + (w - h) * pos, h / 2, r, p)
        p.clearShadowLayer()
    }
}

/** Line icons drawn on a 24-unit grid, so they stay sharp at every size and need no image files. */
class Glyph(private val kind: Kind, color: Int) : Drawable() {
    enum class Kind {
        RADIATOR, PLUG, BULB, THERMO, DROP, BLIND, HOME, GEAR, REFRESH, PLUS, MINUS, FLAME, WINDOW,
        BATTERY, BOLT, POWER, SPARK, ALERT, MOON, SUN, CLOSE, UP, DOWN, STOP, REMOTE, BELL, CLOCK, ROCKET, CHEVRON
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    override fun getIntrinsicWidth() = -1
    override fun getIntrinsicHeight() = -1

    override fun draw(c: Canvas) {
        val s = minOf(bounds.width(), bounds.height()) / 24f
        c.save()
        c.translate(bounds.left + (bounds.width() - 24 * s) / 2, bounds.top + (bounds.height() - 24 * s) / 2)
        c.scale(s, s)
        val st = stroke
        when (kind) {
            Kind.RADIATOR -> {
                for (i in 0 until 4) {
                    val x = 3.5f + i * 4.6f
                    c.drawRoundRect(RectF(x, 4.5f, x + 3f, 18.5f), 1.5f, 1.5f, st)
                }
                c.drawLine(5f, 21f, 5f, 18.5f, st)
                c.drawLine(19f, 21f, 19f, 18.5f, st)
            }
            Kind.PLUG -> {
                c.drawCircle(12f, 12f, 9f, st)
                c.drawCircle(8.8f, 12f, 1.5f, fill)
                c.drawCircle(15.2f, 12f, 1.5f, fill)
            }
            Kind.BULB -> {
                val p = Path().apply {
                    moveTo(9f, 17f)
                    cubicTo(9f, 14.5f, 5.5f, 13f, 5.5f, 9f)
                    cubicTo(5.5f, 5.4f, 8.4f, 2.5f, 12f, 2.5f)
                    cubicTo(15.6f, 2.5f, 18.5f, 5.4f, 18.5f, 9f)
                    cubicTo(18.5f, 13f, 15f, 14.5f, 15f, 17f)
                    close()
                }
                c.drawPath(p, st)
                c.drawLine(9.5f, 20.5f, 14.5f, 20.5f, st)
            }
            Kind.THERMO -> {
                val p = Path().apply {
                    moveTo(9.5f, 13.6f)
                    lineTo(9.5f, 5f)
                    arcTo(RectF(9.5f, 2.5f, 14.5f, 7.5f), 180f, 180f)
                    lineTo(14.5f, 13.6f)
                    arcTo(RectF(7f, 12f, 17f, 22f), -53f, -254f)
                    close()
                }
                c.drawPath(p, st)
                c.drawCircle(12f, 17f, 2.2f, fill)
                c.drawLine(12f, 9f, 12f, 15f, st)
            }
            Kind.DROP -> {
                val p = Path().apply {
                    moveTo(12f, 2.8f)
                    cubicTo(14.5f, 6.5f, 18.5f, 10.5f, 18.5f, 14.5f)
                    arcTo(RectF(5.5f, 8f, 18.5f, 21f), 0f, 180f)
                    cubicTo(5.5f, 10.5f, 9.5f, 6.5f, 12f, 2.8f)
                    close()
                }
                c.drawPath(p, st)
            }
            Kind.BLIND -> {
                c.drawLine(3f, 4f, 21f, 4f, st)
                for (y in floatArrayOf(8f, 12f, 16f)) c.drawRoundRect(RectF(5f, y - 1.6f, 19f, y + 1.6f), 1f, 1f, st)
                c.drawLine(12f, 18f, 12f, 21f, st)
            }
            Kind.HOME -> {
                val p = Path().apply {
                    moveTo(3f, 11f); lineTo(12f, 3.5f); lineTo(21f, 11f)
                    moveTo(5.5f, 9.5f); lineTo(5.5f, 20.5f); lineTo(18.5f, 20.5f); lineTo(18.5f, 9.5f)
                    moveTo(10f, 20.5f); lineTo(10f, 15f); lineTo(14f, 15f); lineTo(14f, 20.5f)
                }
                c.drawPath(p, st)
            }
            Kind.GEAR -> {
                c.drawCircle(12f, 12f, 3f, st)
                val p = Path()
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val a1 = a - .2; val a2 = a + .2
                    val r1 = 7.2; val r2 = 9.6
                    if (i == 0) p.moveTo((12 + r1 * Math.cos(a - .38)).toFloat(), (12 + r1 * Math.sin(a - .38)).toFloat())
                    p.lineTo((12 + r2 * Math.cos(a1)).toFloat(), (12 + r2 * Math.sin(a1)).toFloat())
                    p.lineTo((12 + r2 * Math.cos(a2)).toFloat(), (12 + r2 * Math.sin(a2)).toFloat())
                    p.lineTo((12 + r1 * Math.cos(a + .38)).toFloat(), (12 + r1 * Math.sin(a + .38)).toFloat())
                    p.lineTo((12 + r1 * Math.cos(a + 0.785 - .38)).toFloat(), (12 + r1 * Math.sin(a + 0.785 - .38)).toFloat())
                }
                p.close()
                c.drawPath(p, st)
            }
            Kind.REFRESH -> {
                c.drawArc(RectF(4f, 4f, 20f, 20f), 40f, 290f, false, st)
                val p = Path().apply { moveTo(19.5f, 3.5f); lineTo(19.5f, 8.5f); lineTo(14.5f, 8.5f) }
                c.drawPath(p, st)
            }
            Kind.PLUS -> { c.drawLine(12f, 5f, 12f, 19f, st); c.drawLine(5f, 12f, 19f, 12f, st) }
            Kind.MINUS -> c.drawLine(5f, 12f, 19f, 12f, st)
            Kind.FLAME -> {
                val p = Path().apply {
                    moveTo(12f, 2.5f)
                    cubicTo(12.5f, 6.5f, 18.5f, 9f, 18.5f, 14.5f)
                    cubicTo(18.5f, 18.5f, 15.5f, 21.5f, 12f, 21.5f)
                    cubicTo(8.5f, 21.5f, 5.5f, 18.5f, 5.5f, 14.5f)
                    cubicTo(5.5f, 11.5f, 7.5f, 9.5f, 8.5f, 8f)
                    cubicTo(9f, 10f, 10f, 11f, 11f, 11.5f)
                    cubicTo(10.5f, 8f, 11f, 5f, 12f, 2.5f)
                    close()
                }
                c.drawPath(p, st)
            }
            Kind.WINDOW -> {
                c.drawRoundRect(RectF(4.5f, 3f, 19.5f, 21f), 1.5f, 1.5f, st)
                c.drawLine(12f, 3f, 12f, 21f, st)
                c.drawLine(4.5f, 12f, 19.5f, 12f, st)
            }
            Kind.BATTERY -> {
                c.drawRoundRect(RectF(2.5f, 7f, 19f, 17f), 2.5f, 2.5f, st)
                c.drawRoundRect(RectF(20f, 10f, 22f, 14f), 1f, 1f, fill)
                c.drawRoundRect(RectF(5f, 9.5f, 9.5f, 14.5f), 1f, 1f, fill)
            }
            Kind.BOLT -> {
                val p = Path().apply {
                    moveTo(13.5f, 2.5f); lineTo(5f, 13.5f); lineTo(11.5f, 13.5f); lineTo(10.5f, 21.5f)
                    lineTo(19f, 10.5f); lineTo(12.5f, 10.5f); close()
                }
                c.drawPath(p, st)
            }
            Kind.POWER -> {
                c.drawArc(RectF(4f, 4.5f, 20f, 20.5f), -50f, 280f, false, st)
                c.drawLine(12f, 2.5f, 12f, 11f, st)
            }
            Kind.SPARK -> {
                val p = Path().apply {
                    moveTo(12f, 2.5f); quadTo(13f, 11f, 21.5f, 12f); quadTo(13f, 13f, 12f, 21.5f)
                    quadTo(11f, 13f, 2.5f, 12f); quadTo(11f, 11f, 12f, 2.5f); close()
                }
                c.drawPath(p, st)
            }
            Kind.ALERT -> {
                val p = Path().apply { moveTo(12f, 3f); lineTo(22f, 20.5f); lineTo(2f, 20.5f); close() }
                c.drawPath(p, st)
                c.drawLine(12f, 9.5f, 12f, 14f, st)
                c.drawCircle(12f, 17.2f, 1.1f, fill)
            }
            Kind.MOON -> {
                val p = Path().apply {
                    moveTo(20f, 14.5f)
                    cubicTo(18.8f, 18.5f, 15.2f, 21f, 11.5f, 21f)
                    cubicTo(6.5f, 21f, 3f, 17.2f, 3f, 12.5f)
                    cubicTo(3f, 8.6f, 5.5f, 5.3f, 9.5f, 4f)
                    cubicTo(8.2f, 9.5f, 13.5f, 16f, 20f, 14.5f)
                    close()
                }
                c.drawPath(p, st)
            }
            Kind.SUN -> {
                c.drawCircle(12f, 12f, 4f, st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(
                        (12 + 7 * Math.cos(a)).toFloat(), (12 + 7 * Math.sin(a)).toFloat(),
                        (12 + 9.5 * Math.cos(a)).toFloat(), (12 + 9.5 * Math.sin(a)).toFloat(), st,
                    )
                }
            }
            Kind.CLOSE -> { c.drawLine(6f, 6f, 18f, 18f, st); c.drawLine(18f, 6f, 6f, 18f, st) }
            Kind.UP -> { val p = Path().apply { moveTo(5f, 15f); lineTo(12f, 8f); lineTo(19f, 15f) }; c.drawPath(p, st) }
            Kind.DOWN -> { val p = Path().apply { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) }; c.drawPath(p, st) }
            Kind.CHEVRON -> { val p = Path().apply { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }; c.drawPath(p, st) }
            Kind.STOP -> c.drawRoundRect(RectF(6.5f, 6.5f, 17.5f, 17.5f), 2.5f, 2.5f, fill)
            Kind.REMOTE -> {
                c.drawRoundRect(RectF(6f, 2.5f, 18f, 21.5f), 4f, 4f, st)
                c.drawCircle(12f, 12f, 3f, st)
                c.drawCircle(12f, 6.3f, 1f, fill)
            }
            Kind.BELL -> {
                val p = Path().apply {
                    moveTo(6f, 16.5f); lineTo(6f, 11f)
                    cubicTo(6f, 7.5f, 8.7f, 4.5f, 12f, 4.5f)
                    cubicTo(15.3f, 4.5f, 18f, 7.5f, 18f, 11f)
                    lineTo(18f, 16.5f); lineTo(20f, 18.5f); lineTo(4f, 18.5f); close()
                }
                c.drawPath(p, st)
                c.drawLine(10.5f, 21.5f, 13.5f, 21.5f, st)
            }
            Kind.CLOCK -> {
                c.drawCircle(12f, 12f, 9f, st)
                val p = Path().apply { moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f) }
                c.drawPath(p, st)
            }
            Kind.ROCKET -> {
                val p = Path().apply {
                    moveTo(12f, 2.5f)
                    cubicTo(15.5f, 5f, 16.5f, 9f, 15.5f, 15f)
                    lineTo(8.5f, 15f)
                    cubicTo(7.5f, 9f, 8.5f, 5f, 12f, 2.5f)
                    close()
                    moveTo(8.5f, 12f); lineTo(5.5f, 15.5f); lineTo(8.8f, 16.5f)
                    moveTo(15.5f, 12f); lineTo(18.5f, 15.5f); lineTo(15.2f, 16.5f)
                    moveTo(10.5f, 18f); lineTo(10.5f, 20.5f)
                    moveTo(13.5f, 18f); lineTo(13.5f, 20.5f)
                }
                c.drawPath(p, st)
                c.drawCircle(12f, 9f, 1.6f, fill)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
        invalidateSelf()
    }
    override fun setColorFilter(cf: ColorFilter?) {
        stroke.colorFilter = cf
        fill.colorFilter = cf
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun kindGlyph(d: Device) = when (d.kind) {
    Kind.ROOM -> if (d.hkr != null) Glyph.Kind.HOME else Glyph.Kind.HOME
    Kind.THERMOSTAT -> Glyph.Kind.RADIATOR
    Kind.SWITCH -> Glyph.Kind.PLUG
    Kind.LIGHT -> Glyph.Kind.BULB
    Kind.BLIND -> Glyph.Kind.BLIND
    Kind.SENSOR -> if (d.product.contains("Control", true) || d.product.contains("440")) Glyph.Kind.REMOTE else Glyph.Kind.THERMO
    Kind.ALARM -> Glyph.Kind.BELL
    Kind.OTHER -> Glyph.Kind.SPARK
}
