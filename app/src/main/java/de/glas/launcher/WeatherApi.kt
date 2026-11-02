package de.glas.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** [code] is a WMO weather code; [time] is when it was fetched, in milliseconds. */
data class WeatherNow(val temp: Int, val code: Int, val isDay: Boolean, val max: Int, val min: Int, val time: Long)

/** A place for the weather; [region] is shown to tell places with the same name apart. */
data class Place(val name: String, val region: String, val lat: Double, val lon: Double) {
    val key get() = "%.3f,%.3f".format(java.util.Locale.ROOT, lat, lon)

    companion object {
        val MEERBUSCH = Place("Meerbusch", "Nordrhein-Westfalen, Deutschland", 51.26, 6.69)
    }
}

/** Weather from Open-Meteo (free, no account, no ads). */
object WeatherApi {
    private const val MAX_AGE = 15 * 60_000L

    /** Cached weather if it is fresh, otherwise fetches it; [done] runs on the main thread. */
    fun load(context: Context, place: Place, done: (WeatherNow?) -> Unit) {
        val prefs = Prefs(context)
        val cached = prefs.cachedWeather(place)
        if (cached != null && System.currentTimeMillis() - cached.time < MAX_AGE) return done(cached)
        Thread {
            val fresh = try { fetch(context, place) } catch (e: Exception) { null }
            if (fresh != null) prefs.saveWeather(place, fresh)
            Handler(Looper.getMainLooper()).post { done(fresh ?: cached) }
        }.start()
    }

    /** The weather sources in the order to try them: the chosen one first, the others if it fails. */
    private fun order(ctx: Context): List<String> {
        val chosen = Prefs(ctx).weatherSource
        return listOf(chosen) + SOURCES.filter { it != chosen }
    }

    val SOURCES = listOf("openmeteo", "dwd", "metno")

    fun sourceName(id: String) = when (id) {
        "dwd" -> tr("Deutscher Wetterdienst")
        "metno" -> "Met.no (Yr)"
        else -> tr("Automatisch (Open-Meteo)")
    }

    /** Current weather from the first source that answers. Blocking; call from a background thread. */
    fun fetch(ctx: Context, place: Place): WeatherNow {
        var error: Exception? = null
        for (source in order(ctx)) {
            try {
                return when (source) {
                    "dwd" -> WeatherSources.brightSky(place).now
                    "metno" -> WeatherSources.metNo(place).now
                    else -> fetchOpenMeteo(place)
                }
            } catch (e: Exception) {
                error = e
            }
        }
        throw error ?: IllegalStateException()
    }

    private fun fetchOpenMeteo(place: Place): WeatherNow {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}&current=temperature_2m,weather_code,is_day,cloud_cover_low,cloud_cover_mid,cloud_cover_high&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
        val json = JSONObject(get(url))
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        return WeatherNow(
            temp = current.getDouble("temperature_2m").roundToInt(),
            code = skyCode(
                current.getInt("weather_code"),
                current.optDouble("cloud_cover_low", 0.0),
                current.optDouble("cloud_cover_mid", 0.0),
                current.optDouble("cloud_cover_high", 0.0),
            ),
            isDay = current.getInt("is_day") == 1,
            max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            time = System.currentTimeMillis(),
        )
    }

    class Hour(val time: String, val temp: Int, val code: Int, val isDay: Boolean, val rain: Int)
    class Day(val date: String, val code: Int, val max: Int, val min: Int, val rain: Int, val sunrise: String, val sunset: String, val uv: Double?)
    class Forecast(
        val now: WeatherNow,
        val feelsLike: Int,
        val humidity: Int,
        val windKmh: Int,
        val windDir: Int,
        val pressure: Int,
        val hours: List<Hour>,
        val days: List<Day>,
        val aqi: Int?,
        val pollen: Map<String, Double?>,
        /** Where the weather comes from, e.g. "Open-Meteo". */
        val source: String = "Open-Meteo",
    )

    /** Full forecast for the weather view: now, the next 12 hours, 7 days, air quality and pollen. Blocking. */
    class Loaded(val forecast: Forecast, val offline: Boolean, val error: String?)

    /**
     * The forecast, tried three times; if the weather service can't be reached, the last one
     * received for [place] with its time. Throws only if there is neither.
     */
    fun forecast(ctx: Context, place: Place): Loaded {
        val file = File(ctx.cacheDir, "forecast-" + place.key.replace(',', '_') + ".json")
        var error: Exception? = null
        val sources = order(ctx)
        for (source in sources) {
            // The chosen source gets a second chance; the others are only the way out
            val attempts = if (source == sources.first()) 2 else 1
            for (attempt in 0 until attempts) {
                try {
                    val f = when (source) {
                        "dwd" -> WeatherSources.brightSky(place)
                        "metno" -> WeatherSources.metNo(place)
                        else -> {
                            val text = get(forecastUrl(place))
                            parseForecast(text, place, System.currentTimeMillis()).also {
                                runCatching { file.writeText(text) }
                            }
                        }
                    }
                    return Loaded(f, offline = false, error = null)
                } catch (e: Exception) {
                    error = e
                    if (attempt < attempts - 1) Thread.sleep(2_000L)
                }
            }
        }
        val reason = error?.let { it.message ?: it.javaClass.simpleName }
        if (file.exists()) {
            runCatching { parseForecast(file.readText(), place, file.lastModified(), withAir = false) }.getOrNull()
                ?.let { return Loaded(it, offline = true, error = reason) }
        }
        throw error ?: IllegalStateException()
    }

    private fun forecastUrl(place: Place) = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m,wind_direction_10m,surface_pressure,cloud_cover_low,cloud_cover_mid,cloud_cover_high&hourly=temperature_2m,weather_code,precipitation_probability,is_day,cloud_cover_low,cloud_cover_mid,cloud_cover_high&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max&forecast_days=7&timezone=auto"

    private fun parseForecast(text: String, place: Place, time: Long, withAir: Boolean = true): Forecast {
        val json = JSONObject(text)
        val c = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        fun hourCode(i: Int) = skyCode(
            hourly.getJSONArray("weather_code").optInt(i, 2),
            hourly.getJSONArray("cloud_cover_low").optDouble(i, 0.0),
            hourly.getJSONArray("cloud_cover_mid").optDouble(i, 0.0),
            hourly.getJSONArray("cloud_cover_high").optDouble(i, 0.0),
        )
        // Daytime hours (8 to 20 o'clock) of each date, for the weather that prevails that day
        val daytime = (0 until times.length()).filter { times.getString(it).substring(11, 13).toInt() in 8..20 }
            .groupBy({ times.getString(it).take(10) }, { hourCode(it) })
        val days = (0 until daily.getJSONArray("time").length()).map { i ->
            val date = daily.getJSONArray("time").getString(i)
            Day(
                date = date,
                code = daytime[date]?.let { prevailing(it) } ?: daily.getJSONArray("weather_code").optInt(i, 2),
                max = daily.getJSONArray("temperature_2m_max").optDouble(i, 0.0).roundToInt(),
                min = daily.getJSONArray("temperature_2m_min").optDouble(i, 0.0).roundToInt(),
                rain = daily.getJSONArray("precipitation_probability_max").optInt(i, 0),
                sunrise = daily.getJSONArray("sunrise").optString(i).substringAfter('T'),
                sunset = daily.getJSONArray("sunset").optString(i).substringAfter('T'),
                uv = daily.getJSONArray("uv_index_max").optDouble(i, Double.NaN).takeIf { !it.isNaN() },
            )
        }
        // Hourly values start at midnight; begin with the current hour
        val nowHour = c.getString("time").take(13)
        val start = (0 until times.length()).firstOrNull { times.getString(it).take(13) == nowHour } ?: 0
        val hours = (start until minOf(start + 12, times.length())).map { i ->
            Hour(
                time = times.getString(i).substringAfter('T'),
                temp = hourly.getJSONArray("temperature_2m").optDouble(i, 0.0).roundToInt(),
                code = hourCode(i),
                isDay = hourly.getJSONArray("is_day").optInt(i, 1) == 1,
                rain = hourly.getJSONArray("precipitation_probability").optInt(i, 0),
            )
        }
        val now = WeatherNow(
            // A value can be missing now and then; then the view still opens
            temp = c.optDouble("temperature_2m", 0.0).let { if (it.isNaN()) 0 else it.roundToInt() },
            code = skyCode(c.optInt("weather_code", 2), c.optDouble("cloud_cover_low", 0.0), c.optDouble("cloud_cover_mid", 0.0), c.optDouble("cloud_cover_high", 0.0)),
            isDay = c.optInt("is_day", 1) == 1,
            max = days.firstOrNull()?.max ?: 0,
            min = days.firstOrNull()?.min ?: 0,
            time = time,
        )
        // Air quality and pollen come from a separate service; the view works without them
        val air = if (withAir) airQuality(place) else Air(null, emptyPollen())
        return Forecast(
            now = now,
            feelsLike = c.optDouble("apparent_temperature", 0.0).roundToInt(),
            humidity = c.optInt("relative_humidity_2m", 0),
            windKmh = c.optDouble("wind_speed_10m", 0.0).roundToInt(),
            windDir = c.optInt("wind_direction_10m", 0),
            pressure = c.optDouble("surface_pressure", 0.0).roundToInt(),
            hours = hours,
            days = days,
            aqi = air.aqi,
            pollen = air.pollen,
        )
    }

    class Air(val aqi: Int?, val pollen: Map<String, Double?>)

    fun emptyPollen(): Map<String, Double?> = linkedMapOf(tr("Birke") to null, tr("Gräser") to null, tr("Beifuß") to null)

    /** Air quality and pollen come from a separate Open-Meteo service; the view works without them. */
    fun airQuality(place: Place): Air {
        val air = runCatching {
            JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.lat}&longitude=${place.lon}&current=european_aqi,birch_pollen,grass_pollen,mugwort_pollen&timezone=auto")).getJSONObject("current")
        }.getOrNull() ?: return Air(null, emptyPollen())
        fun pollen(key: String) = air.optDouble(key).takeIf { !it.isNaN() }
        return Air(
            air.optInt("european_aqi", -1).takeIf { it >= 0 },
            linkedMapOf(tr("Birke") to pollen("birch_pollen"), tr("Gräser") to pollen("grass_pollen"), tr("Beifuß") to pollen("mugwort_pollen")),
        )
    }

    /**
     * The weather that occurs most often in a day's hours, as a code for its symbol.
     * On a tie the worse weather wins, so a half rainy day still shows rain.
     */
    fun prevailing(codes: List<Int>): Int {
        val counts = codes.groupingBy { skyFor(it) }.eachCount()
        val sky = counts.maxWithOrNull(compareBy<Map.Entry<Sky, Int>> { it.value }.thenBy { it.key.ordinal })?.key ?: Sky.PARTLY
        return when (sky) {
            Sky.CLEAR -> 0
            Sky.PARTLY -> 2
            Sky.OVERCAST -> 3
            Sky.FOG -> 45
            Sky.RAIN -> 61
            Sky.SNOW -> 71
            Sky.STORM -> 95
        }
    }

    /** A weather warning; [official] ones come from the Deutscher Wetterdienst. */
    class Warning(val title: String, val detail: String, val official: Boolean)

    /**
     * Current warnings for a place. In Germany the official ones of the Deutscher Wetterdienst
     * (via the free Bright Sky service); elsewhere, or if that fails, derived from the forecast
     * of the next 12 hours. Blocking.
     */
    fun warnings(place: Place): List<Warning> {
        val official = runCatching {
            val json = JSONObject(get("https://api.brightsky.dev/alerts?lat=${place.lat}&lon=${place.lon}"))
            // Bright Sky only covers Germany; outside there is no location
            if (json.isNull("location")) null
            else {
                val alerts = json.optJSONArray("alerts")
                (0 until (alerts?.length() ?: 0)).map { i ->
                    val a = alerts!!.getJSONObject(i)
                    // Bright Sky has the DWD texts in German and English
                    val l = if (I18n.lang == "de") "de" else "en"
                    val event = a.optString("event_$l").ifBlank { a.optString("headline_$l") }
                    Warning(prettify(event), a.optString("description_$l"), true)
                }
            }
        }.getOrNull()
        if (official != null) return official

        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}&hourly=weather_code,wind_gusts_10m,temperature_2m,precipitation&forecast_hours=12&timezone=auto"
        val hourly = runCatching { JSONObject(get(url)).getJSONObject("hourly") }.getOrNull() ?: return emptyList()
        val n = hourly.getJSONArray("time").length()
        val codes = (0 until n).map { hourly.getJSONArray("weather_code").optInt(it) }
        val gust = (0 until n).maxOfOrNull { hourly.getJSONArray("wind_gusts_10m").optDouble(it, 0.0) } ?: 0.0
        val icy = (0 until n).any { hourly.getJSONArray("precipitation").optDouble(it, 0.0) > 0 && hourly.getJSONArray("temperature_2m").optDouble(it, 9.0) <= .5 }
        val note = tr("In den nächsten 12 Stunden erwartet, berechnet aus der Vorhersage.")
        return buildList {
            if (codes.any { it in 95..99 }) add(Warning(tr("Gewitter"), note, false))
            when {
                gust >= 100 -> add(Warning(tr("Orkanartige Böen bis {0} km/h", gust.roundToInt()), note, false))
                gust >= 75 -> add(Warning(tr("Sturmböen bis {0} km/h", gust.roundToInt()), note, false))
                gust >= 60 -> add(Warning(tr("Windböen bis {0} km/h", gust.roundToInt()), note, false))
            }
            if (icy || codes.any { it == 66 || it == 67 }) add(Warning(tr("Glätte"), note, false))
            if (codes.any { it == 65 || it == 82 }) add(Warning(tr("Starkregen"), note, false))
            if (codes.any { it == 75 || it == 86 }) add(Warning(tr("Starker Schneefall"), note, false))
        }
    }

    /** "STURMBÖEN" from the DWD becomes "Sturmböen". */
    private fun prettify(text: String) = text.lowercase(java.util.Locale.GERMANY)
        .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(java.util.Locale.GERMANY) } }

    /** Finds places by name via the Open-Meteo geocoding service. Blocking. */
    fun search(name: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=10&language=${I18n.lang}&format=json&name=" +
            URLEncoder.encode(name.trim(), "UTF-8")
        val results = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val region = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
            Place(r.getString("name"), region, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    /** Met.no asks every app to name itself and where to find it. */
    internal fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "GlasLauncher/2 github.com/dezibelmaster/glas-launcher")
        try {
            val code = conn.responseCode
            if (code != 200) {
                // Open-Meteo says why in a "reason" field
                val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                val reason = body?.let { runCatching { JSONObject(it).optString("reason") }.getOrNull() }.orEmpty()
                throw java.io.IOException("HTTP $code" + if (reason.isNotBlank()) ": $reason" else "")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Open-Meteo calls the sky overcast when thin cirrus high up covers it, although it
     * looks blue from the ground. For the dry codes 0–3 the cloud layers are weighed
     * instead: low clouds fully, mid clouds mostly, high clouds only slightly.
     * Rain, snow, fog and thunderstorms keep their code.
     */
    private fun skyCode(code: Int, low: Double, mid: Double, high: Double): Int {
        if (code !in 0..3) return code
        val clear = (1 - low / 100) * (1 - .8 * mid / 100) * (1 - .3 * high / 100)
        val cover = (1 - clear) * 100
        return when {
            cover < 15 -> 0
            cover < 40 -> 1
            cover < 75 -> 2
            else -> 3
        }
    }

    /** German label for a WMO weather code. */
    fun describe(code: Int): String = when (code) {
        0 -> tr("Klar")
        1 -> tr("Überwiegend klar")
        2 -> tr("Leicht bewölkt")
        3 -> tr("Bedeckt")
        45, 48 -> tr("Nebel")
        in 51..57 -> tr("Nieselregen")
        in 61..67 -> tr("Regen")
        in 71..77 -> tr("Schneefall")
        in 80..82 -> tr("Regenschauer")
        85, 86 -> tr("Schneeschauer")
        in 95..99 -> tr("Gewitter")
        else -> "–"
    }

    fun skyFor(code: Int): Sky = when (code) {
        0, 1 -> Sky.CLEAR
        2 -> Sky.PARTLY
        3 -> Sky.OVERCAST
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.PARTLY
    }
}
