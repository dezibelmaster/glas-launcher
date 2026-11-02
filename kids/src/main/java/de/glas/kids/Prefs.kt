package de.glas.kids

import android.content.Context

/** Everything Glas Kids remembers. Changed by the parents in the settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("kids", Context.MODE_PRIVATE)

    /** Kids mode on: only YouTube Kids (and the extra apps) may be opened. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var pinHash: String?
        get() = sp.getString("pin", null)
        set(v) = sp.edit().putString("pin", v).apply()

    /** Wrong PIN entries in a row, and until when (epoch ms) no PIN may be entered after too many. */
    var wrongPins: Int
        get() = sp.getInt("wrongPins", 0)
        set(v) = sp.edit().putInt("wrongPins", v).apply()
    var pinBlockedUntil: Long
        get() = sp.getLong("pinBlockedUntil", 0)
        set(v) = sp.edit().putLong("pinBlockedUntil", v).apply()

    /** Viewing time per day in minutes; 0 means no limit. */
    var dailyMinutes: Int
        get() = sp.getInt("dailyMinutes", 0)
        set(v) = sp.edit().putInt("dailyMinutes", v).apply()

    /** Times of day in which watching is allowed, e.g. "07:00-19:30"; null means all day. */
    var window: String?
        get() = sp.getString("window", null)
        set(v) = sp.edit().apply { if (v == null) remove("window") else putString("window", v) }.apply()

    /** How many minutes before the end the remaining time is shown; 0 never. */
    var warnMinutes: Int
        get() = sp.getInt("warnMinutes", 5)
        set(v) = sp.edit().putInt("warnMinutes", v).apply()

    /** Packages that are allowed besides YouTube Kids. */
    var extraApps: Set<String>
        get() = sp.getStringSet("extraApps", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("extraApps", v).apply()

    /** Viewing time counted so far on [usedDay] (yyyy-MM-dd), in ms. */
    var usedDay: String?
        get() = sp.getString("usedDay", null)
        set(v) = sp.edit().putString("usedDay", v).apply()
    var usedMs: Long
        get() = sp.getLong("usedMs", 0)
        set(v) = sp.edit().putLong("usedMs", v).apply()

    /** Extra time granted by a parent: until then (epoch ms) neither the daily limit nor the time window applies. */
    var grantedUntil: Long
        get() = sp.getLong("grantedUntil", 0)
        set(v) = sp.edit().putLong("grantedUntil", v).apply()

    /** Language code like "en"; null follows the TV's language. */
    var language: String?
        get() = sp.getString("language", null)
        set(v) = sp.edit().apply { if (v == null) remove("language") else putString("language", v) }.commit().let {}
}
