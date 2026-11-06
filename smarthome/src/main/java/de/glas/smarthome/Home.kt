package de.glas.smarthome

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Backend {
    fun load(): Snapshot
    fun setTemp(d: Device, raw: Int)
    fun setOn(d: Device, on: Boolean)
    fun setLevel(d: Device, percent: Int)
    fun blind(d: Device, target: String)
    /** Minutes of boost or window-open; 0 ends it. */
    fun boost(d: Device, minutes: Int)
    fun window(d: Device, minutes: Int)
    fun apply(t: Template)
    /** Temperatures of the last 24 hours, oldest first. */
    fun stats(d: Device): Stats?
    /** Settings of a radiator controller (REST API, FRITZ!OS 8.20+). */
    fun config(d: Device): HkrConfig
    /** Changes some of them; [thermostat] holds only the fields to change, as in `thermostatInterface`. */
    fun setConfig(d: Device, thermostat: org.json.JSONObject)
}

class FritzBackend(config: FritzClient.Config) : Backend {
    private val c = FritzClient(config)
    /** The box is reached over the address for away (MyFRITZ!). */
    val remote get() = c.remoteActive
    private var templates: List<Template> = emptyList()
    private var templatesAt = 0L

    override fun load(): Snapshot {
        val devices = Parser.devices(XNode.parse(c.aha("getdevicelistinfos")))
        // Templates rarely change; ask every five minutes. Older FRITZ!OS does not know them at all.
        if (SystemClock.elapsedRealtime() - templatesAt > 300_000 || templatesAt == 0L) {
            templates = runCatching { Parser.templates(XNode.parse(c.aha("gettemplatelistinfos"))) }.getOrDefault(templates)
            templatesAt = SystemClock.elapsedRealtime()
        }
        return Snapshot(devices, templates)
    }

    override fun setTemp(d: Device, raw: Int) {
        c.aha("sethkrtsoll", d.ain, mapOf("param" to raw.toString()))
    }

    override fun setOn(d: Device, on: Boolean) {
        if (d.switchOn != null) c.aha(if (on) "setswitchon" else "setswitchoff", d.ain)
        else c.aha("setsimpleonoff", d.ain, mapOf("onoff" to if (on) "1" else "0"))
    }

    override fun setLevel(d: Device, percent: Int) {
        c.aha("setlevelpercentage", d.ain, mapOf("level" to percent.coerceIn(0, 100).toString()))
    }

    override fun blind(d: Device, target: String) {
        c.aha("setblind", d.ain, mapOf("target" to target))
    }

    private fun end(minutes: Int) = if (minutes <= 0) "0" else (System.currentTimeMillis() / 1000 + minutes * 60L).toString()

    override fun boost(d: Device, minutes: Int) {
        c.aha("sethkrboost", d.ain, mapOf("endtimestamp" to end(minutes)))
    }

    override fun window(d: Device, minutes: Int) {
        c.aha("sethkrwindowopen", d.ain, mapOf("endtimestamp" to end(minutes)))
    }

    override fun apply(t: Template) {
        c.aha("applytemplate", t.ain)
    }

    override fun stats(d: Device): Stats? {
        val aha = runCatching { Parser.stats(XNode.parse(c.aha("getbasicdevicestats", d.ain))) }
        aha.getOrNull()?.takeIf { it.valid }?.let { return it }
        // Some boxes only report the history through the REST API
        if (d.hkr != null && !d.isGroup) runCatching { config(d).stats }.getOrNull()?.takeIf { it.valid }?.let { return it }
        aha.exceptionOrNull()?.let { throw it }
        return null
    }

    /** Unit UID of the REST API; equals the AIN for radiator controllers, otherwise found in the unit list. */
    private val uids = HashMap<String, String>()

    private fun unit(d: Device): org.json.JSONObject {
        uids[d.ain]?.let { return org.json.JSONObject(c.rest("GET", "configuration/units/" + path(it))) }
        val direct = runCatching { org.json.JSONObject(c.rest("GET", "configuration/units/" + path(d.ain))) }.getOrNull()
        if (direct != null && direct.has("interfaces")) { uids[d.ain] = d.ain; return direct }
        val list = org.json.JSONArray(c.rest("GET", "configuration/units"))
        val compact = d.ain.replace(" ", "")
        for (i in 0 until list.length()) {
            val u = list.getJSONObject(i)
            val ain = u.optString("ain").replace(" ", "")
            if (ain == compact && u.optJSONObject("interfaces")?.has("thermostatInterface") == true) {
                uids[d.ain] = u.getString("UID")
                return org.json.JSONObject(c.rest("GET", "configuration/units/" + path(u.getString("UID"))))
            }
        }
        throw FritzException("Die FRITZ!Box kennt keine Einstellungen für „${d.name}“.")
    }

    private fun path(uid: String) = java.net.URLEncoder.encode(uid, "UTF-8").replace("+", "%20")

    override fun config(d: Device): HkrConfig = HkrConfig.parse(unit(d))

    override fun setConfig(d: Device, thermostat: org.json.JSONObject) {
        val uid = uids[d.ain] ?: run { unit(d); uids[d.ain] ?: d.ain }
        val body = org.json.JSONObject().put("interfaces", org.json.JSONObject().put("thermostatInterface", thermostat))
        c.rest("PUT", "configuration/units/" + path(uid), body.toString())
    }
}

/** State of the home on the screen, loaded on one background thread so the FRITZ!Box gets one request at a time. */
object Home {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var key = ""
    var backend: Backend? = null
        private set
    var devices: List<Device> = emptyList()
        private set
    var templates: List<Template> = emptyList()
        private set
    var updated = 0L
        private set
    var error: String? = null
        private set
    var loading = false
        private set
    /** Last answer came over the address for away. */
    var remote = false
        private set
    /** Values just set on the phone win over the box for a few seconds, until the box has caught up. */
    private val overrides = HashMap<String, Pair<Long, Device>>()
    val listeners = LinkedHashSet<() -> Unit>()
    var onError: ((String) -> Unit)? = null

    private var store: Store? = null
    private var pinsTried = false

    fun configure(store: Store) {
        this.store = store
        val k = when {
            store.demo -> "demo"
            store.configured -> "${store.host}|${store.remote}|${store.pins}|${store.user}|${store.password.hashCode()}"
            else -> ""
        }
        if (k == key) return
        key = k
        devices = emptyList(); templates = emptyList(); error = null; updated = 0; overrides.clear(); loading = false
        backend = when {
            store.demo -> DemoBackend()
            store.configured -> FritzBackend(FritzClient.Config(store.host, store.user, store.password, store.remote, store.pins))
            else -> null
        }
        changed()
    }

    private fun changed() = listeners.toList().forEach { it() }

    private fun message(t: Throwable) = (t as? FritzException)?.message ?: (t.message ?: t.javaClass.simpleName)

    fun refresh() {
        val b = backend ?: return
        if (loading) return
        loading = true
        changed()
        io.execute {
            val r = runCatching { b.load() }
            main.post {
                if (b !== backend) return@post
                loading = false
                r.onSuccess { s ->
                    val now = SystemClock.elapsedRealtime()
                    overrides.entries.removeAll { now - it.value.first > 6000 }
                    devices = s.devices.map { overrides[it.id]?.second ?: it }
                    templates = s.templates
                    updated = System.currentTimeMillis()
                    remote = (b as? FritzBackend)?.remote == true
                    error = null
                    rememberCertificates()
                }.onFailure { error = message(it) }
                changed()
            }
        }
    }

    /**
     * At home, with an address for away but no certificate remembered yet (set up with version 1.5):
     * read the box's certificates once in the background. They take effect the next time the screen opens.
     */
    private fun rememberCertificates() {
        val s = store ?: return
        if (pinsTried || s.demo || remote || s.remote.isBlank() || s.pins.isNotEmpty()) return
        pinsTried = true
        val host = s.host
        val away = s.remote
        io.execute {
            val pins = FritzClient.readPins(host, away)
            if (pins.isNotEmpty()) main.post { if (s.host == host && s.remote == away) s.pins = pins }
        }
    }

    fun device(id: String) = devices.firstOrNull { it.id == id }

    /** Shows [d] right away, before the box confirms it. */
    fun local(d: Device) {
        overrides[d.id] = SystemClock.elapsedRealtime() to d
        devices = devices.map { if (it.id == d.id) d else it }
        changed()
    }

    /** Runs [work] on the FRITZ!Box thread and hands the result (or the error text) back on the main thread. */
    fun <T> fetch(work: (Backend) -> T, done: (T?, String?) -> Unit) {
        val b = backend ?: return
        io.execute {
            val r = runCatching { work(b) }
            main.post { done(r.getOrNull(), r.exceptionOrNull()?.let { message(it) }) }
        }
    }

    fun send(refreshAfter: Long = 1500, action: (Backend) -> Unit) {
        val b = backend ?: return
        io.execute {
            val r = runCatching { action(b) }
            main.post {
                r.exceptionOrNull()?.let {
                    overrides.clear()
                    onError?.invoke(message(it))
                }
                main.postDelayed({ refresh() }, if (r.isFailure) 0 else refreshAfter)
            }
        }
    }
}

/** Settings of the app. The password is encrypted with a key that never leaves this phone's keystore. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    var host: String
        get() = p.getString("host", "") ?: ""
        set(v) = p.edit().putString("host", v.trim()).apply()
    /** MyFRITZ! address for away, e.g. https://xxxx.myfritz.net:44123; empty when not used. */
    var remote: String
        get() = p.getString("remote", "") ?: ""
        set(v) = p.edit().putString("remote", v.trim()).apply()
    var user: String
        get() = p.getString("user", "") ?: ""
        set(v) = p.edit().putString("user", v.trim()).apply()
    var password: String
        get() = decrypt(p.getString("password", "") ?: "")
        set(v) = p.edit().putString("password", encrypt(v)).apply()
    /** Fingerprints of the box's own certificates, read at home, for the address for away. */
    var pins: Set<String>
        get() = (p.getString("pins", "") ?: "").split(',').filter { it.isNotBlank() }.toSet()
        set(v) = p.edit().putString("pins", v.joinToString(",")).apply()
    var demo: Boolean
        get() = p.getBoolean("demo", false)
        set(v) = p.edit().putBoolean("demo", v).apply()
    var filter: String
        get() = p.getString("filter", "all") ?: "all"
        set(v) = p.edit().putString("filter", v).apply()

    /** "temp" shows temperatures and switches on the cards, "battery" the charge of every battery device. */
    var view: String
        get() = p.getString("view", "temp") ?: "temp"
        set(v) = p.edit().putString("view", v).apply()

    val configured get() = host.isNotBlank()

    fun logout() = p.edit().remove("host").remove("remote").remove("pins").remove("user").remove("password").putBoolean("demo", false).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    private fun encrypt(s: String): String = runCatching {
        if (s.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(c.iv + c.doFinal(s.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrDefault("")

    private fun decrypt(s: String): String = runCatching {
        if (s.isEmpty()) return ""
        val all = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrDefault("")

    private companion object {
        const val ALIAS = "glas-smarthome"
    }
}
