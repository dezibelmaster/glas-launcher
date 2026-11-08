package de.glas.smart

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.tiles.TileService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** A place for the weather; [region] tells places with the same name apart. */
data class Place(val name: String, val region: String, val lat: Double, val lon: Double) {
    companion object {
        val DEFAULT = Place("Meerbusch", "Nordrhein-Westfalen, Deutschland", 51.26, 6.69)
    }
}

class Hour(val time: String, val temp: Int, val code: Int, val isDay: Boolean, val rain: Int)
class Day(val date: LocalDate, val code: Int, val max: Int, val min: Int, val rain: Int)

/** Warnings derived from the forecast for the next 24 hours. */
enum class Warning(val label: Int) {
    THUNDER(R.string.warn_thunder),
    STORM(R.string.warn_storm),
    ICE(R.string.warn_ice),
    HEAVY_RAIN(R.string.warn_heavy_rain),
    SNOW(R.string.warn_snow),
    HEAT(R.string.warn_heat),
}

/** [code] is a WMO weather code; [time] is when it was received, in milliseconds. */
class Forecast(
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val feelsLike: Int,
    val humidity: Int,
    val wind: Int,
    val gusts: Int,
    val max: Int,
    val min: Int,
    val sunrise: String,
    val sunset: String,
    val hours: List<Hour>,
    val days: List<Day>,
    val warnings: List<Warning>,
    val time: Long,
)

/** Weather from Open-Meteo (free, no account, no ads), the same service the launcher uses. */
object Weather {
    private fun url(p: Place) = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m,wind_gusts_10m,cloud_cover_low,cloud_cover_mid,cloud_cover_high" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,wind_gusts_10m,is_day,cloud_cover_low,cloud_cover_mid,cloud_cover_high" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
        "&forecast_days=7&timezone=auto"

    /** Blocking. */
    fun fetchText(place: Place): String = get(url(place))

    fun parse(text: String, time: Long): Forecast {
        val json = JSONObject(text)
        val c = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val h = json.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        fun arr(name: String) = h.getJSONArray(name)
        fun hourCode(i: Int) = skyCode(
            arr("weather_code").optInt(i, 2),
            arr("cloud_cover_low").optDouble(i, 0.0),
            arr("cloud_cover_mid").optDouble(i, 0.0),
            arr("cloud_cover_high").optDouble(i, 0.0),
        )
        fun d(v: Double) = if (v.isNaN()) 0 else v.roundToInt()

        // Daytime hours (8 to 20 o'clock) of each date, for the weather that prevails that day
        val daytime = (0 until times.length()).filter { times.getString(it).substring(11, 13).toInt() in 8..20 }
            .groupBy({ times.getString(it).take(10) }, { hourCode(it) })
        val dates = daily.getJSONArray("time")
        val days = (0 until dates.length()).map { i ->
            val date = dates.getString(i)
            Day(
                date = LocalDate.parse(date),
                code = daytime[date]?.let { prevailing(it) } ?: daily.getJSONArray("weather_code").optInt(i, 2),
                max = d(daily.getJSONArray("temperature_2m_max").optDouble(i, 0.0)),
                min = d(daily.getJSONArray("temperature_2m_min").optDouble(i, 0.0)),
                rain = daily.getJSONArray("precipitation_probability_max").optInt(i, 0),
            )
        }

        // Hourly values start at midnight; begin with the current hour
        val nowHour = c.getString("time").take(13)
        val start = (0 until times.length()).firstOrNull { times.getString(it).take(13) == nowHour } ?: 0
        val hours = (start until minOf(start + 12, times.length())).map { i ->
            Hour(
                time = times.getString(i).substringAfter('T'),
                temp = d(arr("temperature_2m").optDouble(i, 0.0)),
                code = hourCode(i),
                isDay = arr("is_day").optInt(i, 1) == 1,
                rain = arr("precipitation_probability").optInt(i, 0),
            )
        }

        val warnings = mutableSetOf<Warning>()
        for (i in start until minOf(start + 24, times.length())) {
            val code = arr("weather_code").optInt(i, 0)
            val temp = arr("temperature_2m").optDouble(i, 10.0)
            val rain = arr("precipitation").optDouble(i, 0.0)
            if (code in 95..99) warnings += Warning.THUNDER
            if (arr("wind_gusts_10m").optDouble(i, 0.0) >= 65) warnings += Warning.STORM
            if (code in listOf(56, 57, 66, 67) || (temp <= 0.5 && rain > 0.1 && code !in 71..77 && code !in 85..86)) warnings += Warning.ICE
            if (rain >= 10) warnings += Warning.HEAVY_RAIN
            if (code in listOf(73, 75, 86)) warnings += Warning.SNOW
            if (temp >= 32) warnings += Warning.HEAT
        }

        val today = days.firstOrNull()
        return Forecast(
            temp = d(c.optDouble("temperature_2m", 0.0)),
            code = skyCode(c.optInt("weather_code", 2), c.optDouble("cloud_cover_low", 0.0), c.optDouble("cloud_cover_mid", 0.0), c.optDouble("cloud_cover_high", 0.0)),
            isDay = c.optInt("is_day", 1) == 1,
            feelsLike = d(c.optDouble("apparent_temperature", 0.0)),
            humidity = c.optInt("relative_humidity_2m", 0),
            wind = d(c.optDouble("wind_speed_10m", 0.0)),
            gusts = d(c.optDouble("wind_gusts_10m", 0.0)),
            max = today?.max ?: 0,
            min = today?.min ?: 0,
            sunrise = daily.getJSONArray("sunrise").optString(0).substringAfter('T'),
            sunset = daily.getJSONArray("sunset").optString(0).substringAfter('T'),
            hours = hours,
            days = days,
            warnings = Warning.entries.filter { it in warnings },
            time = time,
        )
    }

    /**
     * High thin clouds hardly dim the sky, but the weather code counts them like low ones.
     * Works out from the cloud layers how cloudy it looks (as in the launcher).
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

    /** The weather of a day: a thunderstorm always counts, rain only if it lasts a while. */
    private fun prevailing(codes: List<Int>): Int {
        codes.filter { it >= 95 }.maxOrNull()?.let { return it }
        fun mostCommon(list: List<Int>) = list.groupingBy { it }.eachCount().maxBy { it.value }.key
        val wet = codes.filter { it >= 45 }
        val dry = codes.filter { it < 45 }
        if (wet.size >= 3 || dry.isEmpty()) return mostCommon(wet.ifEmpty { codes })
        return dry.average().roundToInt().coerceIn(0, 3)
    }

    /** Places with that name from Open-Meteo's place search. Blocking. */
    fun search(name: String): List<Place> {
        val lang = Locale.getDefault().language
        val text = get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name, "UTF-8")}&count=8&language=$lang")
        val results = JSONObject(text).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            Place(
                name = r.getString("name"),
                region = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                lat = r.getDouble("latitude"),
                lon = r.getDouble("longitude"),
            )
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "GlasSmart/1 github.com/dezibelmaster/glas-launcher")
        try {
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun describe(code: Int): Int = when (code) {
        0 -> R.string.w_clear
        1 -> R.string.w_mostly_clear
        2 -> R.string.w_partly
        3 -> R.string.w_overcast
        45, 48 -> R.string.w_fog
        in 51..57 -> R.string.w_drizzle
        in 61..67 -> R.string.w_rain
        in 71..77 -> R.string.w_snow
        in 80..82 -> R.string.w_showers
        85, 86 -> R.string.w_snow_showers
        in 95..99 -> R.string.w_thunder
        else -> R.string.w_partly
    }

    fun icon(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "☁️"
    }
}

/** The weather shown in the app and the tile; kept on the watch so it shows at once. */
object WeatherStore {
    private const val MAX_AGE = 15 * 60_000L
    private val main = Handler(Looper.getMainLooper())

    var forecast by mutableStateOf<Forecast?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** Set when the last attempt failed; the last weather received stays shown. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The stored weather, if any. */
    fun cached(ctx: Context): Forecast? {
        val prefs = Prefs(ctx)
        val text = prefs.forecastJson ?: return null
        return runCatching { Weather.parse(text, prefs.forecastTime) }.getOrNull()
    }

    /** Fresh weather, fetched if the stored one is older than [maxAge]. Blocking; null if there is none at all. */
    fun get(ctx: Context, maxAge: Long = MAX_AGE): Forecast? {
        val prefs = Prefs(ctx)
        if (prefs.forecastJson != null && System.currentTimeMillis() - prefs.forecastTime < maxAge) return cached(ctx)
        return try {
            val text = Weather.fetchText(prefs.place)
            val now = System.currentTimeMillis()
            Weather.parse(text, now).also {
                prefs.forecastJson = text
                prefs.forecastTime = now
            }
        } catch (e: Exception) {
            cached(ctx)
        }
    }

    /** Shows the stored weather and fetches new weather in the background if it is old or [force] is set. */
    fun load(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (forecast == null) forecast = cached(app)
        val prefs = Prefs(app)
        val fresh = prefs.forecastJson != null && System.currentTimeMillis() - prefs.forecastTime < MAX_AGE
        if (loading || (fresh && !force)) return
        loading = true
        Thread {
            val place = prefs.place
            val result = runCatching {
                val text = Weather.fetchText(place)
                val now = System.currentTimeMillis()
                Weather.parse(text, now).also {
                    // The place was changed meanwhile: this weather belongs to the old one
                    if (prefs.place != place) return@runCatching null
                    prefs.forecastJson = text
                    prefs.forecastTime = now
                }
            }
            main.post {
                loading = false
                if (result.getOrNull() == null && result.isSuccess) return@post load(app, force = true)
                result.onSuccess {
                    forecast = it
                    error = null
                    runCatching { TileService.getUpdater(app).requestUpdate(WeatherTile::class.java) }
                }.onFailure { error = it.message ?: it.javaClass.simpleName }
            }
        }.start()
    }

    /** After the place changed: forget the old weather and fetch the new. */
    fun reset(ctx: Context) {
        forecast = null
        error = null
        load(ctx, force = true)
    }
}
