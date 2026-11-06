package de.glas.smart

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Kind(val title: String, val emoji: String) {
    LIGHT("Lampen", "💡"),
    PLUG("Steckdosen & Schalter", "🔌"),
    SENSOR("Sensoren", "🌡️"),
    OTHER("Sonstiges", "⚙️"),
}

class Device(
    val id: String,
    var name: String,
    val category: String,
    var online: Boolean,
    val uid: String,
    val product: String,
    val status: MutableMap<String, Any?>,
) {
    /** Name of the shared home the device comes from, if the account is only a member there. */
    var homeName = ""

    companion object {
        fun from(o: JSONObject) = Device(
            id = o.optString("id").ifBlank { o.optString("dev_id") },
            name = o.optString("name").ifBlank { o.optString("product_name", "Gerät") },
            category = o.optString("category"),
            online = if (o.has("online")) o.optBoolean("online") else o.optBoolean("is_online", true),
            uid = o.optString("uid"),
            product = o.optString("product_name"),
            status = statusMap(o.optJSONArray("status") ?: JSONArray()).toMutableMap(),
        ).also { it.homeName = o.optString("home_name") }

        fun statusMap(arr: JSONArray): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                m[s.optString("code")] = s.opt("value")
            }
            return m
        }

        private val lightCats = setOf("dj", "dd", "xdd", "fwd", "dc", "tgq", "tgkg", "gyd", "sxd", "tyndj", "fwl", "ykq")
        private val plugCats = setOf("cz", "pc", "kg", "tdq", "dlq", "qjdcz", "znjdq", "wkcz")
        private val sensorCats = setOf(
            "wsdcg", "mcs", "pir", "ywbj", "sj", "rqbj", "cobj", "co2bj", "hps", "ldcg", "zd", "pm25",
            "jwbj", "wnykq", "sos", "wkf", "qxj", "dgnbj",
        )
    }

    val kind: Kind
        get() = when {
            category in lightCats || "switch_led" in status -> Kind.LIGHT
            category in plugCats -> Kind.PLUG
            category in sensorCats -> Kind.SENSOR
            switchCodes().isNotEmpty() -> Kind.PLUG
            else -> Kind.OTHER
        }

    val emoji: String
        get() = when (category) {
            "dd", "fwd" -> "✨"
            "cz", "pc", "wkcz" -> "🔌"
            "kg", "tdq", "tgkg" -> "🔘"
            "mcs" -> "🚪"
            "pir", "hps" -> "🚶"
            "sj" -> "💧"
            "ywbj" -> "🔥"
            "cobj", "co2bj", "rqbj", "jwbj" -> "⚠️"
            "cl", "clkg" -> "🪟"
            "fs", "fsd" -> "🌀"
            "sp", "spsxj" -> "📷"
            "dghsxj" -> "🔔"
            "wk", "wkf" -> "🔥"
            "ms" -> "🔒"
            "kt" -> "❄️"
            else -> kind.emoji
        }

    /** Camera or video doorbell, which can show a live picture. */
    val isCamera: Boolean
        get() = category in setOf("sp", "dghsxj", "spsxj", "sp_wnq") || "basic_osd" in status || "sd_status" in status

    fun bool(code: String) = status[code] as? Boolean
    fun int(code: String) = (status[code] as? Number)?.toInt()

    /** Codes that switch the device on and off, in display order. */
    fun switchCodes(): List<String> {
        if (status["switch_led"] is Boolean) return listOf("switch_led")
        val numbered = status.keys.filter { Regex("switch_\\d+").matches(it) && status[it] is Boolean }
            .sortedBy { it.removePrefix("switch_").toInt() }
        return when {
            numbered.isNotEmpty() -> numbered
            status["switch"] is Boolean -> listOf("switch")
            status["switch_on"] is Boolean -> listOf("switch_on")
            else -> emptyList()
        }
    }

    /** On if any of its switches is on; null if it can't be switched. */
    val isOn: Boolean?
        get() {
            val codes = switchCodes()
            if (codes.isEmpty()) return null
            return codes.any { bool(it) == true }
        }

    /** Commands that switch every outlet/light of the device to [on]. */
    fun powerCommands(on: Boolean) = switchCodes().map { it to on as Any }

    /** Current colour of a light as ARGB, or warm white, for the little dot on its card. */
    fun lightColor(spec: Spec?): Int {
        val mode = status["work_mode"] as? String
        val code = if ("colour_data_v2" in status) "colour_data_v2" else "colour_data"
        if (mode == "colour") {
            val c = Colour.parse(status[code], spec?.fn(code))
            if (c != null) return android.graphics.Color.HSVToColor(floatArrayOf(c.h.toFloat(), c.s.toFloat().coerceAtLeast(.15f), 1f))
        }
        // White: blend from warm to cold along the colour temperature
        val tCode = if ("temp_value_v2" in status) "temp_value_v2" else "temp_value"
        val fn = spec?.fn(tCode)
        val t = int(tCode)
        val f = if (t != null && fn != null && fn.max > fn.min) (t - fn.min).toFloat() / (fn.max - fn.min) else .3f
        return blend(0xFFFFC46B.toInt(), 0xFFDDEBFF.toInt(), f)
    }

    /** Brightness in percent, or null. In colour mode the brightness is part of the colour. */
    fun brightnessPercent(spec: Spec?): Int? {
        val mode = status["work_mode"] as? String
        if (mode == "colour") {
            val code = if ("colour_data_v2" in status) "colour_data_v2" else "colour_data"
            Colour.parse(status[code], spec?.fn(code))?.let { return (it.v * 100).roundToInt() }
        }
        val code = if ("bright_value_v2" in status) "bright_value_v2" else if ("bright_value" in status) "bright_value"
        else if ("bright_value_1" in status) "bright_value_1" else return null
        val v = int(code) ?: return null
        val fn = spec?.fn(code)
        val max = fn?.max ?: if (code == "bright_value") 255 else 1000
        return (v * 100f / max).roundToInt().coerceIn(1, 100)
    }

    /** One line for the card: state plus the most useful reading. */
    fun summary(spec: Spec?): String {
        if (!online) return "Offline"
        val parts = ArrayList<String>()
        when (kind) {
            Kind.LIGHT -> {
                if (isOn == true) {
                    parts += "An"
                    brightnessPercent(spec)?.let { parts += "$it %" }
                } else parts += "Aus"
            }
            Kind.PLUG -> {
                val on = switchCodes().count { bool(it) == true }
                val n = switchCodes().size
                parts += when {
                    n > 1 && on > 0 && on < n -> "$on von $n an"
                    on > 0 -> "An"
                    else -> "Aus"
                }
                val w = reading("cur_power", spec)
                if (w != null && on > 0) parts += w
            }
            else -> {
                for (code in Labels.sensorOrder) {
                    if (code !in status) continue
                    parts += Labels.value(code, status[code], spec?.status(code))
                    if (parts.size >= 2) break
                }
                isOn?.let { parts.add(0, if (it) "An" else "Aus") }
            }
        }
        battery(spec)?.let { parts += "🔋 $it" }
        return parts.joinToString(" · ").ifEmpty { "Online" }
    }

    fun reading(code: String, spec: Spec?): String? =
        if (code in status) Labels.value(code, status[code], spec?.status(code)) else null

    private fun battery(spec: Spec?): String? {
        reading("battery_percentage", spec)?.let { return it }
        reading("va_battery", spec)?.let { return it }
        return when (status["battery_state"]) {
            "low" -> "schwach"
            else -> null
        }
    }
}

fun blend(a: Int, b: Int, f: Float): Int {
    val t = f.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).roundToInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** One data point of a device, as described by its specification. */
class Fn(val code: String, val type: String, val values: JSONObject) {
    val min get() = values.optInt("min", 0)
    val max get() = values.optInt("max", 100)
    val step get() = values.optInt("step", 1).coerceAtLeast(1)
    val scale get() = values.optInt("scale", 0)
    val unit: String get() = values.optString("unit", "")
    val range: List<String>
        get() {
            val arr = values.optJSONArray("range") ?: return emptyList()
            return (0 until arr.length()).map { arr.optString(it) }
        }
}

class Spec(val functions: Map<String, Fn>, val statuses: Map<String, Fn>) {
    fun fn(code: String) = functions[code] ?: statuses[code]
    fun status(code: String) = statuses[code] ?: functions[code]
    fun canSet(code: String) = code in functions

    fun toJson(): JSONObject {
        fun arr(m: Map<String, Fn>) = JSONArray().apply {
            m.values.forEach { put(JSONObject().put("code", it.code).put("type", it.type).put("values", it.values.toString())) }
        }
        return JSONObject().put("functions", arr(functions)).put("status", arr(statuses))
    }

    companion object {
        fun from(o: JSONObject): Spec {
            fun read(arr: JSONArray?): Map<String, Fn> {
                val m = LinkedHashMap<String, Fn>()
                if (arr == null) return m
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    val values = try {
                        JSONObject(f.optString("values", "{}").ifBlank { "{}" })
                    } catch (e: Exception) {
                        JSONObject()
                    }
                    m[f.optString("code")] = Fn(f.optString("code"), f.optString("type"), values)
                }
                return m
            }
            return Spec(read(o.optJSONArray("functions")), read(o.optJSONArray("status")))
        }
    }
}

/** Hue in degrees, saturation and value from 0 to 1, converted to and from the device's own ranges. */
data class Colour(val h: Int, val s: Double, val v: Double) {
    fun toJson(fn: Fn?): JSONObject {
        val sMax = fn?.values?.optJSONObject("s")?.optInt("max", 1000) ?: 1000
        val vMax = fn?.values?.optJSONObject("v")?.optInt("max", 1000) ?: 1000
        return JSONObject()
            .put("h", h.coerceIn(0, 360))
            .put("s", (s * sMax).roundToInt().coerceIn(0, sMax))
            .put("v", (v * vMax).roundToInt().coerceIn((vMax / 100).coerceAtLeast(1), vMax))
    }

    companion object {
        fun parse(raw: Any?, fn: Fn?): Colour? {
            val o = when (raw) {
                is JSONObject -> raw
                is String -> try {
                    JSONObject(raw)
                } catch (e: Exception) {
                    return null
                }
                else -> return null
            }
            if (!o.has("h")) return null
            // Old lights (colour_data) use 0-255, newer ones 0-1000
            val sMax = fn?.values?.optJSONObject("s")?.optInt("max") ?: if (fn?.code == "colour_data") 255 else 1000
            val vMax = fn?.values?.optJSONObject("v")?.optInt("max") ?: if (fn?.code == "colour_data") 255 else 1000
            return Colour(o.optInt("h"), o.optDouble("s", 0.0) / sMax, o.optDouble("v", 0.0) / vMax)
        }
    }
}

/** German names for data points and their values. */
object Labels {
    val sensorOrder = listOf(
        "va_temperature", "temp_current", "temp_indoor", "va_humidity", "humidity_value", "humidity_indoor",
        "doorcontact_state", "pir", "presence_state", "watersensor_state", "smoke_sensor_status",
        "gas_sensor_status", "co_status", "co2_value", "pm25_value", "cur_power",
    )

    private val names = mapOf(
        "switch_led" to "Licht",
        "switch" to "Schalter",
        "switch_on" to "Schalter",
        "work_mode" to "Modus",
        "bright_value" to "Helligkeit",
        "bright_value_v2" to "Helligkeit",
        "temp_value" to "Weißton",
        "temp_value_v2" to "Weißton",
        "colour_data" to "Farbe",
        "colour_data_v2" to "Farbe",
        "countdown" to "Timer",
        "countdown_1" to "Timer",
        "relay_status" to "Nach Stromausfall",
        "light_mode" to "Kontrollleuchte",
        "child_lock" to "Kindersicherung",
        "cur_power" to "Leistung",
        "cur_voltage" to "Spannung",
        "cur_current" to "Strom",
        "add_ele" to "Verbrauch",
        "va_temperature" to "Temperatur",
        "temp_current" to "Temperatur",
        "temp_indoor" to "Temperatur",
        "va_humidity" to "Luftfeuchtigkeit",
        "humidity_value" to "Luftfeuchtigkeit",
        "humidity_indoor" to "Luftfeuchtigkeit",
        "temp_unit_convert" to "Temperatureinheit",
        "doorcontact_state" to "Tür/Fenster",
        "pir" to "Bewegung",
        "presence_state" to "Anwesenheit",
        "watersensor_state" to "Wasser",
        "smoke_sensor_status" to "Rauch",
        "smoke_sensor_state" to "Rauch",
        "gas_sensor_status" to "Gas",
        "co_status" to "Kohlenmonoxid",
        "co2_value" to "CO₂",
        "pm25_value" to "Feinstaub PM2,5",
        "battery_percentage" to "Batterie",
        "va_battery" to "Batterie",
        "battery_state" to "Batterie",
        "temper_alarm" to "Sabotagealarm",
        "do_not_disturb" to "Nicht stören",
        "switch_backlight" to "Hintergrundbeleuchtung",
        "percent_control" to "Position",
        "control" to "Steuerung",
        "fan_speed" to "Lüfterstufe",
        "fan_speed_percent" to "Lüfterstufe",
        "temp_set" to "Solltemperatur",
        "mode" to "Modus",
        // Cameras and video doorbells
        "basic_indicator" to "Statusleuchte",
        "basic_flip" to "Bild um 180° drehen",
        "basic_osd" to "Zeitstempel im Bild",
        "basic_private" to "Privatmodus (Kamera aus)",
        "basic_nightvision" to "Nachtsicht",
        "basic_wdr" to "Gegenlichtausgleich (WDR)",
        "basic_anti_flicker" to "Flimmerschutz",
        "basic_device_volume" to "Lautstärke",
        "motion_switch" to "Bewegungserkennung",
        "motion_sensitivity" to "Empfindlichkeit Bewegung",
        "motion_tracking" to "Bewegung verfolgen",
        "motion_area_switch" to "Bewegungsbereich",
        "decibel_switch" to "Geräuscherkennung",
        "decibel_sensitivity" to "Empfindlichkeit Geräusch",
        "record_switch" to "Aufnahme auf SD-Karte",
        "record_mode" to "Aufnahmemodus",
        "sd_status" to "SD-Karte",
        "chime_ring_volume" to "Lautstärke Gong",
        "chime_ring_tune" to "Klingelton Gong",
        "wireless_electricity" to "Batterie",
        "wireless_powermode" to "Stromversorgung",
        "humanoid_filter" to "Nur Personen melden",
        "ipc_auto_siren" to "Sirene bei Bewegung",
        "siren_switch" to "Sirene",
    )

    // Enum values that only make sense for one data point, e.g. "0" for night vision means automatic
    private val enumsByCode = mapOf(
        "basic_nightvision" to mapOf("0" to "Automatisch", "1" to "Aus", "2" to "An"),
        "motion_sensitivity" to mapOf("0" to "Niedrig", "1" to "Mittel", "2" to "Hoch"),
        "decibel_sensitivity" to mapOf("0" to "Niedrig", "1" to "Hoch"),
        "record_mode" to mapOf("1" to "Nur bei Ereignis", "2" to "Durchgehend"),
        "basic_anti_flicker" to mapOf("0" to "Aus", "1" to "50 Hz", "2" to "60 Hz"),
        "wireless_powermode" to mapOf("0" to "Batterie", "1" to "Netzteil"),
    )

    fun enumLabel(code: String, v: String) = enumsByCode[code]?.get(v) ?: enumValue(v)

    private val enumValues = mapOf(
        "white" to "Weiß", "colour" to "Farbe", "scene" to "Szene", "music" to "Musik",
        "power_off" to "Aus", "power_on" to "An", "off" to "Aus", "on" to "An", "memory" to "Letzter Zustand",
        "last" to "Letzter Zustand", "none" to "Keine", "relay" to "Zeigt Zustand", "pos" to "Zeigt Gegenteil",
        "low" to "Schwach", "middle" to "Mittel", "high" to "Voll",
        "alarm" to "Alarm!", "normal" to "Normal", "presence" to "Anwesend",
        "open" to "Öffnen", "close" to "Schließen", "stop" to "Stopp",
        "c" to "°C", "f" to "°F", "auto" to "Automatisch", "manual" to "Manuell",
    )

    fun name(code: String): String {
        names[code]?.let { return it }
        Regex("switch_(\\d+)").matchEntire(code)?.let { return "Schalter ${it.groupValues[1]}" }
        Regex("countdown_(\\d+)").matchEntire(code)?.let { return "Timer ${it.groupValues[1]}" }
        return code.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun enumValue(v: String) = enumValues[v] ?: v.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun number(v: Double, decimals: Int): String = String.format(Locale.GERMANY, "%.${decimals}f", v)

    /** Readable value with unit, using the scale from the device's specification. */
    fun value(code: String, raw: Any?, fn: Fn?): String {
        when (code) {
            "doorcontact_state" -> return if (raw == true) "Offen" else "Geschlossen"
            "watersensor_state" -> return if (raw == "alarm" || raw == "1" || raw == true) "Wasser erkannt!" else "Trocken"
            "pir" -> return if (raw == "pir" || raw == true) "Bewegung" else "Keine Bewegung"
            "presence_state" -> return if (raw == "presence") "Anwesend" else "Niemand da"
            "sd_status" -> return when ((raw as? Number)?.toInt() ?: raw.toString().toIntOrNull()) {
                1 -> "In Ordnung"
                2 -> "Fehler"
                3 -> "Voll"
                4 -> "Wird formatiert"
                5 -> "Keine Karte"
                else -> raw.toString()
            }
            "smoke_sensor_status", "smoke_sensor_state", "gas_sensor_status", "co_status" ->
                return if (raw == "alarm" || raw == "1" || raw == true) "Alarm!" else "Normal"
        }
        return when (raw) {
            is Boolean -> if (raw) "An" else "Aus"
            is Number -> {
                val scale = fn?.scale ?: defaultScale(code)
                val v = raw.toDouble() / 10.0.pow(scale)
                val unit = fn?.unit?.takeIf { it.isNotBlank() } ?: defaultUnit(code)
                val shown = when {
                    code.startsWith("countdown") -> return duration(raw.toInt())
                    code.startsWith("bright_value") || code.startsWith("temp_value") ->
                        return "${(raw.toDouble() * 100 / (fn?.max ?: 1000)).roundToInt()} %"
                    else -> number(v, scale.coerceAtMost(2))
                }
                if (unit.isBlank()) shown else "$shown ${unit.replace("℃", "°C")}"
            }
            is String -> enumLabel(code, raw)
            null -> "–"
            else -> raw.toString()
        }
    }

    fun duration(seconds: Int): String = when {
        seconds <= 0 -> "Aus"
        seconds < 3600 -> "${(seconds + 59) / 60} min"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }

    // Used before the specification has loaded
    private fun defaultScale(code: String) = when (code) {
        "cur_power", "cur_voltage", "va_temperature", "temp_current" -> 1
        "add_ele" -> 3
        else -> 0
    }

    private fun defaultUnit(code: String) = when (code) {
        "cur_power" -> "W"
        "cur_voltage" -> "V"
        "cur_current" -> "mA"
        "add_ele" -> "kWh"
        "va_temperature", "temp_current", "temp_indoor" -> "°C"
        "va_humidity", "humidity_value", "humidity_indoor", "battery_percentage", "va_battery" -> "%"
        else -> ""
    }
}
