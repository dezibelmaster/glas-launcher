package de.glas.smart

import android.content.Context

/** Settings and the last weather, kept on the watch. */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("glas_smart", Context.MODE_PRIVATE)

    var place: Place
        get() {
            val name = p.getString("place_name", null) ?: return Place.DEFAULT
            return Place(
                name,
                p.getString("place_region", "").orEmpty(),
                p.getFloat("place_lat", 0f).toDouble(),
                p.getFloat("place_lon", 0f).toDouble(),
            )
        }
        set(value) {
            p.edit()
                .putString("place_name", value.name)
                .putString("place_region", value.region)
                .putFloat("place_lat", value.lat.toFloat())
                .putFloat("place_lon", value.lon.toFloat())
                // The weather of the old place no longer fits
                .remove("forecast").remove("forecast_time")
                .apply()
        }

    /** Address of the TV in the home network; the default is the one in the launcher's README. */
    var tvHost: String
        get() = p.getString("tv_host", null) ?: "192.168.178.173"
        set(value) = p.edit().putString("tv_host", value).apply()

    /** The weather service's answer as received, and when. */
    var forecastJson: String?
        get() = p.getString("forecast", null)
        set(value) = p.edit().putString("forecast", value).apply()

    var forecastTime: Long
        get() = p.getLong("forecast_time", 0L)
        set(value) = p.edit().putLong("forecast_time", value).apply()
}
