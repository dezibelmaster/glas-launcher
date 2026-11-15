package de.glas.launcher

import android.content.Context
import android.view.KeyEvent
import org.json.JSONObject

/**
 * New jobs for keys of the remote: once, twice or long pressed. The keys are caught by the
 * accessibility service ("Home-Taste umleiten"), which sees them before any app does.
 */
object KeyRemap {
    class Mapping(val press: String?, val double: String?, val long: String?) {
        val empty get() = press == null && double == null && long == null
    }

    /** Keys needed to move around and confirm; giving them other jobs would lock the TV. */
    private val reserved = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ESCAPE,
    )

    fun allowed(code: Int) = code !in reserved && code != KeyEvent.KEYCODE_UNKNOWN

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("glas", Context.MODE_PRIVATE)

    fun all(ctx: Context): Map<Int, Mapping> {
        val raw = prefs(ctx).getString("keymap", null) ?: return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().mapNotNull { k ->
            val m = o.optJSONObject(k) ?: return@mapNotNull null
            fun s(n: String) = m.optString(n).takeIf { it.isNotEmpty() }
            k.toIntOrNull()?.let { it to Mapping(s("press"), s("double"), s("long")) }
        }.toMap()
    }

    fun get(ctx: Context, code: Int): Mapping? = all(ctx)[code]?.takeUnless { it.empty }

    fun put(ctx: Context, code: Int, m: Mapping?) {
        val map = all(ctx).toMutableMap()
        if (m == null || m.empty) map.remove(code) else map[code] = m
        val o = JSONObject()
        for ((k, v) in map) o.put(k.toString(), JSONObject().put("press", v.press ?: "").put("double", v.double ?: "").put("long", v.long ?: ""))
        prefs(ctx).edit().putString("keymap", o.toString()).apply()
    }

    /** A readable name for a key, e.g. "Rote Taste" or "Netflix". */
    fun name(code: Int): String = when (code) {
        KeyEvent.KEYCODE_PROG_RED -> tr("Rote Taste")
        KeyEvent.KEYCODE_PROG_GREEN -> tr("Grüne Taste")
        KeyEvent.KEYCODE_PROG_YELLOW -> tr("Gelbe Taste")
        KeyEvent.KEYCODE_PROG_BLUE -> tr("Blaue Taste")
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> tr("Wiedergabe/Pause")
        KeyEvent.KEYCODE_CHANNEL_UP -> tr("Kanal hoch")
        KeyEvent.KEYCODE_CHANNEL_DOWN -> tr("Kanal runter")
        KeyEvent.KEYCODE_VOLUME_MUTE -> tr("Stumm")
        KeyEvent.KEYCODE_TV_INPUT -> tr("Eingang (Quelle)")
        KeyEvent.KEYCODE_GUIDE -> tr("Programmführer")
        KeyEvent.KEYCODE_SETTINGS -> tr("Einstellungen")
        KeyEvent.KEYCODE_BOOKMARK -> tr("Lesezeichen")
        KeyEvent.KEYCODE_CAPTIONS -> tr("Untertitel")
        KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST -> tr("Sprachsuche")
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
            .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
