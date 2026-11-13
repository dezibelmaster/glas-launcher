package de.glas.browser

import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

// The look of the Glas Launcher (dark glass, white text), kept small for the browser app.

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

object Ui {
    const val MUTED = 0x9EF3F6FA.toInt()
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun text(ctx: Context, size: Float, color: Int = Color.WHITE) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        typeface = light
        setTextColor(color)
    }

    fun rowBackground(ctx: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = ctx.dp(14f)
        setColor(if (focused) 0x33FFFFFF else 0x0DFFFFFF)
        if (focused) setStroke(ctx.dp(2), Color.WHITE)
    }

    fun panel(ctx: Context) = GradientDrawable().apply {
        cornerRadius = ctx.dp(26f)
        setColor(0xF20D1117.toInt())
        setStroke(ctx.dp(1), 0x2EFFFFFF)
    }

    fun row(ctx: Context, title: String, value: String? = null, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        background = rowBackground(ctx, false)
        setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(14))
        addView(text(ctx, 17f).apply { text = title }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (value != null) addView(text(ctx, 15f, MUTED).apply { text = value })
        setOnFocusChangeListener { v, f -> v.background = rowBackground(ctx, f) }
        setOnClickListener { onClick() }
    }
}

/** A list of choices in a dark panel, operated with the remote. */
object GlassMenu {
    class Item(val label: String, val value: String? = null, val action: () -> Unit)

    fun show(ctx: Context, title: String, items: List<Item>, focus: Int = 0, footer: String? = null, onCancel: (() -> Unit)? = null): Dialog {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var chosen = false
        dialog.setOnDismissListener { if (!chosen) onCancel?.invoke() }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(28), ctx.dp(24), ctx.dp(28), ctx.dp(24))
        }
        list.addView(Ui.text(ctx, 22f).apply { text = title; setPadding(0, 0, 0, ctx.dp(12)) })
        val rows = items.map { item -> Ui.row(ctx, item.label, item.value) { chosen = true; dialog.dismiss(); item.action() } }
        for (row in rows) list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(6) })
        if (footer != null) list.addView(Ui.text(ctx, 13f, Ui.MUTED).apply {
            text = footer
            setLineSpacing(0f, 1.2f)
            setPadding(0, ctx.dp(14), 0, 0)
        })
        dialog.setContentView(ScrollView(ctx).apply { addView(list) })
        dialog.window?.setBackgroundDrawable(Ui.panel(ctx))
        dialog.show()
        dialog.window?.setLayout(ctx.dp(600), WRAP_CONTENT)
        rows.getOrNull(focus.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))?.requestFocus()
        return dialog
    }
}

/** Asks for a line of text; on the TV the on-screen keyboard opens when OK is pressed on the field. */
object TextPrompt {
    fun ask(ctx: Context, title: String, hint: String, initial: String = "", button: String = tr("Öffnen"), onCancel: (() -> Unit)? = null, onText: (String) -> Unit) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var done = false
        dialog.setOnDismissListener { if (!done) onCancel?.invoke() }
        val field = EditText(ctx).apply {
            this.hint = hint
            setText(initial)
            setSingleLine()
            textSize = 20f
            setTextColor(Color.WHITE)
            setHintTextColor(Ui.MUTED)
            imeOptions = EditorInfo.IME_ACTION_GO
            background = Ui.rowBackground(ctx, false)
            setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(14))
            setOnFocusChangeListener { v, f -> v.background = Ui.rowBackground(ctx, f) }
        }
        fun submit() {
            done = true
            dialog.dismiss()
            onText(field.text.toString())
        }
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(28), ctx.dp(24), ctx.dp(28), ctx.dp(24))
            addView(Ui.text(ctx, 22f).apply { text = title; setPadding(0, 0, 0, ctx.dp(14)) })
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(Ui.row(ctx, button) { submit() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
        }
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(Ui.panel(ctx))
        dialog.show()
        dialog.window?.setLayout(ctx.dp(600), WRAP_CONTENT)
        field.requestFocus()
    }
}

/**
 * Texts are written in German and wrapped in tr(); the other languages are tables in
 * assets/i18n/<code>.json. The language is the launcher's (sent along when it opens the
 * browser), otherwise the TV's.
 */
object I18n {
    private val codes = setOf("de", "en", "fr", "es", "it", "nl")
    @Volatile var lang = "de"
        private set
    @Volatile private var table: Map<String, String> = emptyMap()

    fun init(ctx: Context, wanted: String? = Store.language(ctx)) {
        val sys = Resources.getSystem().configuration.locales[0].language
        val code = wanted?.takeIf { it in codes } ?: sys.takeIf { it in codes } ?: "en"
        if (code == lang && (code == "de" || table.isNotEmpty())) return
        table = if (code == "de") emptyMap() else runCatching {
            val json = JSONObject(ctx.assets.open("i18n/$code.json").bufferedReader().use { it.readText() })
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
        lang = code
    }

    fun lookup(de: String) = table[de] ?: de
}

fun tr(de: String, vararg args: Any?): String {
    var s = I18n.lookup(de)
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}
