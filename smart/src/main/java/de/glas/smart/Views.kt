package de.glas.smart

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

const val TEXT = 0xFFF3F6FA.toInt()
const val MUTED = 0x9EF3F6FA.toInt()
const val AMBER = 0xFFFFC857.toInt()
const val GREEN = 0xFF5AD39A.toInt()
const val RED = 0xFFFF6B6B.toInt()

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

fun Context.text(size: Float, color: Int = TEXT, face: Typeface = Typeface.DEFAULT) = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    typeface = face
}

fun Context.glass(radius: Float = 22f, fill: Int = 0x12FFFFFF, stroke: Int = 0x1CFFFFFF) = GradientDrawable().apply {
    cornerRadius = dp(radius)
    setColor(fill)
    setStroke(dp(1), stroke)
}

fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }

fun Context.vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.horizontal() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** Keeps content clear of the status and navigation bars (Android 15 draws apps edge to edge). */
fun Activity.fitBars(root: View) {
    val l = root.paddingLeft
    val t = root.paddingTop
    val r = root.paddingRight
    val b = root.paddingBottom
    root.setOnApplyWindowInsetsListener { v, insets ->
        @Suppress("DEPRECATION")
        v.setPadding(l + insets.systemWindowInsetLeft, t + insets.systemWindowInsetTop,
            r + insets.systemWindowInsetRight, b + insets.systemWindowInsetBottom)
        insets
    }
}

/** Rounded pill button, e.g. for scenes and quick actions. */
fun Context.pill(label: String, accent: Int? = null, onClick: () -> Unit) = text(15f, if (accent != null) accent else TEXT, medium).apply {
    text = label
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(10), dp(16), dp(10))
    background = glass(20f, if (accent != null) (accent and 0x00FFFFFF) or 0x26000000 else 0x14FFFFFF,
        if (accent != null) (accent and 0x00FFFFFF) or 0x66000000 else 0x24FFFFFF)
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.pillRow(pills: List<View>) = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    clipToPadding = false
    val row = horizontal()
    pills.forEachIndexed { i, p -> row.addView(p, wrap().margins(r = if (i < pills.size - 1) dp(8) else 0)) }
    addView(row)
}

fun Context.sectionTitle(title: String) = text(13f, MUTED, medium).apply {
    text = title.uppercase()
    letterSpacing = .08f
    setPadding(dp(6), dp(22), dp(6), dp(10))
}

/** Round on/off button for device cards. */
@SuppressLint("ViewConstructor")
class PowerButton(ctx: Context, private val accent: Int) : View(ctx) {
    var on = false
        set(v) {
            field = v
            invalidate()
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = ctx.dp(2.2f)
    }

    init {
        isClickable = true
        contentDescription = "Ein/Aus"
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        fill.color = if (on) accent else 0x1FFFFFFF
        c.drawCircle(cx, cy, r, fill)
        line.color = if (on) 0xFF101418.toInt() else TEXT
        val ir = r * .42f
        c.drawArc(RectF(cx - ir, cy - ir, cx + ir, cy + ir), -60f, 300f, false, line)
        c.drawLine(cx, cy - ir * 1.25f, cx, cy - ir * .15f, line)
    }
}

fun SeekBar.tint(color: Int) {
    progressTintList = ColorStateList.valueOf(color)
    thumbTintList = ColorStateList.valueOf(color)
    progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
}

/** Glass block with a title above its content. */
fun Context.panel(title: String?, content: View): LinearLayout = vertical().apply {
    background = glass()
    setPadding(dp(18), dp(14), dp(18), dp(16))
    if (title != null) addView(text(14f, MUTED, medium).apply { text = title }, matchWrap().margins(b = dp(6)))
    addView(content, matchWrap())
}

/** Row with a label and a switch. */
fun Context.switchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) = horizontal().apply {
    setPadding(0, dp(6), 0, dp(6))
    addView(text(17f).apply { text = label }, weight())
    addView(Switch(context).apply {
        isChecked = checked
        isEnabled = enabled
        setOnCheckedChangeListener { _, c -> onChange(c) }
    })
}

/** Label with live value and a slider; [onSet] fires when the finger lets go, so the device isn't flooded. */
fun Context.sliderRow(
    label: String, min: Int, max: Int, value: Int, color: Int, enabled: Boolean,
    show: (Int) -> String, onSet: (Int) -> Unit,
): LinearLayout = vertical().apply {
    val head = horizontal()
    val valueText = text(15f, MUTED).apply { text = show(value) }
    head.addView(text(17f).apply { text = label }, weight())
    head.addView(valueText)
    addView(head, matchWrap())
    addView(SeekBar(context).apply {
        this.max = max - min
        progress = (value - min).coerceIn(0, max - min)
        isEnabled = enabled
        tint(color)
        setPadding(dp(4), dp(14), dp(4), dp(10))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                valueText.text = show(p + min)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = onSet(s.progress + min)
        })
    }, matchWrap())
}

/** Choice chips for an enum such as the light's mode; the selected one is highlighted. */
fun Context.chips(options: List<Pair<String, String>>, selected: String?, accent: Int, onPick: (String) -> Unit): View =
    pillRow(options.map { (value, label) -> pill(label, if (value == selected) accent else null) { onPick(value) } })

/** Rainbow bar to pick a hue; reports the hue in degrees when released. */
class HueBar(ctx: Context, hue: Int, private val onPick: (Int) -> Unit) : View(ctx) {
    private var hue = hue.toFloat()
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(3f)
        color = Color.WHITE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        minimumHeight = ctx.dp(44)
    }

    fun setHue(h: Int) {
        hue = h.toFloat()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        bar.shader = LinearGradient(pad(), 0f, w - pad(), 0f, colors, null, Shader.TileMode.CLAMP)
    }

    private fun pad() = height / 2f

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val r = h * .28f
        c.drawRoundRect(RectF(pad() - r, h / 2 - r, width - pad() + r, h / 2 + r), r, r, bar)
        val x = pad() + (width - 2 * pad()) * hue / 360f
        dot.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        c.drawCircle(x, h / 2, h * .42f, dot)
        c.drawCircle(x, h / 2, h * .42f, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        parent.requestDisallowInterceptTouchEvent(true)
        hue = ((e.x - pad()) / (width - 2 * pad()) * 360f).coerceIn(0f, 360f)
        invalidate()
        if (e.action == MotionEvent.ACTION_UP) onPick(hue.toInt())
        return true
    }
}

/** Round colour swatch. */
fun Context.swatch(color: Int, onClick: () -> Unit) = View(this).apply {
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), 0x40FFFFFF)
    }
    isClickable = true
    setOnClickListener { onClick() }
}
