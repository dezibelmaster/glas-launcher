package de.glas.launcher

import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The launcher's languages. Texts are written in German in the code and wrapped in tr();
 * the other languages are tables in assets/i18n/<code>.json that map the German text to theirs.
 */
object I18n {
    class Language(val code: String, val name: String)

    val languages = listOf(
        Language("de", "Deutsch"),
        Language("en", "English"),
        Language("fr", "Français"),
        Language("es", "Español"),
        Language("it", "Italiano"),
        Language("nl", "Nederlands"),
    )

    @Volatile var lang = "de"
        private set
    @Volatile private var table: Map<String, String> = emptyMap()

    val locale: Locale get() = Locale.forLanguageTag(lang)

    /** The language the TV itself is set to, if the launcher has it; English otherwise. */
    fun systemLanguage(): String {
        val sys = Resources.getSystem().configuration.locales[0].language
        return languages.firstOrNull { it.code == sys }?.code ?: "en"
    }

    /** Loads the chosen language (or the TV's). Call again after the choice changes. */
    fun init(ctx: Context) {
        val code = Prefs(ctx).language?.takeIf { c -> languages.any { it.code == c } } ?: systemLanguage()
        table = if (code == "de") emptyMap() else runCatching {
            val json = JSONObject(ctx.assets.open("i18n/$code.json").bufferedReader().use { it.readText() })
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
        lang = code
    }

    fun lookup(de: String) = table[de] ?: de

    fun nameOf(code: String?) = if (code == null) tr("Automatisch ({0})", languages.first { it.code == systemLanguage() }.name)
        else languages.firstOrNull { it.code == code }?.name ?: code

    /** A date format in the current language from a skeleton like "EEEEdMMMM". */
    fun dateFormat(skeleton: String) = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

/** The text in the current language. Placeholders {0}, {1} … are filled with args. */
fun tr(de: String, vararg args: Any?): String {
    var s = I18n.lookup(de)
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}

/** Loads the language before any screen, screensaver or service of the launcher starts. */
class GlasApp : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        RemoteServer.start(this)
    }
}
