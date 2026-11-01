package de.glas.launcher

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest

/** A list of choices in a dark panel, operated with the remote. */
object GlassMenu {
    class Item(val label: String, val action: () -> Unit)

    fun show(ctx: Context, title: String, items: List<Item>, focus: Int = 0, footer: String? = null) {
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
        if (footer != null) list.addView(Ui.text(ctx, 13f, color = Ui.MUTED).apply {
            text = footer
            setPadding(0, ctx.dp(14), 0, 0)
        })
        dialog.setContentView(ScrollView(ctx).apply { addView(list) })
        dialog.window?.setBackgroundDrawable(Ui.panel(ctx))
        dialog.show()
        dialog.window?.setLayout(ctx.dp(560), WRAP_CONTENT)
        rows.getOrNull(focus.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))?.requestFocus()
    }
}

/** Four-digit PIN for the parental lock. Works with the number keys or the on-screen keypad. */
object Pin {
    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("glas:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun ask(ctx: Context, title: String, onEntered: (String) -> Unit) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var pin = ""
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
                setTextColor(0xFFFFFFFF.toInt())
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
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(Ui.panel(ctx))
        dialog.show()
        buttons[4].requestFocus()
    }

    /** Runs [onOk] right away if no PIN is set, otherwise after the right PIN. */
    fun verify(ctx: Context, prefs: Prefs, title: String, onOk: () -> Unit) {
        val expected = prefs.pinHash ?: return onOk()
        ask(ctx, title) { if (hash(it) == expected) onOk() else ctx.toast(tr("Falscher PIN")) }
    }

    fun create(ctx: Context, prefs: Prefs, onDone: () -> Unit) {
        ask(ctx, tr("Neuen PIN festlegen")) { first ->
            ask(ctx, tr("PIN wiederholen")) { second ->
                if (first == second) {
                    prefs.pinHash = hash(first)
                    ctx.toast(tr("PIN gespeichert"))
                    onDone()
                } else {
                    ctx.toast(tr("Die PINs stimmen nicht überein"))
                }
            }
        }
    }
}

/** Asks for a line of text; on the TV the on-screen keyboard opens when OK is pressed on the field. */
object TextPrompt {
    fun ask(ctx: Context, title: String, hint: String, onText: (String) -> Unit) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val field = android.widget.EditText(ctx).apply {
            this.hint = hint
            setSingleLine()
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(Ui.MUTED)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = Ui.rowBackground(ctx, false)
            setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(14))
            setOnFocusChangeListener { v, f -> v.background = Ui.rowBackground(ctx, f) }
        }
        fun submit() {
            dialog.dismiss()
            onText(field.text.toString())
        }
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(28), ctx.dp(24), ctx.dp(28), ctx.dp(24))
            addView(Ui.text(ctx, 22f).apply {
                text = title
                setPadding(0, 0, 0, ctx.dp(14))
            })
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(Ui.row(ctx, tr("Suchen")) { submit() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
        }
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(Ui.panel(ctx))
        dialog.show()
        dialog.window?.setLayout(ctx.dp(560), WRAP_CONTENT)
        field.requestFocus()
    }
}
