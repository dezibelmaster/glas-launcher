package de.glas.smarthome

import java.util.Locale

enum class Kind { ROOM, THERMOSTAT, SWITCH, LIGHT, BLIND, SENSOR, ALARM, OTHER }

/** Radiator controller. Temperatures are raw FRITZ!Box values: 16..56 = 8..28 °C in half degrees, 253 = off, 254 = on. */
data class Hkr(
    val tist: Double?,
    val tsoll: Int,
    val absenk: Int,
    val komfort: Int,
    val windowOpen: Boolean,
    val windowEnd: Long,
    val boost: Boolean,
    val boostEnd: Long,
    val batteryLow: Boolean,
    val battery: Int?,
    val nextTime: Long,
    val nextTemp: Int,
    val error: Int,
    val summer: Boolean,
    val holiday: Boolean,
    val locked: Boolean,
) {
    val isOff get() = tsoll == OFF
    val isOn get() = tsoll == ON
    /** The radiator is warming up: target above the measured temperature, or fully on. */
    val heating get() = !summer && (isOn || boost || (tsoll in MIN..MAX && tist != null && tist < tsoll / 2.0 - .2))

    companion object {
        const val MIN = 16
        const val MAX = 56
        const val OFF = 253
        const val ON = 254
    }
}

data class Device(
    val id: String,
    val ain: String,
    val name: String,
    val product: String,
    val firmware: String,
    val present: Boolean,
    val isGroup: Boolean,
    val members: List<String>,
    val mask: Int,
    val temp: Double?,
    val humidity: Int?,
    val hkr: Hkr?,
    val switchOn: Boolean?,
    val switchLocked: Boolean,
    /** Watts, kilowatt hours, volts. */
    val power: Double?,
    val energy: Double?,
    val voltage: Double?,
    val onOff: Boolean?,
    val level: Int?,
    val blindMode: String?,
    val alert: Boolean?,
    val battery: Int?,
    val batteryLow: Boolean,
) {
    fun has(bit: Int) = mask and (1 shl bit) != 0

    val kind: Kind
        get() = when {
            isGroup -> Kind.ROOM
            hkr != null -> Kind.THERMOSTAT
            has(18) || blindMode != null -> Kind.BLIND
            has(2) || has(17) || (onOff != null && level != null && switchOn == null) -> Kind.LIGHT
            switchOn != null || onOff != null -> Kind.SWITCH
            temp != null || humidity != null -> Kind.SENSOR
            alert != null -> Kind.ALARM
            else -> Kind.OTHER
        }

    val isOn: Boolean? get() = switchOn ?: onOff
    val canSwitch get() = isOn != null
    val anyBatteryLow get() = batteryLow || hkr?.batteryLow == true
    val batteryLevel get() = hkr?.battery ?: battery
}

data class Template(val id: String, val ain: String, val name: String)

data class Snapshot(val devices: List<Device>, val templates: List<Template>)

object Fmt {
    private val de = Locale.GERMANY
    fun temp(c: Double?, unit: Boolean = true) = if (c == null) "–" else String.format(de, "%.1f", c) + if (unit) " °C" else "°"
    fun raw(raw: Int, unit: Boolean = true) = when (raw) {
        Hkr.OFF -> "Aus"
        Hkr.ON -> "An"
        in Hkr.MIN..Hkr.MAX -> temp(raw / 2.0, unit)
        else -> "–"
    }
    fun watts(w: Double) = when {
        w >= 1000 -> String.format(de, "%.2f kW", w / 1000)
        w >= 100 -> String.format(de, "%.0f W", w)
        else -> String.format(de, "%.1f W", w)
    }
    fun kwh(kwh: Double) = if (kwh >= 100) String.format(de, "%.0f kWh", kwh) else String.format(de, "%.2f kWh", kwh)
    fun volts(v: Double) = String.format(de, "%.0f V", v)
    fun time(epochSec: Long): String = java.text.SimpleDateFormat("HH:mm", de).format(java.util.Date(epochSec * 1000))
    fun dayTime(epochSec: Long): String {
        val d = java.util.Calendar.getInstance().apply { timeInMillis = epochSec * 1000 }
        val today = java.util.Calendar.getInstance()
        val tomorrow = (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
        fun same(a: java.util.Calendar, b: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            same(d, today) -> "heute " + time(epochSec)
            same(d, tomorrow) -> "morgen " + time(epochSec)
            else -> java.text.SimpleDateFormat("EE HH:mm", de).format(d.time)
        }
    }
    fun hkrError(code: Int) = when (code) {
        1 -> "Keine Adaptierung möglich. Ist der Regler richtig montiert?"
        2 -> "Ventilhub zu kurz oder Batterie zu schwach."
        3 -> "Ventil bewegt sich nicht. Ist es verklemmt?"
        4 -> "Die Installation wird vorbereitet."
        5 -> "Der Regler ist im Installationsmodus."
        6 -> "Der Regler passt sich an das Ventil an."
        else -> "Fehler $code am Regler."
    }
}

object Parser {
    fun devices(root: XNode): List<Device> = root.children
        .filter { it.name == "device" || it.name == "group" }
        .map { device(it) }

    private fun bool(n: XNode?, tag: String) = n?.text(tag) == "1"

    private fun device(n: XNode): Device {
        val h = n.child("hkr")
        val hkr = h?.let {
            Hkr(
                tist = it.int("tist")?.takeIf { v -> v in 0..120 }?.let { v -> v / 2.0 },
                tsoll = it.int("tsoll") ?: 255,
                absenk = it.int("absenk") ?: 255,
                komfort = it.int("komfort") ?: 255,
                windowOpen = bool(it, "windowopenactiv"),
                windowEnd = it.text("windowopenactiveendtime")?.toLongOrNull() ?: 0,
                boost = bool(it, "boostactive"),
                boostEnd = it.text("boostactiveendtime")?.toLongOrNull() ?: 0,
                batteryLow = bool(it, "batterylow"),
                battery = it.int("battery"),
                nextTime = it.path("nextchange", "endperiod")?.text?.trim()?.toLongOrNull() ?: 0,
                nextTemp = it.path("nextchange", "tchange")?.text?.trim()?.toIntOrNull() ?: 255,
                error = it.int("errorcode") ?: 0,
                summer = bool(it, "summeractive"),
                holiday = bool(it, "holidayactive"),
                locked = bool(it, "lock") || bool(it, "devicelock"),
            )
        }
        val sw = n.child("switch")
        val pm = n.child("powermeter")
        val lvl = n.child("levelcontrol")
        val isGroup = n.name == "group"
        return Device(
            id = n.attrs["id"] ?: n.attrs["identifier"] ?: "",
            ain = n.attrs["identifier"]?.trim() ?: "",
            name = n.text("name")?.ifBlank { null } ?: n.attrs["productname"] ?: "Gerät",
            product = n.attrs["productname"] ?: if (isGroup) "Gruppe" else "",
            firmware = n.attrs["fwversion"] ?: "",
            present = n.text("present") != "0",
            isGroup = isGroup,
            members = n.path("groupinfo", "members")?.text?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            mask = n.attrs["functionbitmask"]?.toIntOrNull() ?: 0,
            temp = n.child("temperature")?.int("celsius")?.let { it / 10.0 },
            humidity = n.child("humidity")?.int("rel_humidity")?.takeIf { it in 0..100 },
            hkr = hkr,
            switchOn = sw?.text("state")?.let { if (it.isEmpty()) null else it == "1" },
            switchLocked = bool(sw, "lock") || bool(sw, "devicelock"),
            power = pm?.text("power")?.toDoubleOrNull()?.let { it / 1000 },
            energy = pm?.text("energy")?.toDoubleOrNull()?.let { it / 1000 },
            voltage = pm?.text("voltage")?.toDoubleOrNull()?.let { it / 1000 },
            onOff = n.child("simpleonoff")?.text("state")?.let { if (it.isEmpty()) null else it == "1" },
            level = lvl?.int("levelpercentage"),
            blindMode = n.child("blind")?.text("mode") ?: if (n.child("blind") != null) "" else null,
            alert = n.child("alert")?.text("state")?.let { it == "1" },
            battery = n.int("battery"),
            batteryLow = n.text("batterylow") == "1",
        )
    }

    /**
     * `getbasicdevicestats`: comma separated values, newest first, temperatures in tenths of a degree,
     * "-" where nothing was measured. The 15-minute grid covers the last 24 hours.
     */
    fun stats(root: XNode): Stats? {
        val all = root.child("temperature")?.children?.filter { it.name == "stats" } ?: return null
        val s = all.firstOrNull { it.attrs["grid"] == "900" } ?: all.firstOrNull() ?: return null
        val values = s.text.trim().split(',').map { v -> v.trim().toDoubleOrNull()?.let { it / 10.0 } }
        if (values.isEmpty()) return null
        val grid = s.attrs["grid"]?.toIntOrNull() ?: 900
        val at = s.attrs["datatime"]?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
        return Stats(values.reversed(), grid, at)
    }

    fun templates(root: XNode): List<Template> = root.children.filter { it.name == "template" }.map {
        Template(it.attrs["id"] ?: "", it.attrs["identifier"] ?: "", it.text("name") ?: "Vorlage")
    }
}

/** Measured values at regular steps; [values] are oldest first, null where the device sent nothing. */
data class Stats(val values: List<Double?>, val stepSeconds: Int, val newestAt: Long) {
    fun timeOf(i: Int) = newestAt - (values.size - 1 - i).toLong() * stepSeconds
    val valid get() = values.count { it != null } >= 2
}

/** Settings of a radiator controller from the Smart Home REST API. Null fields are not reported by the box. */
data class HkrConfig(
    val offset: Double?,
    val offsetExternal: Boolean,
    val windowDuration: Int?,
    val windowSensitivity: String?,
    val windowExternal: Boolean,
    val lockLocal: Boolean?,
    val lockApi: Boolean?,
    val rangeEnabled: Boolean?,
    val rangeMin: Double?,
    val rangeMax: Double?,
    val adaptive: Boolean?,
    val stats: Stats?,
) {
    companion object {
        private fun org.json.JSONObject.boolOrNull(vararg names: String): Boolean? {
            for (n in names) if (has(n) && !isNull(n)) return optBoolean(n)
            return null
        }
        private fun org.json.JSONObject.doubleOrNull(n: String) = if (has(n) && !isNull(n)) optDouble(n).takeIf { !it.isNaN() } else null
        private fun org.json.JSONObject.intOrNull(n: String) = if (has(n) && !isNull(n)) optInt(n) else null

        fun parse(unit: org.json.JSONObject): HkrConfig {
            val t = unit.optJSONObject("interfaces")?.optJSONObject("thermostatInterface")
                ?: throw FritzException("Dieses Gerät hat keine Heizkörper-Einstellungen.")
            val off = t.optJSONObject("temperatureOffset")
            val win = t.optJSONObject("windowOpenMode")
            val range = t.optJSONObject("temperatureRangeLock")
            val stats = unit.optJSONObject("statistics")?.optJSONArray("temperatures")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("statisticsState") == "valid" && it.optString("period") == "day" }
                    ?.let { s ->
                        val v = s.optJSONArray("values") ?: return@let null
                        val list = (0 until v.length()).map { i -> if (v.isNull(i)) null else v.optDouble(i).takeIf { !it.isNaN() } }
                        Stats(list.reversed(), s.optInt("interval", 900).coerceAtLeast(1), System.currentTimeMillis() / 1000)
                    }
            }
            return HkrConfig(
                offset = off?.doubleOrNull("internalOffset"),
                offsetExternal = off?.optString("sensorMode") == "external",
                windowDuration = win?.intOrNull("internalDuration"),
                windowSensitivity = win?.optString("internalSensitivity")?.takeIf { it.isNotEmpty() && it != "unknown" },
                windowExternal = win?.optString("sensorMode") == "external",
                lockLocal = t.boolOrNull("lockedDeviceLocalEnabled", "isLockedDeviceLocal"),
                lockApi = t.boolOrNull("lockedDeviceApiEnabled", "isLockedDeviceApi"),
                rangeEnabled = range?.boolOrNull("temperatureLockEnabled", "isTemperatureLock"),
                rangeMin = range?.optJSONObject("minimum")?.doubleOrNull("celsius"),
                rangeMax = range?.optJSONObject("maximum")?.doubleOrNull("celsius"),
                adaptive = t.boolOrNull("adaptiveHeatingModeEnabled"),
                stats = stats,
            )
        }
    }
}
