package de.glas.kids

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

/** Look of the Glas Launcher: sky gradient, dark frosted glass, white text. */
object Ui {
    const val MUTED = 0x9EF3F6FA.toInt()
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun sky() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7FA6D6.toInt(), 0xFF2B4A7A.toInt(), 0xFF14223B.toInt()))

    fun glass(ctx: Context, radius: Float = 22f) = GradientDrawable().apply {
        cornerRadius = ctx.dp(radius)
        setColor(0x8C141A28.toInt())
        setStroke(ctx.dp(1), 0x2EFFFFFF)
    }

    fun text(ctx: Context, size: Float, face: Typeface = light, color: Int = Color.WHITE) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        typeface = face
        setTextColor(color)
        setShadowLayer(ctx.dp(8f), 0f, ctx.dp(1.5f), 0x99000000.toInt())
    }

    fun rowBackground(ctx: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = ctx.dp(14f)
        setColor(if (focused) 0x33FFFFFF else 0x0DFFFFFF)
        if (focused) setStroke(ctx.dp(2), Color.WHITE)
    }

    fun buttonBackground(ctx: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = ctx.dp(16f)
        setColor(if (focused) Color.WHITE else 0x1FFFFFFF)
        setStroke(ctx.dp(1), if (focused) Color.WHITE else 0x2EFFFFFF)
    }

    /** Opaque dark panel for dialogs. */
    fun panel(ctx: Context) = GradientDrawable().apply {
        cornerRadius = ctx.dp(26f)
        setColor(0xF20D1117.toInt())
        setStroke(ctx.dp(1), 0x2EFFFFFF)
    }

    /** Big button; white with dark text while focused, so it is easy to see from the sofa. */
    fun button(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        typeface = medium
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setPadding(ctx.dp(28), ctx.dp(14), ctx.dp(28), ctx.dp(14))
        fun paint(f: Boolean) {
            background = buttonBackground(ctx, f)
            setTextColor(if (f) 0xFF14223B.toInt() else Color.WHITE)
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { onClick() }
    }

    /** Focusable row with a title and an optional value on the right, used in menus and settings. */
    fun row(ctx: Context, title: String, value: String? = null, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        background = rowBackground(ctx, false)
        setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(14))
        addView(text(ctx, 17f).apply { text = title }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (value != null) addView(text(ctx, 15f, color = MUTED).apply { text = value })
        setOnFocusChangeListener { v, f -> v.background = rowBackground(ctx, f) }
        setOnClickListener { onClick() }
    }

    /** App icon as a rounded square of [size] pixels. */
    fun roundedIcon(icon: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size * .22f
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, Path.Direction.CW) })
        if (Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable) {
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
}

/** A list of choices in a dark panel, operated with the remote. */
object GlassMenu {
    class Item(val label: String, val action: () -> Unit)

    fun show(ctx: Context, title: String, items: List<Item>, focus: Int = 0, footer: String? = null): Dialog {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(28), ctx.dp(24), ctx.dp(28), ctx.dp(24))
        }
        list.addView(Ui.text(ctx, 22f).apply {
            text = title
            setPadding(0, 0, 0, ctx.dp(12))
        })
        val rows = items.map { item -> Ui.row(ctx, item.label) { dialog.dismiss(); item.action() } }
        for (row in rows) list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(6) })
        if (footer != null) list.addView(Ui.text(ctx, 14f, color = Ui.MUTED).apply {
            text = footer
            setPadding(0, ctx.dp(14), 0, 0)
        })
        dialog.setContentView(ScrollView(ctx).apply { addView(list) })
        dialog.window?.setBackgroundDrawable(Ui.panel(ctx))
        dialog.show()
        dialog.window?.setLayout(ctx.dp(580), WRAP_CONTENT)
        rows.getOrNull(focus.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))?.requestFocus()
        return dialog
    }

    fun info(ctx: Context, title: String, text: String, actions: List<Item> = emptyList()) =
        show(ctx, title, actions + Item(tr("Schließen")) {}, footer = text)
}

/** Four-digit parent PIN. Works with the number keys or the on-screen keypad. Three wrong tries: one minute pause. */
object Pin {
    private const val MAX_TRIES = 3
    private const val PAUSE_MS = 60_000L

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("glas-kids:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun ask(ctx: Context, title: String, onCancel: () -> Unit = {}, onEntered: (String) -> Unit): Dialog {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var pin = ""
        var entered = false
        val dots = Ui.text(ctx, 34f).apply {
            gravity = Gravity.CENTER
            letterSpacing = .3f
            setPadding(0, ctx.dp(10), 0, ctx.dp(16))
        }
        fun refresh() { dots.text = "●".repeat(pin.length) + "○".repeat(4 - pin.length) }
        fun press(d: Char) {
            if (pin.length >= 4) return
            pin += d
            refresh()
            if (pin.length == 4) {
                entered = true
                dialog.dismiss()
                onEntered(pin)
            }
        }
        fun back() {
            if (pin.isNotEmpty()) pin = pin.dropLast(1)
            refresh()
        }
        refresh()

        val grid = GridLayout(ctx).apply { columnCount = 3 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕")
        val buttons = keys.map { k ->
            TextView(ctx).apply {
                text = k
                setTextColor(Color.WHITE)
                textSize = 22f
                typeface = Ui.light
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                background = Ui.rowBackground(ctx, false)
                setOnFocusChangeListener { v, f -> v.background = Ui.rowBackground(ctx, f) }
                setOnClickListener {
                    when (k) {
                        "⌫" -> back()
                        "✕" -> dialog.dismiss()
                        else -> press(k[0])
                    }
                }
            }
        }
        for (b in buttons) grid.addView(b, GridLayout.LayoutParams().apply {
            width = ctx.dp(84)
            height = ctx.dp(60)
            setMargins(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
        })

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(32), ctx.dp(26), ctx.dp(32), ctx.dp(26))
            addView(Ui.text(ctx, 20f).apply {
                text = title
                gravity = Gravity.CENTER
            })
            addView(dots)
            addView(grid)
        }
        dialog.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press('0' + (code - KeyEvent.KEYCODE_0)); true }
                KeyEvent.KEYCODE_DEL -> { back(); true }
                else -> false
            }
        }
        dialog.setOnDismissListener { if (!entered) onCancel() }
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(Ui.panel(ctx))
        dialog.show()
        buttons[4].requestFocus()
        return dialog
    }

    /** Runs [onOk] after the right PIN; sets a PIN up first if there is none yet. */
    fun verify(ctx: Context, prefs: Prefs, title: String, onCancel: () -> Unit = {}, onOk: () -> Unit) {
        val expected = prefs.pinHash ?: return create(ctx, prefs, onCancel, onOk)
        val wait = prefs.pinBlockedUntil - System.currentTimeMillis()
        if (wait > 0) {
            ctx.toast(tr("Zu oft falsch. Bitte {0} Sekunden warten.", (wait + 999) / 1000))
            return onCancel()
        }
        ask(ctx, title, onCancel) {
            if (hash(it) == expected) {
                prefs.wrongPins = 0
                onOk()
            } else {
                val tries = prefs.wrongPins + 1
                if (tries >= MAX_TRIES) {
                    prefs.wrongPins = 0
                    prefs.pinBlockedUntil = System.currentTimeMillis() + PAUSE_MS
                    ctx.toast(tr("Zu oft falsch. Bitte {0} Sekunden warten.", PAUSE_MS / 1000))
                } else {
                    prefs.wrongPins = tries
                    ctx.toast(tr("Falscher PIN"))
                }
                onCancel()
            }
        }
    }

    fun create(ctx: Context, prefs: Prefs, onCancel: () -> Unit = {}, onDone: () -> Unit) {
        ask(ctx, tr("Eltern-PIN festlegen"), onCancel) { first ->
            ask(ctx, tr("PIN wiederholen"), onCancel) { second ->
                if (first == second) {
                    prefs.pinHash = hash(first)
                    ctx.toast(tr("PIN gespeichert"))
                    onDone()
                } else {
                    ctx.toast(tr("Die PINs stimmen nicht überein"))
                    onCancel()
                }
            }
        }
    }
}
