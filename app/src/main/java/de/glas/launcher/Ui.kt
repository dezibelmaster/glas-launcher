package de.glas.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

object Ui {
    const val MUTED = 0x9EF3F6FA.toInt()
    val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    val glassNames get() = listOf(tr("Hell"), tr("Mittel"), tr("Dunkel"), tr("Liquid Glass"))

    /** The bright, clear glass of tvOS 26 with a light rim, instead of dark frosted glass. */
    const val LIQUID = 3

    /** Glass tint for a strength from the settings; [extra] makes it a bit more or less opaque. */
    fun glassTint(level: Int, extra: Int = 0): Int {
        if (level == LIQUID) {
            // Panels full of text (all apps, picker) get a dark veil so the writing stays readable
            if (extra > 0) return 0x8C0A0D12.toInt()
            return ((0x24 + extra / 4).coerceIn(0x10, 0x40) shl 24) or 0xFFFFFF
        }
        val alpha = (intArrayOf(0x73, 0x9E, 0xC4)[level.coerceIn(0, 2)] + extra).coerceIn(0x20, 0xF2)
        return (alpha shl 24) or 0x080B10
    }

    /**
     * App icon as a rounded square of [size] pixels, so all icons in the dock share one shape.
     * Adaptive icons are drawn edge to edge from their layers; other icons are clipped.
     */
    fun roundedIcon(icon: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size * .22f
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, Path.Direction.CW) })
        // Adaptive icons exist from Android 8; older devices such as Fire OS 6 only have plain ones
        if (Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable) {
            // Layers are 108 units with the visible part in the middle 72; show exactly that part
            val pad = size / 4
            for (layer in listOfNotNull(icon.background, icon.foreground)) {
                layer.setBounds(-pad, -pad, size + pad, size + pad)
                layer.draw(c)
            }
        } else {
            icon.setBounds(0, 0, size, size)
            icon.draw(c)
        }
        return bmp
    }

    fun text(ctx: Context, size: Float, face: Typeface = light, color: Int = Color.WHITE) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        typeface = face
        setTextColor(color)
        setShadowLayer(ctx.dp(12f), 0f, ctx.dp(1f), 0x40000000)
    }

    /** Stronger shadow for text that sits right on the background, so it stays readable on bright skies. */
    fun readable(tv: TextView) = tv.apply { setShadowLayer(tv.context.dp(8f), 0f, tv.context.dp(1.5f), 0xB3000000.toInt()) }

    fun outline(ctx: Context, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.TRANSPARENT)
        setStroke(ctx.dp(1), 0x24FFFFFF)
    }

    fun tileBackground(ctx: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = ctx.dp(18f)
        setColor(if (focused) 0x2EFFFFFF else 0x0FFFFFFF)
        setStroke(if (focused) ctx.dp(2) else ctx.dp(1), if (focused) Color.WHITE else 0x17FFFFFF)
    }

    fun rowBackground(ctx: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = ctx.dp(14f)
        setColor(if (focused) 0x33FFFFFF else 0x0DFFFFFF)
        if (focused) setStroke(ctx.dp(2), Color.WHITE)
    }

    /** Opaque dark panel for dialogs, which live in their own window and can't blur the home screen. */
    fun panel(ctx: Context) = GradientDrawable().apply {
        if (liquid(ctx)) {
            // Clear, light glass: from Android 12 the window blurs what is behind it, otherwise a darker veil
            cornerRadius = ctx.dp(34f)
            if (canBlur(ctx)) {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(0x52B4BCC8, 0x3D7C8592)
            } else setColor(0xD90E1218.toInt())
            setStroke(ctx.dp(1), 0x73FFFFFF)
        } else {
            cornerRadius = ctx.dp(26f)
            setColor(0xF20D1117.toInt())
            setStroke(ctx.dp(1), 0x2EFFFFFF)
        }
    }

    fun liquid(ctx: Context) = ctx.getSharedPreferences("glas", Context.MODE_PRIVATE).getInt("glass", 2) == LIQUID

    fun canBlur(ctx: Context) = Build.VERSION.SDK_INT >= 31 &&
        (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).isCrossWindowBlurEnabled

    /** Background of a dialog window; with Liquid Glass it blurs what lies behind it. */
    fun styleDialog(dialog: android.app.Dialog, ctx: Context) {
        val w = dialog.window ?: return
        if (liquid(ctx) && !canBlur(ctx)) {
            // The TV doesn't blur behind windows: glass made from the home screen's picture instead
            val glass = GlassDrawable(ctx, ctx.dp(34f), usePicture = true)
            w.setBackgroundDrawable(glass)
            glass.attach(w)
            w.setDimAmount(.12f)
            return
        }
        w.setBackgroundDrawable(panel(ctx))
        if (liquid(ctx) && canBlur(ctx) && Build.VERSION.SDK_INT >= 31) {
            w.setBackgroundBlurRadius(ctx.dp(30))
            // Android darkens everything behind a dialog; with clear glass only a little
            w.setDimAmount(.12f)
        }
    }

    /** Rows in the Liquid Glass look: frosted pills that turn white with dark writing when focused. */
    private fun liquidRow(ctx: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = ctx.dp(20f)
        if (focused) setColor(0xF2FFFFFF.toInt())
        else {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(0x38FFFFFF, 0x14FFFFFF)
            setStroke(ctx.dp(1), 0x47FFFFFF)
        }
    }

    /** Focusable row with a title and an optional value on the right, used in menus and settings. */
    fun row(ctx: Context, title: String, value: String? = null, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        val liquid = liquid(ctx)
        background = if (liquid) liquidRow(ctx, false) else rowBackground(ctx, false)
        setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(14))
        val t = text(ctx, 17f).apply { text = title }
        addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val v = if (value != null) text(ctx, 15f, color = MUTED).apply { text = value }.also { addView(it) } else null
        setOnFocusChangeListener { row, f ->
            if (liquid) {
                row.background = liquidRow(ctx, f)
                t.setTextColor(if (f) 0xFF15181D.toInt() else Color.WHITE)
                v?.setTextColor(if (f) 0xFF4A5059.toInt() else MUTED)
            } else row.background = rowBackground(ctx, f)
        }
        setOnClickListener { onClick() }
    }
}
