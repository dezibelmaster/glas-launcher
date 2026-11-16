package de.glas.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything the launcher remembers. Changed from the TV via the settings screen. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("glas", Context.MODE_PRIVATE)

    /** Dock apps as "package/activity"; null until the first start fills in the defaults. */
    var dock: List<String>?
        get() = sp.getString("dock", null)?.split('\n')?.filter { it.isNotBlank() }
        set(v) = sp.edit().apply { if (v == null) remove("dock") else putString("dock", v.joinToString("\n")) }.apply()

    /** Language code like "en"; null follows the TV's language. */
    var language: String?
        get() = sp.getString("language", null)
        set(v) = sp.edit().apply { if (v == null) remove("language") else putString("language", v) }.commit().let {}

    /** What the screensaver shows over the background. */
    var saverClock: Boolean
        get() = sp.getBoolean("saverClock", true)
        set(v) = sp.edit().putBoolean("saverClock", v).apply()
    var saverDate: Boolean
        get() = sp.getBoolean("saverDate", true)
        set(v) = sp.edit().putBoolean("saverDate", v).apply()
    var saverWeather: Boolean
        get() = sp.getBoolean("saverWeather", true)
        set(v) = sp.edit().putBoolean("saverWeather", v).apply()

    /** Version the launcher last ran as, to point out what is new after an update. */
    var seenVersion: String?
        get() = sp.getString("seenVersion", null)
        set(v) = sp.edit().putString("seenVersion", v).apply()

    /** Where the weather comes from first: "openmeteo", "dwd" or "metno". */
    var weatherSource: String
        get() = sp.getString("weatherSource", "openmeteo")!!.takeIf { it in WeatherApi.SOURCES } ?: "openmeteo"
        set(v) = sp.edit().putString("weatherSource", v).apply()

    /** Forgets the stored weather, so it is fetched again, e.g. from another source. */
    fun clearWeather() = sp.edit().apply { sp.all.keys.filter { it.startsWith("weather.") }.forEach { remove(it) } }.apply()

    /** Whether the inputs tile was put into the dock once; after that it is the user's to move or remove. */
    var inputsTileAdded: Boolean
        get() = sp.getBoolean("inputsTileAdded", false)
        set(v) = sp.edit().putBoolean("inputsTileAdded", v).apply()

    /** Painted landscape chosen by hand; null lets them follow the time of day. */
    var landscape: String?
        get() = sp.getString("landscape", null)
        set(v) = sp.edit().apply { if (v == null) remove("landscape") else putString("landscape", v) }.apply()

    /** Reddit community for photo backgrounds, e.g. "EarthPorn". */
    var redditSub: String
        get() = sp.getString("redditSub", null) ?: "EarthPorn"
        set(v) = sp.edit().putString("redditSub", v).apply()

    /** Reddit photo chosen by hand (its post id); null lets them change by themselves. */
    var redditPost: String?
        get() = sp.getString("redditPost", null)
        set(v) = sp.edit().apply { if (v == null) remove("redditPost") else putString("redditPost", v) }.apply()

    /** Names given to apps by hand, by component ("package/activity"). */
    var names: Map<String, String>
        get() = runCatching {
            val o = org.json.JSONObject(sp.getString("names", "{}")!!)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        set(v) = sp.edit().putString("names", org.json.JSONObject(v).toString()).apply()

    /** Height of the large clock chosen by hand, per background picture (fraction of the screen from the top). */
    var clockSpots: Map<String, Float>
        get() = runCatching {
            val o = org.json.JSONObject(sp.getString("clockSpots", "{}")!!)
            o.keys().asSequence().associateWith { o.getDouble(it).toFloat() }
        }.getOrDefault(emptyMap())
        set(v) = sp.edit().putString("clockSpots", org.json.JSONObject(v.mapValues { it.value.toDouble() }).toString()).apply()

    /** How milky the glass of windows (control centre, menus) is: 0 clear … 3 very milky. */
    var windowFrost: Int
        get() = sp.getInt("windowFrost", 1)
        set(v) = sp.edit().putInt("windowFrost", v).apply()

    /** Packages that need the PIN to open. */
    var locked: Set<String>
        get() = sp.getStringSet("locked", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("locked", v).apply()

    var pinHash: String?
        get() = sp.getString("pin", null)
        set(v) = sp.edit().putString("pin", v).apply()

    var protectSettings: Boolean
        get() = sp.getBoolean("protectSettings", false)
        set(v) = sp.edit().putBoolean("protectSettings", v).apply()

    /** "Alle Apps" in the dock needs the PIN. */
    var lockAllApps: Boolean
        get() = sp.getBoolean("lockAllApps", false)
        set(v) = sp.edit().putBoolean("lockAllApps", v).apply()

    /** "weather", "image" or "video". */
    var background: String
        get() = sp.getString("background", "weather")!!
        set(v) = sp.edit().putString("background", v).apply()

    /** "realistic", "modern" or "abstract". */
    var weatherStyle: String
        get() = sp.getString("weatherStyle", "realistic")!!
        set(v) = sp.edit().putString("weatherStyle", v).apply()

    var imageUri: String?
        get() = sp.getString("imageUri", null)
        set(v) = sp.edit().putString("imageUri", v).apply()

    var videoUri: String?
        get() = sp.getString("videoUri", null)
        set(v) = sp.edit().putString("videoUri", v).apply()

    /** Glass strength: 0 light, 1 medium, 2 dark. */
    var glass: Int
        get() = sp.getInt("glass", 2)
        set(v) = sp.edit().putInt("glass", v).apply()

    /** How often the frosted glass picks up a video's picture, per second; 0 = once (still picture). */
    var glassVideoRate: Int
        get() = sp.getInt("glassVideoRate", 2)
        set(v) = sp.edit().putInt("glassVideoRate", v).apply()

    /** Minutes without input before the launcher shows its screensaver; 0 = off. */
    var idleMinutes: Int
        get() = sp.getInt("idleMinutes", 5)
        set(v) = sp.edit().putInt("idleMinutes", v).apply()

    /** Background extension and the chosen wallpaper; [extUri] null means all videos in turn. */
    var extAuthority: String?
        get() = sp.getString("extAuthority", null)
        set(v) = sp.edit().putString("extAuthority", v).apply()

    var extUri: String?
        get() = sp.getString("extUri", null)
        set(v) = sp.edit().putString("extUri", v).apply()

    var extType: String
        get() = sp.getString("extType", "video")!!
        set(v) = sp.edit().putString("extType", v).apply()

    /** Projectivy plugin as background, as a flattened component name. */
    var plugin: String?
        get() = sp.getString("plugin", null)
        set(v) = sp.edit().putString("plugin", v).apply()

    /** Plugin wallpaper chosen by hand, which then stays; null switches by itself. */
    var pluginPin: String?
        get() = sp.getString("pluginPin", null)
        set(v) = sp.edit().putString("pluginPin", v).apply()

    /** Minutes between two plugin wallpapers. */
    var pluginMinutes: Int
        get() = sp.getInt("pluginMinutes", 10)
        set(v) = sp.edit().putInt("pluginMinutes", v).apply()

    /** Top of the home screen: "bar" (slim status bar) or "classic" (large clock and weather card). */
    var topStyle: String
        get() = sp.getString("topStyle", "bar")!!
        set(v) = sp.edit().putString("topStyle", v).apply()

    /** Status bar folded away to just the time in the corner. */
    var statusHidden: Boolean
        get() = sp.getBoolean("statusHidden", false)
        set(v) = sp.edit().putBoolean("statusHidden", v).apply()

    /** Favourite videos and pictures of plugins and extensions, by address. */
    var favorites: Set<String>
        get() = sp.getStringSet("favorites", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("favorites", v).apply()

    /** Switch only between favourites instead of all items. */
    var favoritesOnly: Boolean
        get() = sp.getBoolean("favoritesOnly", false)
        set(v) = sp.edit().putBoolean("favoritesOnly", v).apply()

        /** Time windows of apps, package to "06:00-20:00". */
    var timeWindows: Map<String, String>
        get() = sp.getStringSet("timeWindows", emptySet())!!.mapNotNull { e ->
            e.split('|').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        set(v) = sp.edit().putStringSet("timeWindows", v.map { "${it.key}|${it.value}" }.toSet()).apply()

    /** GitHub repository ("owner/repo") the launcher looks in for updates. */
    /** GitHub project to look for new versions in; the Glas Launcher's own unless changed. */
    var updateRepo: String
        get() = sp.getString("updateRepo", null) ?: DEFAULT_UPDATE_REPO
        set(v) = sp.edit().apply { if (v == DEFAULT_UPDATE_REPO) remove("updateRepo") else putString("updateRepo", v) }.apply()

    /** The Glas Browser app was put into the dock once; after that the user decides. */
    var browserInDock: Boolean
        get() = sp.getBoolean("glasBrowserInDock", false)
        set(v) = sp.edit().putBoolean("glasBrowserInDock", v).apply()

    /** Free memory automatically when the screensaver starts. */
    var autoFreeMemory: Boolean
        get() = sp.getBoolean("autoFreeMemory", false)
        set(v) = sp.edit().putBoolean("autoFreeMemory", v).apply()

    /** Weather places; the first is the main place that drives the background. */
    var places: List<Place>
        get() {
            val raw = sp.getString("places", null) ?: return emptyList()
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Place(o.getString("name"), o.optString("region"), o.getDouble("lat"), o.getDouble("lon"))
            }
        }
        set(v) {
            val arr = JSONArray()
            for (p in v) arr.put(JSONObject().put("name", p.name).put("region", p.region).put("lat", p.lat).put("lon", p.lon))
            sp.edit().putString("places", arr.toString()).apply()
        }

    /** The place for the weather background; null until one has been chosen on the first start. */
    val mainPlace: Place? get() = places.firstOrNull()

    fun saveWeather(place: Place, w: WeatherNow) {
        val o = JSONObject()
            .put("temp", w.temp).put("code", w.code).put("day", w.isDay)
            .put("max", w.max).put("min", w.min).put("time", w.time)
        sp.edit().putString("weather.${place.key}", o.toString()).apply()
    }

    fun cachedWeather(place: Place? = mainPlace): WeatherNow? {
        if (place == null) return null
        val o = sp.getString("weather.${place.key}", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return WeatherNow(o.getInt("temp"), o.getInt("code"), o.getBoolean("day"), o.getInt("max"), o.getInt("min"), o.getLong("time"))
    }

    companion object {
        const val DEFAULT_UPDATE_REPO = "dezibelmaster/glas-launcher"

        /**
         * Earlier versions showed Meerbusch until another place was chosen. Keep it for
         * those who already used the launcher, so their weather doesn't vanish after the update.
         */
        fun migrate(context: Context) {
            val sp = context.getSharedPreferences("glas", Context.MODE_PRIVATE)
            if (sp.contains("places") || !(sp.contains("dock") || sp.contains("seenVersion"))) return
            Prefs(context).places = listOf(Place.MEERBUSCH)
        }
    }
}
