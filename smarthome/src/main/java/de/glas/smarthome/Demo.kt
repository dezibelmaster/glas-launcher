package de.glas.smarthome

/** A made-up home to try the app without a FRITZ!Box. Changes stay until the app is closed. */
class DemoBackend : Backend {
    private val now get() = System.currentTimeMillis() / 1000

    private fun hkr(ist: Double, soll: Int, battery: Int = 80, window: Boolean = false, low: Boolean = false) = Hkr(
        tist = ist, tsoll = soll, absenk = 33, komfort = 42, windowOpen = window, windowEnd = if (window) now + 900 else 0,
        boost = false, boostEnd = 0, batteryLow = low, battery = battery,
        nextTime = now + 3 * 3600, nextTemp = 33, error = 0, summer = false, holiday = false, locked = false,
    )

    private fun device(
        id: String, name: String, product: String, mask: Int, hkr: Hkr? = null, temp: Double? = hkr?.tist,
        humidity: Int? = null, switchOn: Boolean? = null, power: Double? = null, energy: Double? = null,
        onOff: Boolean? = null, level: Int? = null, blind: String? = null, alert: Boolean? = null,
        group: List<String> = emptyList(), battery: Int? = null,
    ) = Device(
        id = id, ain = "demo $id", name = name, product = product, firmware = "7.60", present = true,
        isGroup = group.isNotEmpty(), members = group, mask = mask, temp = temp, humidity = humidity, hkr = hkr,
        switchOn = switchOn, switchLocked = false, power = power, energy = energy, voltage = if (power != null) 231.4 else null,
        onOff = onOff ?: switchOn, level = level, blindMode = blind, alert = alert, battery = battery, batteryLow = false,
    )

    private var list = mutableListOf(
        device("900", "Wohnzimmer", "Gruppe", 1 shl 6, hkr(20.0, 40), group = listOf("16", "17")),
        device("16", "Heizkörper Fenster", "FRITZ!Smart Thermo 301", 1 shl 6, hkr(20.0, 40, battery = 90)),
        device("17", "Heizkörper Sofa", "FRITZ!Smart Thermo 301", 1 shl 6, hkr(20.5, 40, battery = 70)),
        device("18", "Heizkörper Büro", "FRITZ!DECT 301", 1 shl 6, hkr(20.0, 36)),
        device("19", "Heizkörper Schlafzimmer", "FRITZ!DECT 301", 1 shl 6, hkr(21.0, 48, battery = 60)),
        device("20", "Heizkörper Küche", "FRITZ!DECT 302", 1 shl 6, hkr(21.0, 33, battery = 15, low = true)),
        device("21", "HK Bad", "FRITZ!Smart Thermo 301", 1 shl 6, hkr(22.0, 44)),
        device("22", "Heizkörper Esszimmer", "FRITZ!DECT 301", 1 shl 6, hkr(22.0, 42, window = true)),
        device("30", "FRITZ!Smart Control 440", "FRITZ!Smart Control 440", (1 shl 8) or (1 shl 20), temp = 21.5, humidity = 51, battery = 100),
        device("40", "Fernseher", "FRITZ!Smart Energy 200", (1 shl 9) or (1 shl 7), switchOn = true, power = 86.3, energy = 142.75),
        device("41", "Kaffeemaschine", "FRITZ!Smart Energy 200", (1 shl 9) or (1 shl 7), switchOn = false, power = 0.0, energy = 38.12),
        device("50", "Stehlampe", "FRITZ!DECT 500", (1 shl 2) or (1 shl 15) or (1 shl 16), onOff = true, level = 70),
        device("60", "Rollladen Terrasse", "Rollotron DECT 1213", (1 shl 18) or (1 shl 16), level = 0, blind = "manuell"),
    )

    private val templates = listOf(
        Template("1", "demo-t1", "Alle Heizkörper Komfort"),
        Template("2", "demo-t2", "Nachtabsenkung"),
        Template("3", "demo-t3", "Urlaub"),
    )

    private fun update(id: String, f: (Device) -> Device) {
        val ids = list.firstOrNull { it.id == id }?.members.orEmpty() + id
        list = list.map { if (it.id in ids) f(it) else it }.toMutableList()
    }

    override fun load(): Snapshot {
        Thread.sleep(350)
        // Rooms slowly move towards their target temperature
        list = list.map { d ->
            val h = d.hkr ?: return@map d
            val target = when (h.tsoll) { Hkr.ON -> 28.0; Hkr.OFF -> 16.0; else -> h.tsoll / 2.0 }
            val ist = h.tist ?: return@map d
            val next = when {
                h.windowOpen -> ist
                ist < target - .25 -> ist + .5
                ist > target + .25 && !h.boost -> ist - .5
                else -> ist
            }
            d.copy(hkr = h.copy(tist = next), temp = next)
        }.toMutableList()
        return Snapshot(list.toList(), templates)
    }

    override fun setTemp(d: Device, raw: Int) = update(d.id) { it.copy(hkr = it.hkr?.copy(tsoll = raw)) }
    override fun setOn(d: Device, on: Boolean) = update(d.id) {
        it.copy(
            switchOn = it.switchOn?.let { on }, onOff = it.onOff?.let { on },
            power = it.power?.let { if (on) 86.3 else 0.0 },
        )
    }
    override fun setLevel(d: Device, percent: Int) = update(d.id) { it.copy(level = percent, onOff = it.onOff?.let { percent > 0 }) }
    override fun blind(d: Device, target: String) = update(d.id) {
        it.copy(level = when (target) { "open" -> 0; "close" -> 100; else -> it.level })
    }
    override fun boost(d: Device, minutes: Int) = update(d.id) {
        it.copy(hkr = it.hkr?.copy(boost = minutes > 0, boostEnd = if (minutes > 0) now + minutes * 60 else 0))
    }
    override fun window(d: Device, minutes: Int) = update(d.id) {
        it.copy(hkr = it.hkr?.copy(windowOpen = minutes > 0, windowEnd = if (minutes > 0) now + minutes * 60 else 0))
    }
    override fun stats(d: Device): Stats? {
        Thread.sleep(250)
        val base = d.hkr?.tist ?: d.temp ?: return null
        val now = System.currentTimeMillis() / 1000
        // A day with a cooler night and a warm evening
        val values = (0 until 96).map { i ->
            val hour = ((now - (95 - i) * 900L) / 3600.0 + 1) % 24
            val night = if (hour < 6 || hour > 23) -1.5 else 0.0
            val evening = if (hour in 17.0..22.0) .5 else 0.0
            Math.round((base + night + evening + Math.sin(i / 7.0) * .3) * 2) / 2.0
        }
        return Stats(values, 900, now)
    }

    private val configs = HashMap<String, HkrConfig>()

    override fun config(d: Device): HkrConfig {
        Thread.sleep(250)
        return configs.getOrPut(d.id) {
            HkrConfig(offset = 0.0, offsetExternal = false, windowDuration = 10, windowSensitivity = "high", windowExternal = false,
                lockLocal = false, lockApi = false, rangeEnabled = d.id == "19", rangeMin = 16.0, rangeMax = 23.0, adaptive = true, stats = null)
        }
    }

    override fun setConfig(d: Device, thermostat: org.json.JSONObject) {
        var c = config(d)
        thermostat.optJSONObject("temperatureOffset")?.let { c = c.copy(offset = it.optDouble("internalOffset", c.offset ?: 0.0)) }
        thermostat.optJSONObject("windowOpenMode")?.let {
            c = c.copy(windowDuration = it.optInt("internalDuration", c.windowDuration ?: 10),
                windowSensitivity = it.optString("internalSensitivity", c.windowSensitivity ?: "medium"))
        }
        if (thermostat.has("lockedDeviceLocalEnabled")) c = c.copy(lockLocal = thermostat.getBoolean("lockedDeviceLocalEnabled"))
        if (thermostat.has("lockedDeviceApiEnabled")) c = c.copy(lockApi = thermostat.getBoolean("lockedDeviceApiEnabled"))
        if (thermostat.has("adaptiveHeatingModeEnabled")) c = c.copy(adaptive = thermostat.getBoolean("adaptiveHeatingModeEnabled"))
        thermostat.optJSONObject("temperatureRangeLock")?.let { r ->
            c = c.copy(rangeEnabled = r.optBoolean("temperatureLockEnabled", c.rangeEnabled ?: false),
                rangeMin = r.optJSONObject("minimum")?.optDouble("celsius") ?: c.rangeMin,
                rangeMax = r.optJSONObject("maximum")?.optDouble("celsius") ?: c.rangeMax)
        }
        configs[d.id] = c
    }

    override fun apply(t: Template) {
        val raw = when (t.ain) { "demo-t1" -> 42; "demo-t2" -> 33; else -> 32 }
        list = list.map { it.copy(hkr = it.hkr?.copy(tsoll = raw)) }.toMutableList()
    }
}
