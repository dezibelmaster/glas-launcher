package de.glas.launcher

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Weather sources besides Open-Meteo, for when it is overloaded or chosen in the settings:
 * the Deutscher Wetterdienst through the free Bright Sky service (Germany only) and the
 * Norwegian weather service Met.no (worldwide). Both are free and need no account.
 * They give no UV maximum per day (Bright Sky) and no sunrise, so those are worked out here;
 * air quality and pollen still come from Open-Meteo's separate air-quality service.
 */
object WeatherSources {
    /** One step of a forecast, already in the TV's local time. */
    private class Step(
        val millis: Long,
        val date: String,
        val hour: Int,
        val temp: Double,
        val code: Int,
        val rain: Int?,
        val uv: Double? = null,
        val max: Double? = null,
        val min: Double? = null,
        val hourly: Boolean = true,
        val json: JSONObject? = null,
    )

    private val tz get() = TimeZone.getDefault()
    private fun local(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = tz }

    // ---- Deutscher Wetterdienst via Bright Sky ----

    fun brightSky(place: Place): WeatherApi.Forecast {
        val day = local("yyyy-MM-dd")
        val today = day.format(Date())
        val last = day.format(Date(System.currentTimeMillis() + 8 * 86_400_000L))
        val zone = tz.id
        val json = JSONObject(WeatherApi.get(
            "https://api.brightsky.dev/weather?lat=${place.lat}&lon=${place.lon}&date=$today&last_date=$last&tz=$zone",
        ))
        val arr = json.getJSONArray("weather")
        if (arr.length() == 0) throw IllegalStateException(tr("Keine Daten für diesen Ort"))
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        val steps = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val stamp = o.optString("timestamp")
            val millis = runCatching { iso.parse(stamp)!!.time }.getOrNull() ?: return@mapNotNull null
            val temp = o.optDouble("temperature").takeIf { !it.isNaN() } ?: return@mapNotNull null
            Step(
                millis = millis,
                date = stamp.take(10),
                hour = stamp.substring(11, 13).toInt(),
                temp = temp,
                code = brightSkyCode(o),
                rain = o.optDouble("precipitation_probability").takeIf { !it.isNaN() }?.roundToInt(),
                json = o,
            )
        }
        // Observations from the nearest weather station; the forecast hour if there are none
        val current = runCatching {
            JSONObject(WeatherApi.get("https://api.brightsky.dev/current_weather?lat=${place.lat}&lon=${place.lon}&tz=$zone"))
                .getJSONObject("weather")
        }.getOrNull()
        val hourNow = nearest(steps)
        val hourJson = hourNow.json ?: JSONObject()
        fun value(currentKey: String, forecastKey: String) =
            current?.optDouble(currentKey)?.takeIf { !it.isNaN() } ?: hourJson.optDouble(forecastKey).takeIf { !it.isNaN() }
        val temp = value("temperature", "temperature") ?: hourNow.temp
        val wind = value("wind_speed_10", "wind_speed") ?: 0.0
        val humidity = value("relative_humidity", "relative_humidity") ?: 0.0
        val code = current?.let { brightSkyCode(it) }?.takeIf { current.has("icon") && !current.isNull("icon") } ?: hourNow.code
        return build(
            place, steps, temp, code,
            feelsLike = feelsLike(temp, wind, humidity),
            humidity = humidity.roundToInt(),
            windKmh = wind.roundToInt(),
            windDir = (value("wind_direction_10", "wind_direction") ?: 0.0).roundToInt(),
            pressure = (value("pressure_msl", "pressure_msl") ?: 0.0).roundToInt(),
            source = tr("Deutscher Wetterdienst"),
        )
    }

    /** Bright Sky's icon as a WMO code; dry skies from the cloud cover, like Open-Meteo's. */
    private fun brightSkyCode(o: JSONObject): Int {
        val icon = o.optString("icon").takeUnless { o.isNull("icon") }.orEmpty()
        val rain = o.optDouble("precipitation").takeIf { !it.isNaN() } ?: 0.0
        val clouds = o.optDouble("cloud_cover").takeIf { !it.isNaN() }
        return when {
            icon == "thunderstorm" -> 95
            icon == "hail" -> 96
            icon == "snow" -> if (rain >= 2) 75 else 71
            icon == "sleet" -> 66
            icon == "rain" -> if (rain >= 4) 65 else if (rain >= 1) 63 else 61
            icon == "fog" -> 45
            clouds != null -> cloudCode(clouds)
            icon.startsWith("clear") -> 0
            icon.startsWith("partly") -> 2
            else -> 3
        }
    }

    // ---- Met.no (Yr) ----

    fun metNo(place: Place): WeatherApi.Forecast {
        val lat = "%.4f".format(Locale.ROOT, place.lat)
        val lon = "%.4f".format(Locale.ROOT, place.lon)
        val json = JSONObject(WeatherApi.get("https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=$lat&lon=$lon"))
        val series = json.getJSONObject("properties").getJSONArray("timeseries")
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val date = local("yyyy-MM-dd")
        val hourOf = local("HH")
        var first: JSONObject? = null
        val steps = (0 until series.length()).mapNotNull { i ->
            val o = series.getJSONObject(i)
            val millis = runCatching { utc.parse(o.getString("time"))!!.time }.getOrNull() ?: return@mapNotNull null
            val data = o.getJSONObject("data")
            val instant = data.getJSONObject("instant").getJSONObject("details")
            val next1 = data.optJSONObject("next_1_hours")
            val next6 = data.optJSONObject("next_6_hours")
            val next = next1 ?: next6 ?: data.optJSONObject("next_12_hours")
            val symbol = next?.optJSONObject("summary")?.optString("symbol_code").orEmpty()
            val details = next?.optJSONObject("details")
            if (first == null) first = instant
            Step(
                millis = millis,
                date = date.format(Date(millis)),
                hour = hourOf.format(Date(millis)).toInt(),
                temp = instant.optDouble("air_temperature"),
                code = metNoCode(symbol, instant.optDouble("cloud_area_fraction")),
                rain = details?.optDouble("probability_of_precipitation")?.takeIf { !it.isNaN() }?.roundToInt(),
                uv = instant.optDouble("ultraviolet_index_clear_sky").takeIf { !it.isNaN() },
                max = next6?.optJSONObject("details")?.optDouble("air_temperature_max")?.takeIf { !it.isNaN() },
                min = next6?.optJSONObject("details")?.optDouble("air_temperature_min")?.takeIf { !it.isNaN() },
                hourly = next1 != null,
            )
        }.filter { !it.temp.isNaN() }
        val now = first ?: throw IllegalStateException(tr("Keine Daten für diesen Ort"))
        val step = nearest(steps)
        val temp = now.optDouble("air_temperature")
        val wind = now.optDouble("wind_speed", 0.0) * 3.6
        val humidity = now.optDouble("relative_humidity", 0.0)
        return build(
            place, steps, temp, step.code,
            feelsLike = feelsLike(temp, wind, humidity),
            humidity = humidity.roundToInt(),
            windKmh = wind.roundToInt(),
            windDir = now.optDouble("wind_from_direction", 0.0).roundToInt(),
            pressure = now.optDouble("air_pressure_at_sea_level", 0.0).roundToInt(),
            source = "Met.no (Yr)",
        )
    }

    /** Met.no's symbol, e.g. "lightrainshowers_day", as a WMO code. */
    private fun metNoCode(symbol: String, clouds: Double): Int {
        val s = symbol.substringBefore('_')
        return when {
            s.contains("thunder") -> 95
            s.contains("sleet") -> 66
            s.contains("snowshowers") -> if (s.startsWith("heavy")) 86 else 85
            s.contains("snow") -> if (s.startsWith("heavy")) 75 else if (s.startsWith("light")) 71 else 73
            s.contains("rainshowers") -> if (s.startsWith("heavy")) 82 else if (s.startsWith("light")) 80 else 81
            s.contains("rain") -> if (s.startsWith("heavy")) 65 else if (s.startsWith("light")) 61 else 63
            s == "fog" -> 45
            s == "clearsky" -> 0
            s == "fair" -> 1
            s == "partlycloudy" -> 2
            s == "cloudy" -> 3
            !clouds.isNaN() -> cloudCode(clouds)
            else -> 3
        }
    }

    // ---- Shared ----

    private fun cloudCode(cover: Double) = when {
        cover < 15 -> 0
        cover < 40 -> 1
        cover < 75 -> 2
        else -> 3
    }

    /** The forecast step for the current hour (or the closest one). */
    private fun nearest(steps: List<Step>): Step {
        val now = System.currentTimeMillis()
        return steps.minByOrNull { kotlin.math.abs(it.millis - now) } ?: throw IllegalStateException(tr("Keine Daten für diesen Ort"))
    }

    /** Puts the steps together into the next 12 hours and 7 days, as Open-Meteo's are. */
    private fun build(
        place: Place, steps: List<Step>, temp: Double, code: Int,
        feelsLike: Double, humidity: Int, windKmh: Int, windDir: Int, pressure: Int, source: String,
    ): WeatherApi.Forecast {
        val nowMillis = System.currentTimeMillis()
        val clock = local("HH:mm")
        val byDate = steps.groupBy { it.date }
        val dates = byDate.keys.sorted().take(7)
        val days = dates.map { d ->
            val list = byDate.getValue(d)
            val sun = sunTimes(place.lat, place.lon, d)
            val daytime = list.filter { it.hour in 8..20 }.map { it.code }
            WeatherApi.Day(
                date = d,
                code = if (daytime.isNotEmpty()) WeatherApi.prevailing(daytime) else WeatherApi.prevailing(list.map { it.code }),
                max = (list.map { it.temp } + list.mapNotNull { it.max }).max().roundToInt(),
                min = (list.map { it.temp } + list.mapNotNull { it.min }).min().roundToInt(),
                rain = list.mapNotNull { it.rain }.maxOrNull() ?: 0,
                sunrise = sun?.first?.let { clock.format(Date(it)) } ?: "–",
                sunset = sun?.second?.let { clock.format(Date(it)) } ?: "–",
                uv = list.mapNotNull { it.uv }.maxOrNull(),
            )
        }
        fun isDay(millis: Long, date: String): Boolean {
            val sun = sunTimes(place.lat, place.lon, date) ?: return true
            return millis in sun.first..sun.second
        }
        val hourStart = nowMillis - nowMillis % 3_600_000L
        val hours = steps.filter { it.hourly && it.millis >= hourStart }.take(12).map {
            WeatherApi.Hour(clock.format(Date(it.millis)), it.temp.roundToInt(), it.code, isDay(it.millis, it.date), it.rain ?: 0)
        }
        val today = days.firstOrNull()
        val air = WeatherApi.airQuality(place)
        return WeatherApi.Forecast(
            now = WeatherNow(
                temp = temp.roundToInt(),
                code = code,
                isDay = isDay(nowMillis, local("yyyy-MM-dd").format(Date(nowMillis))),
                max = today?.max ?: temp.roundToInt(),
                min = today?.min ?: temp.roundToInt(),
                time = nowMillis,
            ),
            feelsLike = feelsLike.roundToInt(),
            humidity = humidity,
            windKmh = windKmh,
            windDir = windDir,
            pressure = pressure,
            hours = hours,
            days = days,
            aqi = air.aqi,
            pollen = air.pollen,
            source = source,
        )
    }

    /** Felt temperature: wind chill when cold and windy, heat index when hot and humid. */
    private fun feelsLike(t: Double, windKmh: Double, humidity: Double): Double = when {
        t <= 10 && windKmh > 4.8 -> {
            val v = windKmh.pow(0.16)
            13.12 + 0.6215 * t - 11.37 * v + 0.3965 * t * v
        }
        t >= 27 && humidity >= 40 -> {
            val f = t * 9 / 5 + 32
            val hi = -42.379 + 2.04901523 * f + 10.14333127 * humidity - 0.22475541 * f * humidity -
                0.00683783 * f * f - 0.05481717 * humidity * humidity + 0.00122874 * f * f * humidity +
                0.00085282 * f * humidity * humidity - 0.00000199 * f * f * humidity * humidity
            (hi - 32) * 5 / 9
        }
        else -> t
    }

    /**
     * Sunrise and sunset on [date] ("yyyy-MM-dd") as times in milliseconds, after the
     * "Almanac for Computers" method (accurate to about a minute); null in polar day or night.
     */
    fun sunTimes(lat: Double, lon: Double, date: String): Pair<Long, Long>? {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(date.take(4).toInt(), date.substring(5, 7).toInt() - 1, date.substring(8, 10).toInt())
        }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val midnightUtc = cal.timeInMillis
        fun rad(d: Double) = Math.toRadians(d)
        fun deg(r: Double) = Math.toDegrees(r)
        fun norm(v: Double, max: Double) = ((v % max) + max) % max
        fun utcHours(rising: Boolean): Double? {
            val lngHour = lon / 15
            val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24
            val m = 0.9856 * t - 3.289
            val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
            var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
            ra += floor(l / 90) * 90 - floor(ra / 90) * 90
            ra /= 15
            val sinDec = 0.39782 * sin(rad(l))
            val cosDec = cos(asin(sinDec))
            val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
            if (cosH > 1 || cosH < -1) return null
            val h = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15
            val tLocal = h + ra - 0.06571 * t - 6.622
            return norm(tLocal - lngHour, 24.0)
        }
        val rise = utcHours(true) ?: return null
        val set = utcHours(false) ?: return null
        val riseMillis = midnightUtc + (rise * 3_600_000).toLong()
        var setMillis = midnightUtc + (set * 3_600_000).toLong()
        if (setMillis < riseMillis) setMillis += 86_400_000L
        return riseMillis to setMillis
    }
}
